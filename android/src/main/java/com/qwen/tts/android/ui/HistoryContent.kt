package com.qwen.tts.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qwen.tts.android.data.db.GenerationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Each record keeps both variants; the selected variant is passed explicitly to play/export. */
@Composable
internal fun HistoryContent(
    generations: List<GenerationEntity>,
    busy: Boolean,
    denoisingId: Long?,
    progress: Int,
    elapsedMillis: Long,
    playingId: Long?,
    message: String?,
    onPlay: (Long, Boolean) -> Unit,
    onStop: () -> Unit,
    onDenoise: (Long) -> Unit,
    onCancel: () -> Unit,
    onExport: (GenerationEntity, Boolean) -> Unit,
    onDelete: (GenerationEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("history_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("配音记录", style = MaterialTheme.typography.headlineSmall)
                Text("本机降噪在手机离线处理，原声单独保留。单条最长 5 分钟。", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (generations.isEmpty()) item { Text("还没有配音，生成的音频会保存在这里。") }
        items(generations, key = { it.generationId }) { generation ->
            val id = generation.generationId
            val processing = denoisingId == id
            val playing = playingId == id
            val hasClean = generation.denoisedWavPath != null
            var clean by remember(id, generation.denoisedWavPath) { mutableStateOf(hasClean) }
            var menu by remember(id) { mutableStateOf(false) }
            ElevatedCard {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(generation.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${generation.voiceName} · ${String.format(Locale.CHINA, "%.1f 秒", generation.sampleCount.toDouble() / generation.sampleRate.coerceAtLeast(1))} · ${SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(generation.createdAt))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (hasClean) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !clean, enabled = !processing,
                                onClick = { if (playing) onStop(); clean = false }, label = { Text("原声") },
                                modifier = Modifier.testTag("history_original_$id"))
                            FilterChip(selected = clean, enabled = !processing,
                                onClick = { if (playing) onStop(); clean = true }, label = { Text("降噪版") },
                                modifier = Modifier.testTag("history_clean_$id"))
                        }
                    }
                    if (processing) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("正在本机降噪 $progress% · 已用 ${elapsedMillis / 1000} 秒", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("history_cancel_$id")) { Text("取消降噪") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (playing) onStop() else onPlay(id, clean) }, enabled = !processing,
                            modifier = Modifier.testTag("history_play_$id"),
                        ) { Text(if (playing) "停止" else "播放") }
                        if (!hasClean && !processing) OutlinedButton(
                            onClick = { onDenoise(id) }, enabled = !busy,
                            modifier = Modifier.testTag("history_denoise_$id"),
                        ) { Text("本机降噪") }
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("history_more_$id")) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(if (clean) "导出降噪版" else "导出原声") },
                                    enabled = !processing, onClick = { menu = false; onExport(generation, clean) })
                                DropdownMenuItem(text = { Text("删除记录及音频") }, enabled = !busy,
                                    onClick = { menu = false; onDelete(generation) })
                            }
                        }
                    }
                }
            }
        }
    }
}
