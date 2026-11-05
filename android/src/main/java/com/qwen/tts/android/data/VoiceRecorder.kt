package com.qwen.tts.android.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class RecorderState(
    val isRecording: Boolean = false,
    val elapsedMillis: Long = 0L,
    val level: Float = 0f,
    val outputPath: String? = null,
)

data class RecordingResult(
    val file: File,
    val durationMillis: Long,
)

class VoiceRecorder {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val recording = AtomicBoolean(false)
    private val sampleRate = 24_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        .coerceAtLeast(sampleRate / 5)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0L
    private var bytes = ByteArrayOutputStream()

    private val _state = MutableStateFlow(RecorderState())
    val state = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean {
        if (recording.get()) return true
        outputFile = file
        bytes = ByteArrayOutputStream()
        file.parentFile?.mkdirs()

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(audioEncoding)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        audioRecord = record
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release()
            return false
        }

        startedAt = System.currentTimeMillis()
        recording.set(true)
        _state.value = RecorderState(isRecording = true, outputPath = file.absolutePath)

        recordingJob = scope.launch {
            val buffer = ShortArray(minBufferSize / 2)
            while (recording.get()) {
                val count = record.read(buffer, 0, buffer.size)
                if (count <= 0) continue
                val accepted = minOf(count, (sampleRate * 30 * 2 - bytes.size()) / 2)
                var sumSquares = 0.0
                for (i in 0 until accepted) {
                    val sample = buffer[i].toInt()
                    bytes.write(sample and 0xff)
                    bytes.write((sample shr 8) and 0xff)
                    sumSquares += sample.toDouble() * sample.toDouble()
                }
                val rms = sqrt(sumSquares / count.toDouble()) / 32768.0
                _state.value = RecorderState(
                    isRecording = true,
                    elapsedMillis = bytes.size() * 1000L / (sampleRate * 2),
                    level = rms.toFloat().coerceIn(0f, 1f),
                    outputPath = file.absolutePath,
                )
                if (bytes.size() >= sampleRate * 30 * 2) break
            }
        }
        return true
    }

    suspend fun stop(): RecordingResult? {
        if (!recording.getAndSet(false)) return null
        val file = outputFile ?: return null
        runCatching { audioRecord?.stop() }
        recordingJob?.join()
        release()

        val duration = bytes.size() * 1000L / (sampleRate * 2)
        file.outputStream().use { output ->
            writeWav(output, bytes.toByteArray(), sampleRate)
        }
        _state.value = RecorderState(isRecording = false, outputPath = file.absolutePath)
        return RecordingResult(file = file, durationMillis = duration)
    }

    fun cancel() {
        val file = outputFile
        if (recording.getAndSet(false)) {
            runCatching { audioRecord?.stop() }
        }
        release()
        runCatching { file?.delete() }
        _state.value = RecorderState()
    }

    private fun release() {
        runCatching { audioRecord?.release() }
        audioRecord = null
        recordingJob = null
    }

    private fun writeWav(output: OutputStream, pcmBytes: ByteArray, sampleRate: Int) {
        output.writeAscii("RIFF")
        output.writeIntLe(36 + pcmBytes.size)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(pcmBytes.size)
        output.write(pcmBytes)
    }
}

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}
