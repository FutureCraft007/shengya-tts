package com.qwen.tts.android.api

import android.content.Context
import android.os.SystemClock
import com.qwen.tts.android.audio.LocalSpeechDenoiser
import com.qwen.tts.android.data.PcmWav
import com.qwen.tts.android.data.TextSegments
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Only used in a dedicated worker process, one invocation at a time. */
internal class WorkerSynthesizer(private val context: Context) : AutoCloseable {
    private var engine: QwenEngine? = null
    private var loadedModel: String? = null

    fun synthesize(job: JSONObject, cancelled: () -> Boolean, progress: (Int, String) -> Unit): JSONObject {
        check(!cancelled()) { "任务已取消" }
        val modelName = job.getString("model")
        require(modelName in setOf(LocalApiService.BASE, LocalApiService.CUSTOM))
        val directory = File(context.filesDir, "qwen3-tts-models")
        check(File(directory, modelName).isFile && File(directory, "qwen-tokenizer-12hz-Q4_K_M.gguf").isFile) { "请先在声芽设置中下载或导入对应模型" }
        val started = SystemClock.elapsedRealtime()
        progress(1, "正在独立进程加载模型")
        val native = engine ?: QwenEngine().also { engine = it }
        native.setBackendPreference(QwenEngine.BACKEND_CPU)
        native.setCpuThreads(job.getInt("cpuThreads").coerceIn(1, 16))
        if (loadedModel != modelName) {
            loadedModel = null
            check(native.loadModels(directory.absolutePath, modelName)) { native.getLastError() ?: "模型加载失败" }
            loadedModel = modelName
        }
        val speaker = job.optString("speaker").takeIf { it.isNotBlank() }
        val speakers = if (speaker != null) native.getAvailableSpeakers() else emptyList()
        if (speaker != null) check(speaker in speakers) { "当前模型没有这个预置音色，请刷新列表" }
        val parts = TextSegments.split(job.getString("text"))
        val chunks = mutableListOf<FloatArray>()
        var rate = 0
        try {
            parts.forEachIndexed { index, part ->
                check(!cancelled()) { "任务已取消" }
                native.setProgressCallback { tokens, max ->
                    progress(5 + ((index + tokens.toDouble() / max.coerceAtLeast(1)) / parts.size * 80).toInt(), "正在生成第 ${index + 1}/${parts.size} 段")
                }
                val params = QwenEngine.NativeParams(languageId = job.getInt("language"), speaker = speaker, maxAudioTokens = 1024)
                val prompt = job.optString("prompt").takeIf { it.isNotBlank() }
                val result = if (prompt != null) native.synthesizeWithIclPrompt(part, prompt, params)
                    else native.synthesize(part, speakerEmbeddingPath = job.optString("embedding").takeIf { it.isNotBlank() }, params = params)
                check(result.success && result.audio?.isNotEmpty() == true) { result.errorMsg ?: "本地合成失败" }
                if (rate == 0) rate = result.sampleRate
                check(rate == result.sampleRate && rate > 0) { "配音采样率不一致" }
                chunks += result.audio
            }
        } finally { native.setProgressCallback(null) }
        check(!cancelled()) { "任务已取消" }
        val samples = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        chunks.forEach { it.copyInto(samples, offset); offset += it.size }
        val id = job.getString("id")
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val output = File(context.filesDir, "generations").apply { mkdirs() }
        val original = File(output, "api-$id.wav")
        val clean = File(output, "api-$id-denoised.wav")
        var complete = false
        try {
            PcmWav.write(original, samples, rate)
            val denoise = if (job.optBoolean("denoise")) LocalSpeechDenoiser.process(context, original, clean, cancelled) { p ->
                progress(85 + p * 14 / 100, "正在本机降噪")
            } else null
            check(!cancelled()) { "任务已取消" }
            val result = JSONObject().put("originalPath", original.absolutePath).put("audioPath", (if (denoise != null) clean else original).absolutePath)
                .put("sampleRate", rate).put("sampleCount", samples.size).put("durationMs", samples.size * 1000L / rate)
                .put("synthesisMillis", SystemClock.elapsedRealtime() - started).put("speakers", JSONArray(speakers))
            if (denoise != null) result.put("denoisedPath", clean.absolutePath).put("denoisedSampleRate", denoise.sampleRate).put("denoisedSampleCount", denoise.sampleCount)
            complete = true
            return result
        } finally { if (!complete) { original.delete(); clean.delete() } }
    }

    override fun close() { engine?.close(); engine = null; loadedModel = null }
}
