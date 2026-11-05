package com.qwen.tts.android.api

import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Small HTTP/1.1 server, bound exclusively to IPv4 loopback; one bounded request per socket. */
internal class LoopbackServer(
    port: Int,
    private val token: String,
    private val route: (String, String, JSONObject?) -> Reply,
) : AutoCloseable {
    data class Reply(val status: Int = 200, val json: JSONObject? = null, val file: File? = null)
    private val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val acceptor = Thread({
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                connections.add(socket)
                try { workers.execute { handle(socket) } } catch (_: Exception) { connections.remove(socket); socket.close() }
            } catch (_: Exception) { if (!server.isClosed) close() }
        }
    }, "qwen-http-accept").apply { isDaemon = true; start() }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                var headerSize = 0
                fun line(): String {
                    val bytes = ArrayList<Byte>()
                    while (true) {
                        val b = input.read()
                        require(b >= 0 && ++headerSize <= 16_384) { "请求头不完整或过大" }
                        if (b == 10) break
                        bytes.add(b.toByte())
                    }
                    return bytes.toByteArray().toString(Charsets.US_ASCII).removeSuffix("\r")
                }
                val first = line().split(' ')
                require(first.size == 3 && first[2] == "HTTP/1.1" && first[0] in setOf("GET", "POST", "DELETE")) { "不支持的 HTTP 请求" }
                require(first[1].matches(Regex("/[a-zA-Z0-9/_-]*"))) { "接口路径无效" }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val row = line()
                    if (row.isEmpty()) break
                    val at = row.indexOf(':')
                    require(at > 0) { "请求头无效" }
                    val name = row.substring(0, at).lowercase()
                    require(!headers.containsKey(name)) { "不接受重复请求头" }
                    headers[name] = row.substring(at + 1).trim()
                }
                if (!MessageDigest.isEqual(("Bearer $token").toByteArray(), headers["authorization"].orEmpty().toByteArray())) {
                    send(socket, Reply(401, JSONObject().put("error", "连接口令不正确"))); return
                }
                require(!headers.containsKey("transfer-encoding")) { "不支持分块请求" }
                val length = headers["content-length"]?.toIntOrNull() ?: if (headers.containsKey("content-length")) -1 else 0
                require(length in 0..131_072) { "请求体过大或长度无效" }
                require(first[0] == "POST" || length == 0) { "此接口不接收请求体" }
                val body = if (length > 0) {
                    require(headers["content-type"].orEmpty().substringBefore(';').trim() == "application/json") { "请发送 JSON 数据" }
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) { val n = input.read(bytes, read, length - read); require(n > 0) { "请求体不完整" }; read += n }
                    JSONObject(bytes.toString(Charsets.UTF_8))
                } else null
                send(socket, route(first[0], first[1], body))
            } catch (error: Exception) {
                runCatching { send(socket, Reply(400, JSONObject().put("error", error.message ?: "请求无效"))) }
            } finally { connections.remove(socket) }
        }
    }

    private fun send(socket: Socket, reply: Reply) {
        val file = reply.file
        val bytes = reply.json?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val length = file?.length() ?: bytes.size.toLong()
        val type = if (file == null) "application/json; charset=utf-8" else "audio/wav"
        val output = socket.getOutputStream().buffered(65536)
        output.write("HTTP/1.1 ${reply.status} Result\r\nContent-Type: $type\r\nContent-Length: $length\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        if (file != null) file.inputStream().use { it.copyTo(output, 65536) } else output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
