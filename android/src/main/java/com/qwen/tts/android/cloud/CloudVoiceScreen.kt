package com.qwen.tts.android.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun CloudVoiceScreen(viewModel: CloudViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    CloudVoiceContent(state, viewModel::configure, viewModel::clearKey, viewModel::prepare, viewModel::play)
}

@Composable
internal fun CloudVoiceContent(
    state: CloudUiState,
    onConfigure: (CloudConfig, String) -> Boolean,
    onClearKey: () -> Unit,
    onPrepare: (String) -> Unit,
    onPlay: () -> Unit,
) {
    var endpoint by remember { mutableStateOf(state.config.endpoint) }
    var model by remember { mutableStateOf(state.config.model) }
    var voice by remember { mutableStateOf(state.config.voice) }
    var key by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val draft = CloudConfig(endpoint.trim(), model.trim(), voice.trim())
    val saved = draft == state.config && key.isBlank()
    val enabled = !state.busy
    LazyColumn(Modifier.fillMaxSize().testTag("cloud_page"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("云端语音合成", style = MaterialTheme.typography.headlineSmall)
            Text("填写兼容 /audio/speech 的完整 HTTPS 接口。服务需使用 Bearer API Key 并直接返回 WAV。")
        }
        item {
            OutlinedTextField(endpoint, { endpoint = it; key = "" }, label = { Text("HTTPS 接口地址") },
                placeholder = { Text("https://example.com/v1/audio/speech") }, enabled = enabled,
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cloud_endpoint"))
        }
        item {
            OutlinedTextField(model, { model = it }, label = { Text("模型") }, enabled = enabled,
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(voice, { voice = it }, label = { Text("音色 ID") }, enabled = enabled,
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(key, { key = it }, label = { Text("API Key") }, enabled = enabled,
                supportingText = {
                    Text(if (state.hasKey && draft.endpoint == state.config.endpoint) "当前地址已有 Key，留空保留。"
                        else "Key 在本机加密保存，仅用于对应地址。")
                }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("cloud_api_key"))
        }
        item {
            Button(onClick = { if (onConfigure(draft, key)) key = "" }, enabled = enabled) { Text("保存配置") }
            if (state.hasKey && draft.endpoint == state.config.endpoint) {
                TextButton(onClick = onClearKey, enabled = enabled) { Text("删除当前 Key") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            OutlinedTextField(text, { text = it.take(2000) }, label = { Text("配音文案") },
                enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
        item {
            Button(onClick = { onPrepare(text) },
                enabled = enabled && saved && state.hasKey && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("生成音频（可能计费）") }
            if (state.sample != null && saved) {
                OutlinedButton(onClick = onPlay, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.playing) "停止试听" else "播放音频")
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.message.isNotBlank()) Text(state.message)
        }
    }
}
