package com.qwen.tts.android.kokoro

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwen.tts.android.MainViewModel

@Composable
internal fun KokoroScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val spec=viewModel.currentLightSpec
    val paradee=state.studioLightModel=="paradee"
    val size="%.1f".format(java.util.Locale.ROOT,spec.getLong("bytes")/1_000_000.0)
    val importModel=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null)viewModel.importKokoro(uri) }
    var chooseVoice by remember { mutableStateOf(false) }
    var chooseSpeed by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val voice=viewModel.kokoroVoices.firstOrNull { it.id==state.kokoroVoiceId }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(if(paradee)"Paradee · 7.88M · 仅英文 · 下载后离线运行" else "Kokoro · 82M · 下载后离线运行",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("模型与音色",style=MaterialTheme.typography.titleMedium)
            Text(if(paradee)"固定英文女声 af_heart；中文和声音克隆请选择其他模型。英文词典随应用提供。数字逐位朗读；未登录词会提示修改。" else "中英文配音，100 个中文音色和 3 个英文音色。这里使用预置音色；参考音频克隆请选择千问模型。",style=MaterialTheme.typography.bodySmall)
            if(paradee)TextButton(onClick={viewModel.updateText("Hello, welcome to the voice studio. This is a small English voice running on your device.")},enabled=!state.busy){Text("填入英文示例")}
            Text(if(state.kokoroReady) "模型已就绪，可离线生成" else "首次下载约 $size MB",color=MaterialTheme.colorScheme.primary)
            if(!state.kokoroReady){
                Button(onClick=viewModel::downloadKokoro,enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("下载模型 · $size MB")}
            }
            OutlinedButton(onClick={importModel.launch(arrayOf("*/*"))},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text(if(paradee)"导入官方 paradee_int8.onnx" else "导入已下载的 .tar.bz2 模型包")}
            Text("下载支持续传，下载与导入均验证 SHA-256。",style=MaterialTheme.typography.bodySmall)
        } }
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("声音设置",style=MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick={chooseVoice=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) { Text((voice?.name?:"选择音色")+" ▾") }
                DropdownMenu(expanded=chooseVoice,onDismissRequest={chooseVoice=false},modifier=Modifier.heightIn(max=360.dp)) {
                    OutlinedTextField(value=search,onValueChange={search=it},placeholder={Text("搜索名称或编号")},singleLine=true,modifier=Modifier.padding(8.dp))
                    viewModel.kokoroVoices.sortedBy { if(it.language=="zh")it.id else it.id+1000 }.filter { search.isBlank() || it.name.contains(search,true) || it.code.contains(search,true) }.forEach { item ->
                        DropdownMenuItem(text={Text(item.name+" · "+item.code)},onClick={viewModel.selectKokoroVoice(item.id);chooseVoice=false})
                    }
                }
            }
            Box {
                OutlinedButton(onClick={chooseSpeed=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("语速 · ${state.kokoroSpeed}× ▾")}
                DropdownMenu(expanded=chooseSpeed,onDismissRequest={chooseSpeed=false}){
                    listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { speed ->DropdownMenuItem(text={Text("${speed}×")},onClick={viewModel.setKokoroSpeed(speed);chooseSpeed=false}) }
                }
            }
        } }
        if(state.kokoroActive){
            ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(state.status)
                if(state.kokoroProgress>0f && state.kokoroProgress<1f) LinearProgressIndicator(progress={state.kokoroProgress},modifier=Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                OutlinedButton(onClick=viewModel::cancelKokoro){Text("取消当前操作")}
            }}
        }
        state.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(state.kokoroSampleCount>0){
            ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("最近的轻量配音",style=MaterialTheme.typography.titleMedium)
                Text("音频 %.1f 秒 · 生成耗时 %.1f 秒".format(state.kokoroSampleCount/24000.0,state.kokoroMillis/1000.0))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Button(onClick=viewModel::playKokoro,enabled=!state.busy){Text("试听")}
                    OutlinedButton(onClick=viewModel::stopAudio){Text("停止")}
                }
                Text("已保存到历史记录，可在那里导出 WAV 或降噪。",style=MaterialTheme.typography.bodySmall)
            }}
        }
        Spacer(Modifier.height(16.dp))
    }
}
