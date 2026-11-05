package com.qwen.tts.android.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.qwen.tts.android.MainActivity
import com.qwen.tts.android.data.EngineHost
import com.qwen.tts.android.data.db.GenerationEntity
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.audio.LocalSpeechDenoiser
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

internal data class ApiServiceState(val running: Boolean = false, val port: Int = 9001, val message: String = "本地服务未启动", val concurrency: Int = 6, val activeJobs: Int = 0, val queuedJobs: Int = 0)

class LocalApiService : Service() {
    companion object {
        internal const val ACTION_CONFIG = "com.qwen.tts.android.zh.CONFIG_LOCAL_API"
        internal fun concurrency(context: Context) = context.getSharedPreferences("local-api", 0).getInt("concurrency", 6).coerceIn(1, 6)
        internal fun configure(context: Context, value: Int) {
            ApiJobs.validateConcurrency(value)
            check(context.getSharedPreferences("local-api", 0).edit().putInt("concurrency", value).commit()) { "无法保存并发设置" }
            if (state.value.running) context.startService(Intent(context, LocalApiService::class.java).setAction(ACTION_CONFIG))
        }
        internal const val ACTION_START = "com.qwen.tts.android.zh.START_LOCAL_API"
        internal val state = MutableStateFlow(ApiServiceState())
        internal const val BASE = "qwen-talker-0.6b-base-Q4_K_M.gguf"
        internal const val CUSTOM = "qwen-talker-0.6b-customvoice-Q4_K_M.gguf"
        internal fun token(context: Context): String {
            val prefs = context.getSharedPreferences("local-api", 0)
            return prefs.getString("token", null) ?: (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).also {
                check(prefs.edit().putString("token", it).commit()) { "无法保存本机连接口令" }
            }
        }
        internal fun start(context: Context, port: Int) {
            require(port in 1024..65535) { "端口应在 1024～65535 之间" }
            context.startForegroundService(Intent(context, LocalApiService::class.java).setAction(ACTION_START).putExtra("port", port))
        }
    }

