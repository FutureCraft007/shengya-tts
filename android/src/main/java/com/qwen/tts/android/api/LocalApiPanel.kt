package com.qwen.tts.android.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalApiPanel() {
    val context = LocalContext.current
    val state by LocalApiService.state.collectAsState()
    var port by remember { mutableStateOf(context.getSharedPreferences("local-api", 0).getInt("port", 9001).toString()) }
    var reveal by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingPort by remember { mutableStateOf<Int?>(null) }
    var concurrency by remember { mutableStateOf(LocalApiService.concurrency(context)) }
    LaunchedEffect(state.concurrency, state.running) { if (state.running) concurrency = state.concurrency }
    val token = remember { LocalApiService.token(context) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPort?.let { port -> runCatching { LocalApiService.start(context, port) }.onFailure { error = it.message } }
        pendingPort = null
    }
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("连接稿好了 · 本机配音服务", style = MaterialTheme.typography.titleMedium)
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            Text("同一手机使用。先准备模型和音色，再启动服务；稿好了填写地址与连接口令。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, enabled = !state.running, singleLine = true,
                label = { Text("端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("http://127.0.0.1:${if (state.running) state.port else port}")
            Text("本机生成并发上限", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..6).forEach { count ->
                    FilterChip(selected = concurrency == count, onClick = {
                        runCatching { LocalApiService.configure(context, count); concurrency = count; error = null }.onFailure { error = it.message }
                    }, label = { Text("$count") }, modifier = Modifier.testTag("local_api_concurrency_$count"))
                }
            }
            Text("最多 6 路独立推理。高并发会增加内存和发热，可随时调低；正在运行的任务会完成当前运算。", style = MaterialTheme.typography.bodySmall)
            if (state.running) Text("服务上限 ${state.concurrency} 路 · 运行 ${state.activeJobs} · 排队 ${state.queuedJobs}", modifier = Modifier.testTag("local_api_queue"))
            OutlinedTextField(token, {}, readOnly = true, label = { Text("本机连接口令") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "隐藏口令" else "显示口令") }
                TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("声芽本机连接口令", token)) }) { Text("复制口令") }
            }
            Button(onClick = {
                error = null
                if (state.running) context.stopService(Intent(context, LocalApiService::class.java)) else runCatching {
                    val chosen = port.toIntOrNull() ?: error("端口格式不正确")
                    require(chosen in 1024..65535) { "端口应在 1024～65535 之间" }
                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingPort = chosen
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else LocalApiService.start(context, chosen)
                }.onFailure { error = it.message }
            }, modifier = Modifier.fillMaxWidth().testTag("local_api_start")) { Text(if (state.running) "停止本地服务" else "启动本地服务") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
