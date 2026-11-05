package com.qwen.tts.android.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Uses Android's local codecs. Source audio is never uploaded. */
class AudioImporter(private val context: Context) {
    fun import(uri: Uri, output: File, requireWhole: Boolean = false): RecordingResult {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件中没有可读取的音轨，请选择 WAV、MP3、M4A 或 FLAC 音频。")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("无法识别音频格式。")
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else AudioFormat.ENCODING_PCM_16BIT
            val samples = MonoSamples()

            fun append(buffer: ByteBuffer) {
                require(sampleRate in 8_000..192_000 && channels in 1..8) { "音频采样率或声道数不受支持。" }
                samples.append(buffer.order(ByteOrder.LITTLE_ENDIAN), channels, encoding, sampleRate * if (requireWhole) 31 else 30)
            }

            if (mime == "audio/raw") {
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                while (samples.size < sampleRate * if (requireWhole) 31 else 30) {
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) break
                    buffer.position(0)
                    buffer.limit(count)
                    append(buffer)
                    if (!extractor.advance()) break
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
                started = true
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var lastProgress = System.nanoTime()
                while (!outputEnded && samples.size < sampleRate * if (requireWhole) 31 else 30) {
                    check(System.nanoTime() - lastProgress < TimeUnit.SECONDS.toNanos(20)) {
                        "音频解码超时，请先把文件转换为 WAV 后重试。"
                    }
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex) ?: error("无法读取解码缓冲区。")
                            input.clear()
                            val count = extractor.readSampleData(input, 0)
                            if (count < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val decoded = decoder.outputFormat
                        sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            decoded.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        lastProgress = System.nanoTime()
                    } else if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = decoder.getOutputBuffer(outputIndex) ?: error("无法读取解码结果。")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                append(buffer)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            require(samples.size >= sampleRate * 3) { "参考音频不足 3 秒，请选择 3～30 秒的清晰单人讲话。" }
            require(!requireWhole || samples.size <= sampleRate * 30) { "完整克隆要求音频不超过 30 秒，请先裁剪音频并填写对应的完整逐字稿；本次没有截断音频或套用整篇文案。" }
            val audio = samples.toArray()
            require(audio.any { kotlin.math.abs(it) > 0.001f }) { "音频几乎没有声音，请更换清晰的人声文件。" }
            PcmWav.write(output, audio, sampleRate)
            return RecordingResult(output, audio.size * 1000L / sampleRate)
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}

internal class MonoSamples {
    private var values = FloatArray(24_000)
    var size = 0
        private set

    fun append(buffer: ByteBuffer, channels: Int, encoding: Int, limit: Int) {
        require(channels > 0)
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            else -> error("不支持这种 PCM 格式，请转换为 16 位 WAV 后重试。")
        }
        val count = minOf(buffer.remaining() / (bytesPerSample * channels), limit - size).coerceAtLeast(0)
        if (size + count > values.size) values = values.copyOf(maxOf(size + count, values.size * 2))
        repeat(count) {
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.let { if (it.isFinite()) it else 0f }
                    AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                    else -> {
                        val value = (buffer.get().toInt() and 255) or
                            ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                        value / 8388608f
                    }
                }
            }
            values[size++] = (sum / channels).coerceIn(-1f, 1f)
        }
    }

    fun toArray(): FloatArray = values.copyOf(size)
}