    private var server: LoopbackServer? = null
    private var jobs: ApiJobs? = null
    private var pool: RemoteWorkerPool? = null
    private val dao by lazy { QwenDatabase.getDatabase(this).qwenDao() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CONFIG) {
            if (server != null) { jobs?.setConcurrency(concurrency(this)); updateQueueState() }
            else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        val port = intent.getIntExtra("port", 9001)
        try {
            require(port in 1024..65535)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("local-api", "本机配音服务", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "local-api").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("声芽本地配音服务").setContentText("127.0.0.1:$port · 点击打开设置").setContentIntent(open).setOngoing(true).build()
            startForeground(9001, notification, if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            pool = RemoteWorkerPool(this)
            val store = ApiJobs(File(filesDir, "local-api-jobs"), concurrency(this), { busy -> EngineHost.apiBusy.value = busy; updateQueueState() }, ::generate)
            jobs = store
            server = LoopbackServer(port, token(this)) { method, path, body ->
                fun json(value: JSONObject, status: Int = 200) = LoopbackServer.Reply(status, value)
                when {
                    method == "GET" && path == "/health" -> json(store.status().put("service", "qwen-local-tts").put("protocol", 1)
                        .put("execution", "isolated-processes").put("busy", EngineHost.apiBusy.value || EngineHost.uiBusy.value).put("model", EngineHost.modelName ?: JSONObject.NULL)
                        .put("baseReady", File(filesDir, "qwen3-tts-models/$BASE").isFile).put("customReady", File(filesDir, "qwen3-tts-models/$CUSTOM").isFile))
                    method == "GET" && path == "/config" -> json(store.status())
                    method == "POST" && path == "/config" -> {
                        val value = body?.opt("concurrency") as? Number ?: error("请填写并发数")
                        require(value.toDouble() == value.toInt().toDouble()) { "并发数应为整数" }
                        val chosen = ApiJobs.validateConcurrency(value.toInt())
                        check(getSharedPreferences("local-api", 0).edit().putInt("concurrency", chosen).commit()) { "无法保存并发设置" }
                        store.setConcurrency(chosen); updateQueueState(); json(store.status())
                    }
                    method == "GET" && path == "/voices" -> json(JSONObject().put("voices", voiceList()))
                    method == "POST" && path == "/jobs" -> json(store.submit(body ?: error("缺少配音参数")), 202)
                    method == "GET" && path.matches(Regex("/requests/[a-zA-Z0-9_-]{8,100}")) -> {
                        val job = store.findRequest(path.substringAfterLast('/'))
                        if (job == null) json(JSONObject().put("error", "请求尚未提交"), 404) else json(job)
                    }
                    path.matches(Regex("/jobs/[a-f0-9-]{36}/audio")) && method == "GET" -> {
                        val file = store.audio(path.split('/')[2])
                        if (file == null) json(JSONObject().put("error", "音频未就绪或已删除"), 404) else LoopbackServer.Reply(file = file)
                    }
                    path.matches(Regex("/jobs/[a-f0-9-]{36}")) && method in setOf("GET", "DELETE") -> {
                        val id = path.substringAfterLast('/')
                        val job = if (method == "GET") store.get(id) else store.cancel(id)
                        if (job == null) json(JSONObject().put("error", "任务不存在"), 404) else json(job)
                    }
                    else -> json(JSONObject().put("error", "接口不存在"), 404)
                }
            }
            EngineHost.serviceEnabled = true
            getSharedPreferences("local-api", 0).edit().putInt("port", port).apply()
            state.value = ApiServiceState(true, port, "本机接口已启动，同一手机的稿好了可以连接", concurrency(this))
        } catch (error: Exception) {
            state.value = ApiServiceState(false, port, "启动失败：${error.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun voiceList(): JSONArray = runBlocking {
        val list = JSONArray()
        dao.listVoices().forEach { voice -> list.put(JSONObject().put("id", voice.voiceId).put("name", voice.name)
            .put("description", if (voice.iclPromptPath != null) "本地完整克隆 · 参考音频与逐字稿" else "本地音色向量克隆")) }
        if (File(filesDir, "qwen3-tts-models/$CUSTOM").isFile) getSharedPreferences("preferences", 0).getString("presetSpeakers", "").orEmpty()
            .lineSequence().filter { it.isNotBlank() }.forEach { speaker -> list.put(JSONObject().put("id", "preset:$speaker").put("name", speaker).put("description", "CustomVoice 预置音色")) }
        list
    }

    private fun updateQueueState() {
        val current = jobs?.status() ?: return
        state.update { it.copy(concurrency = current.getInt("concurrency"), activeJobs = current.getInt("activeJobs"), queuedJobs = current.getInt("queuedJobs")) }
    }

    private fun generate(job: JSONObject, cancelled: () -> Boolean, progress: (Int, String) -> Unit): JSONObject {
        val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qwen:local-tts")
        wake.acquire(30 * 60 * 1000L)
        val output = File(filesDir, "generations")
        val original = File(output, "api-${job.getString("id")}.wav")
        val clean = File(output, "api-${job.getString("id")}-denoised.wav")
        var saved = false
        try {
            while (EngineHost.uiBusy.value) {
                check(!cancelled()) { "任务已取消" }
                progress(0, "等待声芽当前本地任务完成"); Thread.sleep(100)
            }
            check(!cancelled()) { "任务已取消" }
            // The UI engine is no longer used by the API. Release its extra
            // model copy before loading up to six isolated worker engines.
            synchronized(EngineHost.lock) { if (!EngineHost.uiBusy.value) EngineHost.close() }
            val voiceId = job.getString("voice")
            val preset = voiceId.startsWith("preset:")
            require(!preset || voiceId.removePrefix("preset:").isNotBlank()) { "请选择有效的预置音色" }
            val voice = if (preset) null else runBlocking { dao.getVoice(voiceId) } ?: if (!preset) error("音色不存在，请刷新音色列表") else null
            val preferredThreads = getSharedPreferences("preferences", 0).getInt("cpuThreads", 4).coerceIn(1, 16)
            val threads = minOf(preferredThreads, (Runtime.getRuntime().availableProcessors() / concurrency(this)).coerceAtLeast(1))
            val input = JSONObject(job.toString()).put("model", if (preset) CUSTOM else BASE).put("cpuThreads", threads)
                .put("speaker", if (preset) voiceId.removePrefix("preset:") else "")
                .put("embedding", voice?.speakerEmbeddingPath.orEmpty()).put("prompt", voice?.iclPromptPath.orEmpty())
            val result = checkNotNull(pool) { "本地推理服务已停止" }.execute(input, cancelled, progress)
            check(!cancelled()) { "任务已取消" }
            result.optJSONArray("speakers")?.takeIf { it.length() > 0 }?.let { speakers ->
                getSharedPreferences("preferences", 0).edit().putString("presetSpeakers", (0 until speakers.length()).joinToString("\n") { speakers.getString(it) }).apply()
            }
            val hasDenoise = result.has("denoisedPath")
            val id = runBlocking { dao.insertGeneration(GenerationEntity(text = job.getString("text"), voiceId = voice?.voiceId,
                voiceName = voice?.name ?: voiceId.removePrefix("preset:"), wavPath = result.getString("originalPath"),
                sampleRate = result.getInt("sampleRate"), sampleCount = result.getInt("sampleCount"), synthesisMillis = result.getLong("synthesisMillis"),
                denoisedWavPath = if (hasDenoise) result.getString("denoisedPath") else null,
                denoisedSampleRate = if (hasDenoise) result.getInt("denoisedSampleRate") else null,
                denoisedSampleCount = if (hasDenoise) result.getInt("denoisedSampleCount") else null,
                denoiseModel = if (hasDenoise) LocalSpeechDenoiser.MODEL else null)) }
            saved = true
            return JSONObject().put("audioPath", result.getString("audioPath")).put("sampleRate", if (hasDenoise) result.getInt("denoisedSampleRate") else result.getInt("sampleRate"))
                .put("sampleCount", if (hasDenoise) result.getInt("denoisedSampleCount") else result.getInt("sampleCount"))
                .put("durationMs", result.getLong("durationMs")).put("generationId", id)
        } finally {
            if (!saved) { original.delete(); clean.delete() }
            if (wake.isHeld) wake.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { state.value = state.value.copy(running = false, message = "系统已结束本次服务运行，请在声芽设置中重新启动"); stopSelf() }
    override fun onDestroy() {
        server?.close(); jobs?.close(); pool?.close(); pool = null; EngineHost.serviceEnabled = false; EngineHost.apiBusy.value = false
        state.value = state.value.copy(running = false, activeJobs = 0, queuedJobs = 0, message = if (state.value.running) "本地服务已停止" else state.value.message)
        Thread({ synchronized(EngineHost.lock) { if (!EngineHost.uiBusy.value && !EngineHost.serviceEnabled) EngineHost.close() } }, "qwen-api-cleanup").start()
        super.onDestroy()
    }
}
