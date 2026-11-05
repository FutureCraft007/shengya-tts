package com.qwen.tts.android.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object PcmWav {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        require(sampleRate > 0 && samples.isNotEmpty()) { "没有可以保存的音频。" }
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples.size * 2)
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
            header.putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
            header.putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size * 2)
            output.write(header.array())
            val chunk = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { sample ->
                val value = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                chunk.putShort((value * 32767f).toInt().toShort())
                if (!chunk.hasRemaining()) {
                    output.write(chunk.array())
                    chunk.clear()
                }
            }
            if (chunk.position() > 0) output.write(chunk.array(), 0, chunk.position())
        }
    }
}
