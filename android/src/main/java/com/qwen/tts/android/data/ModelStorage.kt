package com.qwen.tts.android.data

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object ModelStorage {
    fun download(url: String, target: File, size: Long, sha256: String, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, "${target.name}.download")
        if (partial.length() > size) partial.delete()
        if (partial.length() < size) {
            val resume = partial.length()
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                if (resume > 0) setRequestProperty("Range", "bytes=$resume-")
            }
            try {
                val code = connection.responseCode
                check(code == 200 || code == 206) { "下载服务器返回 HTTP $code，请稍后重试或切换下载源。" }
                val append = code == 206 && resume > 0
                if (code == 206) {
                    val start = connection.getHeaderField("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    check(start == if (append) resume else 0L) { "下载服务器返回了错误的续传位置，请更换下载源。" }
                }
                var count = if (append) resume else 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var lastUpdate = 0L
                        while (true) {
                            check(!cancelled()) { "操作已取消，可稍后继续下载。" }
                            val read = input.read(buffer)
                            if (read < 0) break
                            check(count + read <= size) { "模型大小与预期不符，请重新下载。" }
                            output.write(buffer, 0, read)
                            count += read
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate >= 250) {
                                progress(count)
                                lastUpdate = now
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        check(partial.length() == size) { "模型下载尚未完成，点击下载可继续。" }
        check(!cancelled()) { "操作已取消。" }
        try {
            verify(partial, size, sha256)
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        progress(size)
    }

    fun import(input: InputStream, target: File, size: Long, sha256: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.import")
        try {
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= size) { "文件过大，请导入设置页指定的 Q4_K_M 模型。" }
                    output.write(buffer, 0, read)
                }
            }
            verify(temporary, size, sha256)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun verify(file: File, size: Long, sha256: String) {
        require(file.length() == size) { "模型文件大小不正确，请确认已完整下载。" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == sha256) { "模型 SHA-256 校验失败，请重新获取正确的文件。" }
    }
}
