package com.qwen.tts.android.cloud

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudUiState(
    val config: CloudConfig = CloudConfig(),
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val error: String? = null,
    val sample: File? = null,
    val playing: Boolean = false,
)

class CloudViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CloudRepository(application)
    private val preferences = application.getSharedPreferences("https-tts-config", 0)
    private val initial = CloudConfig(
        preferences.getString("endpoint", "").orEmpty(),
        preferences.getString("model", "").orEmpty(),
        preferences.getString("voice", "").orEmpty(),
    )
    private val _state = MutableStateFlow(CloudUiState(config = initial, hasKey = repository.hasKey(initial.endpoint)))
    val state = _state.asStateFlow()
    private var player: MediaPlayer? = null

    fun configure(config: CloudConfig, key: String): Boolean {
        if (_state.value.busy) return false
        stop()
        return runCatching {
            config.url()
            require(config.model.isNotBlank() && config.voice.isNotBlank()) { "请填写模型和音色 ID。" }
            if (key.isNotBlank()) repository.saveKey(config.endpoint, key)
            preferences.edit().putString("endpoint", config.endpoint).putString("model", config.model)
                .putString("voice", config.voice).apply()
        }.onSuccess {
            _state.value = CloudUiState(config = config, hasKey = repository.hasKey(config.endpoint), message = "云端配置已保存。")
        }.onFailure { error ->
            _state.update { it.copy(error = error.message ?: "无法保存云端配置。") }
        }.isSuccess
    }

    fun clearKey() {
        if (_state.value.busy) return
        runCatching { repository.clearKey(_state.value.config.endpoint) }
            .onSuccess { _state.update { it.copy(hasKey = false, message = "当前地址的 API Key 已删除。") } }
            .onFailure { _state.update { it.copy(error = "凭据删除失败。") } }
    }

    fun prepare(text: String) {
        val snapshot = _state.value
        if (snapshot.busy) return
        stop()
        _state.update { it.copy(busy = true, error = null, sample = null, message = "正在生成音频…") }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { repository.sample(snapshot.config, text.trim()) }
                _state.update { it.copy(busy = false, sample = file, message = "已生成，可以试听。") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = if (error is IOException) "云端连接失败，请检查地址、证书和网络。"
                    else error.message ?: "云端生成失败。"
                _state.update { it.copy(busy = false, message = "", error = message) }
            }
        }
    }

    fun play() {
        if (_state.value.playing) { stop(); return }
        val file = _state.value.sample ?: return
        runCatching {
            val current = MediaPlayer()
            player = current
            current.setDataSource(file.absolutePath)
            current.setOnCompletionListener { stop() }
            current.setOnErrorListener { _, _, _ -> stop(); _state.update { it.copy(error = "音频播放失败。") }; true }
            current.prepare()
            current.start()
            _state.update { it.copy(playing = true) }
        }.onFailure { stop(); _state.update { it.copy(error = "无法播放音频。") } }
    }

    private fun stop() {
        runCatching { player?.release() }
        player = null
        _state.update { it.copy(playing = false) }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
