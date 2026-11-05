package com.qwen.tts.android.data

import android.media.AudioFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioDataTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun stereoPcmDownmixAndClipLimit() {
        val mono = MonoSamples()
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16384).putShort(-16384).putShort(8192).putShort(8192).putShort(32767).putShort(32767)
        input.flip()
        mono.append(input, 2, AudioFormat.ENCODING_PCM_16BIT, 2)
        assertArrayEquals(floatArrayOf(0f, .25f), mono.toArray(), .0001f)
    }

    @Test fun floatingPcmSanitizesInvalidAndClippedSamples() {
        val mono = MonoSamples()
        val input = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(2f).putFloat(-2f).putFloat(.5f)
        input.flip()
        mono.append(input, 1, AudioFormat.ENCODING_PCM_FLOAT, 4)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, .5f), mono.toArray(), .0001f)
    }

    @Test fun signed24BitDecodesNegativeSamples() {
        val mono = MonoSamples()
        mono.append(ByteBuffer.wrap(byteArrayOf(0, 0, -128, -1, -1, 127)).order(ByteOrder.LITTLE_ENDIAN), 1, AudioFormat.ENCODING_PCM_24BIT_PACKED, 2)
        assertArrayEquals(floatArrayOf(-1f, 1f), mono.toArray(), .0001f)
    }

    @Test fun wavHeaderAndChunkedPayloadRemainConsistent() {
        val file = temp.newFile("audio.wav")
        val samples = FloatArray(5_001) { .5f }
        PcmWav.write(file, samples, 24_000)
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(24_000, buffer.getInt(24))
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(samples.size * 2, buffer.getInt(40))
        assertEquals(44 + samples.size * 2, bytes.size)
        assertEquals(16383, buffer.getShort(bytes.size - 2).toInt())
    }

    @Test fun modelImportAcceptsOnlyExactSizeAndDigest() {
        val bytes = "GGUF-test-fixture".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val target = File(temp.root, "model.gguf")
        ModelStorage.import(ByteArrayInputStream(bytes), target, bytes.size.toLong(), hash)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(temp.root, "model.gguf.import").exists())
    }

    @Test fun damagedImportKeepsPriorModelAndCleansTemporaryFile() {
        val target = temp.newFile("existing.gguf").apply { writeText("original") }
        val hash = "0".repeat(64)
        assertThrows(IllegalArgumentException::class.java) { ModelStorage.import(ByteArrayInputStream(byteArrayOf(1, 2)), target, 2L, hash) }
        assertEquals("original", target.readText())
        assertFalse(File(temp.root, "existing.gguf.import").exists())
        assertThrows(IllegalArgumentException::class.java) { ModelStorage.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), target, 2L, hash) }
        assertEquals("original", target.readText())
        assertThrows(IllegalArgumentException::class.java) { ModelStorage.import(ByteArrayInputStream(byteArrayOf(1)), target, 2L, hash) }
    }

    @Test fun longChineseTextSplitsWithoutLosingContent() {
        val text = "今天我们一起学习声音克隆。这是第一段参考文字。".repeat(40)
        val parts = TextSegments.split(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 160 && it.isNotBlank() })
        assertEquals(text, parts.joinToString(""))
    }

    @Test fun segmentsDoNotBreakEmojiSurrogates() {
        val text = "语音".repeat(79) + "中😀文".repeat(12)
        val parts = TextSegments.split(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
        assertTrue(TextSegments.split("  ").isEmpty())
    }
}
