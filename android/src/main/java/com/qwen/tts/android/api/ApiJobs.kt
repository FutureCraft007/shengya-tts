package com.qwen.tts.android.api

import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Durable identities prevent an HTTP retry from generating the same speech twice. */
internal class ApiJobs(
    private val directory: File,
    concurrency: Int = 1,
    private val onActivity: (Boolean) -> Unit = {},
    private val process: (JSONObject, () -> Boolean, (Int, String) -> Unit) -> JSONObject,
) : AutoCloseable {
    companion object {
        const val MAX_CONCURRENCY = 6
        const val CAPACITY = 16
        fun validateConcurrency(value: Int): Int {
            require(value in 1..MAX_CONCURRENCY) { "并发数应为 1～6 的整数" }
            return value
        }
    }
    private val jobs = linkedMapOf<String, JSONObject>()
    private val cancel = mutableMapOf<String, AtomicBoolean>()
    private val pendingWork = mutableMapOf<String, Runnable>()
    private val active = mutableSetOf<String>()
    private val worker = ThreadPoolExecutor(validateConcurrency(concurrency), concurrency, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
        .apply { allowCoreThreadTimeOut(true) }
    private var closed = false

    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.extension == "json" }?.sortedBy { it.lastModified() }?.forEach { file ->
            runCatching {
                val job = JSONObject(file.readText())
                if (job.optString("state") in setOf("queued", "running", "cancelling")) {
                    job.put("state", "failed").put("message", "服务已中断；不会自动重新生成，请明确创建新任务")
                    save(job)
                }
                jobs[job.getString("id")] = job
            }
        }
    }

    @Synchronized fun submit(input: JSONObject): JSONObject {
        check(!closed) { "本地服务已停止" }
        val text = input.getString("text").trim()
        val voice = input.getString("voice")
        val requestId = input.optString("requestId").ifBlank { UUID.randomUUID().toString() }
        require(text.length in 1..2000 && voice.length in 1..160 && requestId.matches(Regex("[a-zA-Z0-9_-]{8,100}"))) { "文案、音色或请求 ID 无效" }
        require(input.optInt("language", 2055) in setOf(2050,2053,2054,2061,2055,2058,2064,2069)) { "语言不支持" }
        val signature = JSONObject().put("text", text).put("voice", voice).put("language", input.optInt("language", 2055)).put("denoise", input.optBoolean("denoise", false)).toString()
        jobs.values.firstOrNull { it.optString("requestId") == requestId }?.let {
            require(it.getString("signature") == signature) { "同一请求 ID 的文案或参数已改变，请新建请求 ID" }
            return publicJob(it)
        }
        check(jobs.values.count { it.optString("state") in setOf("queued", "running", "cancelling") } < CAPACITY) { "本地队列已满，请等待已有任务完成" }
        if (jobs.size >= 256) {
            val old = jobs.entries.firstOrNull { it.value.optString("state") in setOf("success", "failed", "cancelled") }
            if (old != null) { jobs.remove(old.key); File(directory, "${old.key}.json").delete() }
        }
        val id = UUID.randomUUID().toString()
        val job = JSONObject(signature).put("id", id).put("requestId", requestId).put("signature", signature)
            .put("state", "queued").put("progress", 0).put("message", "等待本地引擎").put("createdAt", System.currentTimeMillis())
        val flag = AtomicBoolean(false)
        jobs[id] = job; cancel[id] = flag; save(job)
        notifyActivity()
        val task = Runnable { run(id, flag) }
        pendingWork[id] = task
        worker.execute(task)
        return publicJob(job)
    }

    private fun run(id: String, flag: AtomicBoolean) {
        val input = synchronized(this) {
            val job = jobs.getValue(id)
            pendingWork.remove(id)
            if (flag.get() || closed) { if (!closed) finishCancelled(job); cancel.remove(id); notifyActivity(); return }
            active.add(id)
            job.put("state", "running"); save(job)
            notifyActivity()
            JSONObject(job.toString())
        }
        try {
            val result = process(input, { flag.get() || synchronized(this) { closed } }) { progress, message ->
                synchronized(this) {
                    jobs[id]?.let { job ->
                        if (!flag.get()) job.put("progress", progress.coerceIn(0,99)).put("message", message)
                    }
                }
            }
            synchronized(this) {
                val job = jobs.getValue(id)
                // close() has already committed every cancelled job. Do not
                // rewrite those files while a new server restores the queue.
                if (closed) Unit else if (flag.get()) finishCancelled(job) else {
                    result.keys().forEach { key -> job.put(key, result.get(key)) }
                    job.put("state", "success").put("progress", 100).put("message", "配音已完成"); save(job)
                }
            }
        } catch (error: Exception) {
            synchronized(this) {
                jobs[id]?.let { job ->
                    if (closed) Unit else if (flag.get()) finishCancelled(job) else {
                        job.put("state", "failed").put("message", error.message ?: "本地合成失败"); save(job)
                    }
                }
            }
        } finally { synchronized(this) { active.remove(id); cancel.remove(id); notifyActivity() } }
    }

    @Synchronized fun get(id: String): JSONObject? = jobs[id]?.let(::publicJob)
    @Synchronized fun findRequest(id: String): JSONObject? = jobs.values.firstOrNull { it.optString("requestId") == id }?.let(::publicJob)
    @Synchronized fun cancel(id: String): JSONObject? {
        val job = jobs[id] ?: return null
        if (job.optString("state") in setOf("queued", "running", "cancelling")) {
            cancel[id]?.set(true)
            if (id !in active) {
                pendingWork.remove(id)?.let { worker.remove(it) }
                cancel.remove(id)
                finishCancelled(job)
            }
            else job.put("state", "cancelling").put("message", "正在取消，当前片段运算结束后停止")
            save(job)
            notifyActivity()
        }
        return publicJob(job)
    }
    @Synchronized fun audio(id: String): File? = jobs[id]?.takeIf { it.optString("state") == "success" }
        ?.optString("audioPath")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    private fun publicJob(job: JSONObject) = JSONObject(job.toString()).apply {
        remove("audioPath"); remove("signature"); remove("text")
        if (optString("state") == "success") put("audio", "/jobs/${getString("id")}/audio")
    }
    private fun finishCancelled(job: JSONObject) { job.put("state", "cancelled").put("message", "任务已取消"); save(job) }
    private fun save(job: JSONObject) {
        val file = File(directory, "${job.getString("id")}.json")
        val pending = File(directory, "${file.name}.partial")
        pending.writeText(job.toString())
        java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    @Synchronized fun setConcurrency(value: Int) {
        check(!closed) { "本地服务已停止" }
        validateConcurrency(value)
        if (value > worker.maximumPoolSize) worker.maximumPoolSize = value
        worker.corePoolSize = value
        worker.maximumPoolSize = value
    }
    @Synchronized fun status(): JSONObject = JSONObject().put("concurrency", worker.corePoolSize)
        .put("maxConcurrency", MAX_CONCURRENCY).put("queueCapacity", CAPACITY).put("activeJobs", active.size)
        .put("queuedJobs", jobs.values.count { it.optString("state") == "queued" })
        .put("cancellingJobs", jobs.values.count { it.optString("state") == "cancelling" })

    private fun notifyActivity() = onActivity(jobs.values.any { it.optString("state") in setOf("queued", "running", "cancelling") })
    @Synchronized override fun close() {
        closed = true
        cancel.values.forEach { it.set(true) }
        jobs.values.filter { it.optString("state") in setOf("queued", "running", "cancelling") }.forEach(::finishCancelled)
        notifyActivity()
        worker.shutdownNow()
        pendingWork.clear()
    }
}
