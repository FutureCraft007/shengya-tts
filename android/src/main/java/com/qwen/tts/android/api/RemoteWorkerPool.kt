package com.qwen.tts.android.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/** Each lane has its own Android process and therefore its own native globals. */
internal class RemoteWorkerPool(private val context: Context) : AutoCloseable {
    companion object {
        val SERVICES = listOf(QwenWorker1::class.java, QwenWorker2::class.java, QwenWorker3::class.java,
            QwenWorker4::class.java, QwenWorker5::class.java, QwenWorker6::class.java)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val lanes = SERVICES.map { Lane(it) }
    private val available = ArrayBlockingQueue<Lane>(ApiJobs.MAX_CONCURRENCY).apply { addAll(lanes) }
    @Volatile private var closed = false

    fun execute(job: JSONObject, cancelled: () -> Boolean, progress: (Int, String) -> Unit): JSONObject {
        var lane: Lane? = null
        while (lane == null) {
            check(!closed && !cancelled()) { "任务已取消或服务已停止" }
            lane = available.poll(100, TimeUnit.MILLISECONDS)
        }
        try { return lane.execute(job, cancelled, progress) }
        finally { if (!closed) { lane.idle(); available.offer(lane) } }
    }

    private inner class Lane(private val service: Class<*>) {
        private var connection: Client? = null
        private val release = Runnable { synchronized(this) { connection?.close(); connection = null } }

        fun execute(job: JSONObject, cancelled: () -> Boolean, progress: (Int, String) -> Unit): JSONObject {
            val client = synchronized(this) {
                handler.removeCallbacks(release)
                check(!closed) { "本地服务已停止" }
                connection?.takeIf { it.alive } ?: run { connection?.close(); Client(service).also { connection = it } }
            }
            try { return client.execute(job, cancelled, progress) }
            catch (error: Exception) { synchronized(this) { client.close(); if (connection === client) connection = null }; throw error }
        }
        fun idle() { handler.postDelayed(release, 30_000) }
        fun close() = synchronized(this) { handler.removeCallbacks(release); connection?.close(); connection = null }
    }

    private inner class Client(service: Class<*>) : ServiceConnection {
        @Volatile var alive = true
            private set
        private val ready = CompletableFuture<Messenger>()
        @Volatile private var result: CompletableFuture<JSONObject>? = null
        @Volatile private var id: String? = null
        @Volatile private var progress: ((Int, String) -> Unit)? = null
        private var bound = false
        private val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.data.getString("id") == id) {
                val payload = message.data.getString("payload").orEmpty()
                runCatching {
                    when (message.what) {
                        WorkerMessages.PROGRESS -> JSONObject(payload).let { progress?.invoke(it.getInt("progress"), it.getString("message")) }
                        WorkerMessages.RESULT -> result?.complete(JSONObject(payload))
                        WorkerMessages.ERROR -> result?.completeExceptionally(IllegalStateException(payload))
                    }
                }.onFailure { result?.completeExceptionally(it) }
            }
            true
        })

        init {
            bound = context.bindService(Intent(context, service), this, Context.BIND_AUTO_CREATE)
            if (!bound) ready.completeExceptionally(IllegalStateException("无法绑定独立推理进程"))
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(Messenger(service)) }
        override fun onServiceDisconnected(name: ComponentName) { fail() }
        override fun onBindingDied(name: ComponentName) { fail() }
        override fun onNullBinding(name: ComponentName) { fail() }
        private fun fail() {
            alive = false
            val error = IllegalStateException("推理工作进程已退出，可能内存不足；请降低并发数后明确重试")
            ready.completeExceptionally(error); result?.completeExceptionally(error)
        }
        fun execute(job: JSONObject, cancelled: () -> Boolean, onProgress: (Int, String) -> Unit): JSONObject {
            val future = CompletableFuture<JSONObject>()
            id = job.getString("id"); result = future; progress = onProgress
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
            var remote: Messenger? = null
            while (remote == null) {
                check(!closed && !cancelled()) { "任务已取消或服务已停止" }
                check(System.nanoTime() < deadline) { "绑定推理进程超时" }
                try { remote = ready.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
            remote.send(Message.obtain(null, WorkerMessages.RUN).apply {
                replyTo = reply
                data = Bundle().apply { putString("id", id); putString("payload", job.toString()) }
            })
            var cancellationSent = false
            try {
                while (true) {
                    check(!closed) { "本地服务已停止" }
                    if (cancelled() && !cancellationSent) {
                        remote.send(Message.obtain(null, WorkerMessages.CANCEL).apply { data = Bundle().apply { putString("id", id) } })
                        cancellationSent = true
                    }
                    check(System.nanoTime() < deadline) { "本次推理超过 30 分钟，请降低并发数或缩短文案后重试" }
                    try { return future.get(100, TimeUnit.MILLISECONDS) }
                    catch (_: TimeoutException) { }
                    catch (error: java.util.concurrent.ExecutionException) { throw IllegalStateException(error.cause?.message ?: "独立推理失败", error.cause) }
                }
            } finally { result = null; progress = null; id = null }
        }
        fun close() {
            fail()
            if (bound) { bound = false; runCatching { context.unbindService(this) } }
        }
    }

    override fun close() { closed = true; lanes.forEach { it.close() }; available.clear() }
}
