package com.qwen.tts.android.cloud

import android.content.Context
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

data class CloudConfig(val endpoint: String = "", val model: String = "", val voice: String = "") {
    internal fun url(): URI {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0 && !uri.rawPath.isNullOrBlank() && uri.rawPath != "/") {
            "请填写完整的 HTTPS 接口地址，不要包含凭据、查询参数或片段。"
        }
        return uri
    }

    internal fun requestBody(text: String): ByteArray {
        require(model.isNotBlank() && voice.isNotBlank()) { "请填写模型和音色 ID。" }
        require(text.isNotBlank() && text.length <= 2000) { "请输入 1～2000 字的文案。" }
        return JSONObject().put("model", model).put("voice", voice).put("input", text)
            .put("response_format", "wav").toString().toByteArray(Charsets.UTF_8)
    }
}

internal class CloudRepository(context: Context) {
    private val secrets = CloudSecrets(context)
    private val cache = File(context.filesDir, "cloud-samples").apply { mkdirs() }

    private fun slot(endpoint: String) = "https-tts-" + hash(endpoint)
    fun hasKey(endpoint: String) = endpoint.isNotBlank() &&
        runCatching { secrets.read(slot(endpoint)).isNotBlank() }.getOrDefault(false)

    fun saveKey(endpoint: String, key: String) {
        val clean = key.trim().removePrefix("Bearer ").trim()
        require(clean.length in 1..4096 && clean.all { it in '!'..'~' }) { "请填写有效的 API Key。" }
        secrets.save(slot(endpoint), clean)
    }

    fun clearKey(endpoint: String) = secrets.save(slot(endpoint), "")

    fun sample(config: CloudConfig, text: String): File {
        val url = config.url()
        val body = config.requestBody(text)
        val key = secrets.read(slot(config.endpoint))
        require(key.isNotBlank()) { "请先保存当前地址的 API Key。" }
        val id = hash(config.endpoint + "\n" + hash(key) + "\n" + body.toString(Charsets.UTF_8))
        val result = File(cache, "$id.wav")
        if (result.isFile) return result
        val pending = File(cache, "$id.pending")
        check(pending.createNewFile()) { "上次请求结果不明确，请先核查服务商，再修改文案发起新请求。" }
        val connection = (url.toURL().openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status in 300..499) pending.delete()
            check(status !in 300..399) { "服务返回重定向，请填写最终 HTTPS 接口地址。" }
            check(status in 200..299) { "云端请求失败（HTTP $status），请检查地址、凭据和模型。" }
            DataInputStream(connection.inputStream.buffered()).use { input ->
                val header = ByteArray(12)
                input.readFully(header)
                check(header.decodeToString(0, 4) == "RIFF" &&
                    header.decodeToString(8, 12) == "WAVE") { "服务必须直接返回 WAV 音频。" }
                val output = AtomicFile(result)
                val stream = output.startWrite()
                try {
                    stream.write(header)
                    var total = header.size
                    val buffer = ByteArray(16 * 1024)
                    val deadline = System.nanoTime() + 180_000_000_000L
                    while (true) {
                        check(System.nanoTime() < deadline) { "接收音频超时。" }
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        check(total <= 16 * 1024 * 1024) { "音频超过 16 MB，请缩短文案。" }
                        stream.write(buffer, 0, size)
                    }
                    check(total > header.size) { "服务返回了空音频。" }
                    output.finishWrite(stream)
                } catch (error: Throwable) {
                    output.failWrite(stream)
                    throw error
                }
            }
            pending.delete()
            return result
        } finally {
            connection.disconnect()
        }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
