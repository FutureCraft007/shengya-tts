package com.qwen.tts.android

import android.Manifest
import com.qwen.tts.android.kokoro.KokoroModels
import com.qwen.tts.android.kokoro.KokoroScreen
import com.qwen.tts.android.kokoro.ParadeeAdapter
import com.qwen.tts.android.kokoro.kokoroCallback
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qwen.tts.android.data.VoiceRecorder
import com.qwen.tts.android.data.AudioImporter
import com.qwen.tts.android.data.ModelStorage
import com.qwen.tts.android.data.EngineHost
import com.qwen.tts.android.api.LocalApiPanel
import com.qwen.tts.android.data.TextSegments
import com.qwen.tts.android.cloud.CloudVoiceScreen
import com.qwen.tts.android.audio.LocalSpeechDenoiser
import com.qwen.tts.android.ui.HistoryContent
import com.qwen.tts.android.ui.navigateToAppTab
import com.qwen.tts.android.data.db.GenerationEntity
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.VoiceProfileEntity
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ModelFile(
    val name: String,
    val url: String?,
    val sizeBytes: Long,
    val sha256: String,
)

private data class ModelVariant(
    val id: String,
    val label: String,
    val displayName: String,
    val files: List<ModelFile>,
    val talkerName: String,
) {
    val totalBytes: Long = files.sumOf { it.sizeBytes }
    val customVoice: Boolean get() = id == "custom_q4_k_m"
}

private data class LanguageOption(val label: String, val id: Int)
private data class BackendOption(val id: String, val label: String, val preference: Int)
private data class GenerationTimeEstimate(val totalMillis: Long, val sampleSize: Int)

private val languageOptions = listOf(
    LanguageOption("英语", 2050),
    LanguageOption("德语", 2053),
    LanguageOption("西班牙语", 2054),
    LanguageOption("法语", 2061),
    LanguageOption("中文", 2055),
    LanguageOption("日语", 2058),
    LanguageOption("韩语", 2064),
    LanguageOption("俄语", 2069),
)

private val backendOptions = listOf(
    BackendOption("cpu", "CPU", QwenEngine.BACKEND_CPU),
)

private val defaultBackendOption = backendOptions.first { it.id == "cpu" }

private object QwenModel {
    private const val repo = "Serveurperso/Qwen3-TTS-GGUF"
    private const val baseUrl = "https://huggingface.co/$repo/resolve/main"

    val defaultVariant: ModelVariant = variant(
        id = "q4_k_m",
        label = "Q4_K_M",
        displayName = "0.6B Base · 音频克隆",
        talkerName = "qwen-talker-0.6b-base-Q4_K_M.gguf",
        tokenizerName = "qwen-tokenizer-12hz-Q4_K_M.gguf",
        talkerSizeBytes = 628_905_056L,
        tokenizerSizeBytes = 254_974_752L,
    )

    val customVariant: ModelVariant = variant(
        id = "custom_q4_k_m",
        label = "CustomVoice",
        displayName = "0.6B CustomVoice · 预置音色",
        talkerName = "qwen-talker-0.6b-customvoice-Q4_K_M.gguf",
        tokenizerName = "qwen-tokenizer-12hz-Q4_K_M.gguf",
        talkerSizeBytes = 604_878_080L,
        tokenizerSizeBytes = 254_974_752L,
    )

    val variants = listOf(defaultVariant, customVariant)

    val obsoleteFileNames = setOf(
        "qwen-talker-0.6b-base-Q8_0.gguf",
        "qwen-talker-0.6b-base-NVFP4.gguf",
        "qwen-tokenizer-12hz-Q8_0.gguf",
    )

    // Q8_0 and NVFP4 used to be available here. The Android app now intentionally
    // ships one supported path: Q4_K_M.

    fun variantById(id: String): ModelVariant =
        variants.firstOrNull { it.id == id } ?: defaultVariant

    private fun variant(
        id: String,
        label: String,
        displayName: String,
        talkerName: String,
        tokenizerName: String,
        talkerSizeBytes: Long,
        tokenizerSizeBytes: Long,
        talkerUrl: String? = "$baseUrl/$talkerName?download=true",
        tokenizerUrl: String? = "$baseUrl/$tokenizerName?download=true",
    ): ModelVariant =
        ModelVariant(
            id = id,
            label = label,
            displayName = displayName,
            talkerName = talkerName,
            files = listOf(
                ModelFile(tokenizerName, tokenizerUrl, tokenizerSizeBytes,
                    "cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039"),
                ModelFile(talkerName, talkerUrl, talkerSizeBytes,
                    if (id == "custom_q4_k_m") "b3a7e6613d80f8a703c06267fc1e94d48ce91932ab82ab6e31c50f4ca4868e1e"
                    else "4b468ec7b1f62b90ef4ca316c0aa57deadfd54b2cf9651703ea753cedaf04226"),
            ),
        )
}

data class QwenTtsUiState(
    val text: String = "你好，欢迎使用声芽配音。导入一段清晰的人声，就能创建自己的离线音色。",
    val selectedModelId: String = QwenModel.defaultVariant.id,
    val selectedBackendId: String = defaultBackendOption.id,
    val selectedVoiceId: String? = null,
    val activeBackendName: String? = null,
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = QwenModel.defaultVariant.totalBytes,
    val status: String = "模型尚未下载",
    val error: String? = null,
    val sampleRate: Int = 0,
    val sampleCount: Int = 0,
    val synthesisMillis: Long = 0,
    val playing: Boolean = false,
    val playingGenerationId: Long? = null,
    val denoisingGenerationId: Long? = null,
    val denoiseProgress: Int = 0,
    val operationElapsedMillis: Long = 0,
    val synthesisFrames: Int = 0,
    val maxAudioTokens: Int = 512,
    val cpuThreads: Int = 0,
    val selectedCpuThreads: Int = 0,
    val selectedLanguageId: Int = 2055,
    val tokenizeMillis: Long = 0,
    val encodeMillis: Long = 0,
    val generateMillis: Long = 0,
    val decodeMillis: Long = 0,
    val decodeFrames: Int = 0,
    val decodeSamples: Long = 0,
    val decodeGraphComputeMillis: Long = 0,
    val savedFileName: String? = null,
    val snackbarMessage: String? = null,
    val estimatedSynthesisMillis: Long? = null,
    val estimateSampleCount: Int = 0,
    val supportsCloning: Boolean? = null,
    val speakerEmbeddingDim: Int = 0,
    val namedSpeakers: List<String> = emptyList(),
    val selectedSpeaker: String? = null,
    val downloadMirror: Boolean = false,
    val kokoroReady: Boolean = false,
    val studioKokoro: Boolean = false,
    val studioLightModel: String = "kokoro",
    val kokoroPresetId: Int = 3,
    val kokoroVoiceId: Int = 3,
    val kokoroSpeed: Float = 1f,
    val kokoroActive: Boolean = false,
    val kokoroProgress: Float = 0f,
    val kokoroSampleCount: Int = 0,
    val kokoroMillis: Long = 0L,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val kokoroModels = KokoroModels(application)
    private val paradeeModels = KokoroModels(application,"paradee")
    private fun lightModels() = if(_uiState.value.studioLightModel=="paradee")paradeeModels else kokoroModels
    internal val currentLightSpec get() = lightModels().spec
    internal val kokoroVoices get() = lightModels().voices
    private val kokoroCancel = AtomicBoolean(false)
    private var kokoroAudio: FloatArray? = null
    private val preferences = application.getSharedPreferences("preferences", 0)
    private val modelDir = File(application.filesDir, "qwen3-tts-models")
    private val voiceDir = File(application.filesDir, "voices")
    private val generationDir = File(application.filesDir, "generations")
    private val dao = QwenDatabase.getDatabase(application).qwenDao()
    private val recorder = VoiceRecorder()

    val voices = dao.observeVoices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generations = dao.observeGenerations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recorderState = recorder.state

    private val _uiState = MutableStateFlow(
        QwenTtsUiState(
            kokoroReady = kokoroModels.ready(),
            modelReady = isModelReady(QwenModel.defaultVariant),
            downloadTotalBytes = QwenModel.defaultVariant.totalBytes,
        ),
    )
    val uiState = combine(_uiState, EngineHost.apiBusy) { state, external ->
        state.copy(busy = state.busy || external, loaded = state.loaded && EngineHost.engine != null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)

    private var engine: QwenEngine?
        get() = EngineHost.engine
        set(value) { EngineHost.engine = value }
    private val nativeLock = EngineHost.lock
    @Volatile private var cleared = false
    private var generatedAudio: FloatArray? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activeTrack: AudioTrack? = null
    private var denoiseJob: Job? = null
    private var denoiseCancelled = AtomicBoolean(false)

    init {
        viewModelScope.launch { _uiState.collect { EngineHost.uiBusy.value = it.busy || recorderState.value.isRecording } }
        val threads = preferredCpuThreadCount()
        _uiState.update { it.copy(
            selectedCpuThreads = threads, cpuThreads = threads,
            studioKokoro = preferences.getBoolean("studioKokoro",false),
            studioLightModel = preferences.getString("studioLightModel","kokoro").takeIf {it in listOf("kokoro","paradee")}?:"kokoro",
            kokoroReady = if(preferences.getString("studioLightModel","kokoro")=="paradee")paradeeModels.ready() else kokoroModels.ready(),
            kokoroVoiceId = if(preferences.getString("studioLightModel","kokoro")=="paradee")0 else 3,
            selectedModelId = QwenModel.variantById(preferences.getString("modelId", null).orEmpty()).id,
            downloadMirror = preferences.getBoolean("downloadMirror", false),
            selectedVoiceId = preferences.getString("voiceId", null),
            selectedSpeaker = preferences.getString("speaker", null),
        ) }
        refreshModelState()
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    fun selectStudioModel(modelId: String) {
        if (isLocalBusy() || recorderState.value.isRecording) return
        stopAudio()
        if (modelId in listOf("kokoro","paradee")) {
            val models=if(modelId=="paradee")paradeeModels else kokoroModels
            preferences.edit().putBoolean("studioKokoro",true).putString("studioLightModel",modelId).apply()
            _uiState.update { it.copy(studioKokoro=true,studioLightModel=modelId,kokoroReady=models.ready(),kokoroVoiceId=if(modelId=="paradee")0 else it.kokoroPresetId,error=null) }
        } else {
            selectModel(modelId)
            preferences.edit().putBoolean("studioKokoro",false).apply()
            _uiState.update { it.copy(studioKokoro=false,error=null) }
        }
    }
    fun selectKokoroVoice(id: Int) {
        if (!isLocalBusy() && kokoroVoices.any { it.id==id }) _uiState.update { it.copy(kokoroVoiceId=id,kokoroPresetId=if(it.studioLightModel=="kokoro")id else it.kokoroPresetId) }
    }
    fun setKokoroSpeed(speed: Float) { if(!isLocalBusy() && speed in .5f..2f) _uiState.update { it.copy(kokoroSpeed=speed) } }
    fun cancelKokoro() { if(_uiState.value.kokoroActive){kokoroCancel.set(true);_uiState.update { it.copy(status="正在取消，当前下载连接或短句结束后停止…") }} }
    fun downloadKokoro() = prepareKokoro(null)
    fun importKokoro(uri: Uri) = prepareKokoro(uri)
    private fun prepareKokoro(uri: Uri?) {
        if (isLocalBusy() || recorderState.value.isRecording) return
        val models=lightModels();val total=models.spec.getLong("bytes");val title=models.spec.getString("title")
        kokoroCancel.set(false);startBusy(status=if(uri==null) "下载$title" else "校验并导入$title")
        _uiState.update { it.copy(kokoroActive=true,kokoroProgress=0f) }
        viewModelScope.launch {
            val result=runCatching { withContext(Dispatchers.IO) {
                if(uri==null) models.download(kokoroCancel) { bytes -> _uiState.update { it.copy(kokoroProgress=bytes/total.toFloat(),status=if(bytes==total) "校验并解压模型，请稍候…" else "下载模型 · ${bytes/100_000/10f} / ${total/100_000/10f} MB") } }
                else models.import(getApplication<Application>().contentResolver.openInputStream(uri) ?: error("无法读取模型包"),kokoroCancel)
            } }
            stopBusyTicker();_uiState.update { it.copy(busy=false,kokoroActive=false,kokoroReady=models.ready(),
                status=if(result.isSuccess) "$title 已就绪" else "轻量模型准备失败",
                error=result.exceptionOrNull()?.message) }
        }
    }
    fun synthesizeKokoro() {
        val state=_uiState.value;val text=state.text.trim();val models=lightModels()
        if(isLocalBusy() || recorderState.value.isRecording) return
        if(text.isEmpty() || text.length>2000){_uiState.update { it.copy(error="轻量配音请控制在 1～2000 字。") };return}
        if(!models.ready()){_uiState.update { it.copy(error="请先下载或导入当前模型") };return}
        kokoroCancel.set(false);startBusy(status="正在加载轻量模型",resetSynthesis=true);_uiState.update { it.copy(kokoroActive=true) }
        viewModelScope.launch {
            val began=SystemClock.elapsedRealtime()
            val result=runCatching { withContext(Dispatchers.IO) { synchronized(nativeLock) {
                check(!cleared){"配音页面已关闭"};EngineHost.close();_uiState.update { it.copy(loaded=false) }
                val prepared=if(state.studioLightModel=="paradee")ParadeeAdapter.prepareText(text,File(models.directory,"lexicon.txt")) else text
                val tts=models.createTts(state.selectedCpuThreads)
                try {
                    val chunks=TextSegments.split(prepared,80);val audio=mutableListOf<FloatArray>()
                    chunks.forEachIndexed { index,chunk ->
                        check(!kokoroCancel.get()){ "操作已取消" };_uiState.update { it.copy(status="轻量配音 · ${index+1}/${chunks.size}") }
                        val generated=tts.generateWithCallback(chunk,state.kokoroVoiceId,state.kokoroSpeed,kokoroCallback(kokoroCancel))
                        check(!kokoroCancel.get()){ "操作已取消" };check(generated.sampleRate==24000 && generated.samples.isNotEmpty()){ "轻量模型没有生成有效音频" };audio+=generated.samples
                    }
                    FloatArray(audio.sumOf { it.size }).also { all -> var offset=0;audio.forEach { it.copyInto(all,offset);offset+=it.size } }
                } finally { tts.release() }
            } } }
            stopBusyTicker()
            result.fold(onSuccess={ audio ->
                val elapsed=SystemClock.elapsedRealtime()-began
                val saved=runCatching { persistGeneratedAudio(text,audio,24000,(if(state.studioLightModel=="paradee")"Paradee · " else "Kokoro · ")+models.voices.first { it.id==state.kokoroVoiceId }.name,null,elapsed) }
                if(saved.isSuccess){kokoroAudio=audio;generatedAudio=audio}
                _uiState.update { it.copy(busy=false,kokoroActive=false,loaded=false,status=if(saved.isSuccess) "轻量配音已生成并保存到历史" else "保存音频失败",error=saved.exceptionOrNull()?.message,
                    kokoroSampleCount=if(saved.isSuccess)audio.size else it.kokoroSampleCount,kokoroMillis=elapsed,sampleRate=24000,sampleCount=if(saved.isSuccess)audio.size else 0,synthesisMillis=elapsed) }
            },onFailure={ error -> _uiState.update { it.copy(busy=false,kokoroActive=false,loaded=false,status="轻量配音已停止",error=error.message) } })
        }
    }
    fun playKokoro() {
        if(isLocalBusy())return
        val audio=kokoroAudio?:return
        stopAudio();generatedAudio=audio;_uiState.update { it.copy(sampleRate=24000,sampleCount=audio.size) };playAudio()
    }

    fun updateCpuThreads(value: Int) {
        if (isLocalBusy()) return
        _uiState.update { it.copy(selectedCpuThreads = value, cpuThreads = value) }
        EngineHost.control { engine?.setCpuThreads(value) }
        preferences.edit().putInt("cpuThreads", value).apply()
    }

    fun updateLanguage(languageId: Int) {
        if (isLocalBusy()) return
        _uiState.update { it.copy(selectedLanguageId = languageId) }
    }

    fun selectVoice(voiceId: String?) {
        if (isLocalBusy() || recorderState.value.isRecording) return
        _uiState.update { it.copy(selectedVoiceId = voiceId) }
        preferences.edit().putString("voiceId", voiceId).apply()
    }

    fun selectSpeaker(speaker: String) {
        if (isLocalBusy() || speaker !in _uiState.value.namedSpeakers) return
        _uiState.update { it.copy(selectedSpeaker = speaker) }
        preferences.edit().putString("speaker", speaker).apply()
    }

    fun setDownloadMirror(mirror: Boolean) {
        if (isLocalBusy()) return
        _uiState.update { it.copy(downloadMirror = mirror) }
        preferences.edit().putBoolean("downloadMirror", mirror).apply()
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty() || isLocalBusy() || recorderState.value.isRecording) return
        stopAudio()
        if (!EngineHost.control { EngineHost.close() }) return
        _uiState.update { it.copy(loaded = false, namedSpeakers = emptyList()) }
        viewModelScope.launch {
            startBusy("正在导入并校验本地模型")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val accepted = QwenModel.variants.flatMap { it.files }.distinctBy { it.name }
                    uris.forEach { uri ->
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        }
                        val spec = accepted.firstOrNull { it.name == name }
                            ?: error("文件名不匹配，请导入设置页列出的 Q4_K_M GGUF 文件。")
                        _uiState.update { it.copy(status = "正在导入并校验：${spec.name}") }
                        resolver.openInputStream(uri)?.use { input ->
                            ModelStorage.import(input, File(modelDir, spec.name), spec.sizeBytes, spec.sha256)
                        } ?: error("无法读取所选模型文件。")
                    }
                }
            }
            stopBusyTicker()
            refreshModelState()
            result.onSuccess { _uiState.update { it.copy(status = "模型导入完成", error = null) } }
                .onFailure { e -> _uiState.update { it.copy(status = "模型导入失败", error = e.message) } }
        }
    }

    fun selectModel(modelId: String) {
        if (isLocalBusy() || recorderState.value.isRecording) return
        if (_uiState.value.selectedModelId == modelId) {
            preferences.edit().putBoolean("studioKokoro",false).apply()
            _uiState.update { it.copy(studioKokoro=false) }
            return
        }
        val variant = QwenModel.variantById(modelId)
        preferences.edit().putBoolean("studioKokoro",false).apply()
        stopAudio()
        if (!EngineHost.control { EngineHost.close() }) return
        generatedAudio = null
        val ready = isModelReady(variant)
        preferences.edit().putString("modelId", variant.id).apply()
        _uiState.update {
            it.copy(
                selectedModelId = variant.id,
                studioKokoro = false,
                activeBackendName = null,
                modelReady = ready,
                loaded = false,
                status = if (ready) "模型文件已就绪" else "模型尚未下载",
                error = null,
                downloadProgress = 0f,
                downloadBytes = 0L,
                downloadTotalBytes = variant.totalBytes,
                sampleRate = 0,
                sampleCount = 0,
                synthesisMillis = 0,
                synthesisFrames = 0,
                tokenizeMillis = 0,
                encodeMillis = 0,
                generateMillis = 0,
                decodeMillis = 0,
                decodeFrames = 0,
                decodeSamples = 0,
                decodeGraphComputeMillis = 0,
                savedFileName = null,
                estimatedSynthesisMillis = null,
                estimateSampleCount = 0,
                supportsCloning = null,
                speakerEmbeddingDim = 0,
                namedSpeakers = emptyList(),
            )
        }
    }

    fun downloadModel() {
        if (isLocalBusy() || recorderState.value.isRecording) return
        viewModelScope.launch {
            val variant = selectedVariant()
            startBusy(status = "准备下载模型，正在连接服务器…", downloading = true)
            _uiState.update {
                it.copy(downloadProgress = 0f, downloadBytes = 0L, downloadTotalBytes = variant.totalBytes)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    downloadFiles(variant)
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    refreshModelState("模型文件已就绪")
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            downloading = false,
                            status = "模型下载失败，请检查网络、切换下载源或导入本地文件。",
                            error = throwable.message ?: "模型下载失败，请检查网络、切换下载源或导入本地文件。",
                        )
                    }
                },
            )
        }
    }

    fun loadModel() {
        if (!_uiState.value.modelReady || _uiState.value.loaded || isLocalBusy() || recorderState.value.isRecording) return
        viewModelScope.launch {
            startBusy(status = "正在加载本地模型")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(selectedVariant())
                    native.getActiveBackendName().orEmpty() to native.getCpuThreads()
                }
            }

            result.fold(
                onSuccess = { (backend, threads) ->
                    stopBusyTicker()
                    val caps = engine?.getModelCapabilities()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = if (backend.isBlank()) "模型已加载" else "模型已加载，运行设备：$backend",
                            activeBackendName = backend.ifBlank { null },
                            cpuThreads = threads,
                            supportsCloning = caps?.supportsCloning,
                            speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = false,
                            status = "模型加载失败",
                            error = throwable.message ?: "模型加载失败",
                        )
                    }
                },
            )
        }
    }

    fun synthesize() {
        val text = _uiState.value.text.trim()
        if (text.isEmpty() || isLocalBusy() || recorderState.value.isRecording) return
        if (text.length > 2_000) {
            _uiState.update { it.copy(error = "单次配音请控制在 2000 字以内，可分批生成。") }
            return
        }
        startBusy(status = "正在合成语音", resetSynthesis = true, synthesisEstimate = estimateSynthesisMillis(text))
        viewModelScope.launch {
            val variant = selectedVariant()
            val selectedVoice = if (variant.customVoice) null else _uiState.value.selectedVoiceId?.let { dao.getVoice(it) }
            val languageId = _uiState.value.selectedLanguageId

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val native = ensureEngineLoaded(variant)
                    native.setProgressCallback { frames, maxTokens ->
                        _uiState.update {
                            it.copy(
                                loaded = true,
                                status = "正在生成语音",
                                synthesisFrames = frames,
                                maxAudioTokens = maxTokens,
                            )
                        }
                    }
                    try {
                        val parts = TextSegments.split(text)
                        val chunks = mutableListOf<FloatArray>()
                        var rate = 0
                        var elapsed = 0L
                        parts.forEachIndexed { index, part ->
                            _uiState.update { it.copy(status = "正在生成第 ${index + 1}/${parts.size} 段") }
                            val chunk = synchronized(nativeLock) {
                            check(!cleared) { "配音页面已关闭。" }
                            val nativeParams = QwenEngine.NativeParams(
                                languageId = languageId,
                                speaker = if (variant.customVoice) _uiState.value.selectedSpeaker else null,
                                maxAudioTokens = 1024,
                            )
                            if (selectedVoice?.iclPromptPath != null) native.synthesizeWithIclPrompt(part, selectedVoice.iclPromptPath, nativeParams)
                            else native.synthesize(
                                text = part,
                                speakerEmbeddingPath = selectedVoice?.speakerEmbeddingPath,
                                params = QwenEngine.NativeParams(
                                    languageId = languageId,
                                    speaker = if (variant.customVoice) _uiState.value.selectedSpeaker else null,
                                    maxAudioTokens = 1024,
                                ),
                            )
                            }
                            check(chunk.success && chunk.audio?.isNotEmpty() == true) { "本地合成失败：${chunk.errorMsg.orEmpty()}" }
                            if (rate == 0) rate = chunk.sampleRate
                            check(rate == chunk.sampleRate && rate > 0) { "生成音频的采样率不一致。" }
                            chunks += chunk.audio
                            elapsed += chunk.timeMs
                        }
                        val samples = FloatArray(chunks.sumOf { it.size })
                        var offset = 0
                        chunks.forEach { chunk -> chunk.copyInto(samples, offset); offset += chunk.size }
                        QwenEngine.NativeResult(samples, rate, true, null, elapsed)
                    } finally {
                        _uiState.update {
                            it.copy(activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() })
                        }
                        native.setProgressCallback(null)
                    }
                }
            }

            result.fold(
                onSuccess = { nativeResult ->
                    stopBusyTicker()
                    if (!nativeResult.success || nativeResult.audio == null) {
                        _uiState.update {
                            it.copy(
                                busy = false,
                                loaded = true,
                                status = "语音合成失败",
                                error = nativeResult.errorMsg ?: "本地引擎合成失败",
                            )
                        }
                        return@fold
                    }

                    generatedAudio = nativeResult.audio
                    val sampleRate = nativeResult.sampleRate.takeIf { it > 0 } ?: 24_000
                    val voiceName = if (variant.customVoice) speakerLabel(_uiState.value.selectedSpeaker.orEmpty()) else selectedVoice?.name ?: "默认音色"
                    val saved = runCatching { persistGeneratedAudio(text, nativeResult.audio, sampleRate, voiceName, selectedVoice?.voiceId, nativeResult.timeMs) }
                    if (saved.isFailure) {
                        _uiState.update { it.copy(busy = false, status = "音频保存失败", error = "请检查手机存储空间：${saved.exceptionOrNull()?.message}") }
                        return@fold
                    }
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = "音频已生成",
                            sampleRate = sampleRate,
                            sampleCount = nativeResult.audio.size,
                            synthesisMillis = nativeResult.timeMs,
                            tokenizeMillis = nativeResult.tokenizeMs,
                            encodeMillis = nativeResult.encodeMs,
                            generateMillis = nativeResult.generateMs,
                            decodeMillis = nativeResult.decodeMs,
                            decodeFrames = nativeResult.decodeFrames,
                            decodeSamples = nativeResult.decodeSamples,
                            decodeGraphComputeMillis = nativeResult.decodeGraphComputeMs,
                            estimatedSynthesisMillis = null,
                            estimateSampleCount = 0,
                            error = null,
                        )
                    }
                    playAudio()
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "语音合成失败",
                            error = throwable.message ?: "语音合成失败",
                        )
                    }
                },
            )
        }
    }

    fun startVoiceRecording() {
        if (isLocalBusy() || recorderState.value.isRecording) return
        if (selectedVariant().customVoice) {
            _uiState.update { it.copy(error = "请先在设置中选择 Base 模型，再创建克隆音色。") }
            return
        }
        if (!isModelReady(QwenModel.defaultVariant)) {
            _uiState.update { it.copy(error = "请先到设置页下载或导入 Base 模型，再录音创建音色。") }
            return
        }
        val file = File(voiceDir, "pending-${System.currentTimeMillis()}.wav")
        if (!runCatching { recorder.start(file) }.getOrDefault(false)) {
            _uiState.update { it.copy(error = "无法开始录音，请检查麦克风权限。") }
        }
    }

    fun stopVoiceRecordingAndCreate(name: String, transcript: String = "") {
        if (isLocalBusy()) return
        startBusy("正在保存录音")
        viewModelScope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { recorder.stop() } }
            stopBusyTicker()
            _uiState.update { it.copy(busy = false) }
            val result = saved.getOrNull()
            if (result == null || result.durationMillis < 3_000) {
                result?.file?.delete()
                _uiState.update { it.copy(error = "录音不足 3 秒或保存失败，请重新录制 3～30 秒的清晰人声。") }
            } else {
                createVoice(name, transcript) { result }
            }
        }
    }

    fun importVoice(uri: Uri, name: String, transcript: String = "") {
        if (isLocalBusy() || recorderState.value.isRecording) return
        if (selectedVariant().customVoice) {
            _uiState.update { it.copy(error = "请先在设置中选择 Base 模型，再导入音频克隆。") }
            return
        }
        createVoice(name, transcript) {
            val pending = File(voiceDir, "pending-${UUID.randomUUID()}.wav")
            try {
                AudioImporter(getApplication()).import(uri, pending, requireWhole = transcript.isNotBlank())
            } catch (e: Exception) {
                pending.delete()
                throw e
            }
        }
    }

    private fun createVoice(name: String, transcript: String = "", source: () -> com.qwen.tts.android.data.RecordingResult) {
        if (isLocalBusy()) return
        require(transcript.length <= 2000) { "逐字稿最多 2000 字" }
        val trimmedName = name.trim().ifBlank { "音色 ${voices.value.size + 1}" }
        viewModelScope.launch {
            startBusy(status = "正在处理音频并提取音色")
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    check(isModelReady(QwenModel.defaultVariant)) { "请先在设置中下载或导入 Base 模型。" }
                    val native = ensureEngineLoaded(selectedVariant())
                    val caps = native.getModelCapabilities()
                    if (caps?.supportsCloning == false) {
                        error("当前模型不支持音频克隆，请切换为 Base 模型。")
                    }

                    val voiceId = "voice-${UUID.randomUUID()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    var pending: File? = null
                    try {
                    val result = source()
                    pending = result.file
                    val reference = File(targetDir, "reference.wav")
                    result.file.copyTo(reference, overwrite = true)

                    val embedding = File(targetDir, "speaker.json")
                    val extracted = synchronized(nativeLock) {
                        check(!cleared) { "音色页面已关闭。" }
                        native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)
                    }
                    if (!extracted) {
                        error(native.getLastError() ?: "音色提取失败，请检查参考音频和模型文件。")
                    }

                    val prompt = if (transcript.isNotBlank()) File(targetDir, "icl-prompt.bin").also { file ->
                        synchronized(nativeLock) {
                            check(native.extractIclPrompt(reference.absolutePath, transcript.trim(), file.absolutePath)) {
                                native.getLastError() ?: "完整克隆提示提取失败，请核对音频与逐字稿"
                            }
                        }
                    } else null

                    val voice = VoiceProfileEntity(
                        voiceId = voiceId,
                        name = makeUniqueVoiceName(trimmedName),
                        referenceWavPath = reference.absolutePath,
                        speakerEmbeddingPath = embedding.absolutePath,
                        durationMillis = result.durationMillis,
                        referenceText = transcript.trim().takeIf { it.isNotBlank() },
                        iclPromptPath = prompt?.absolutePath,
                    )
                    dao.insertVoice(voice)
                    voice
                    } catch (e: Exception) {
                        targetDir.deleteRecursively()
                        throw e
                    } finally {
                        pending?.delete()
                    }
                }
            }

            created.fold(
                onSuccess = { voice ->
                    preferences.edit().putString("voiceId", voice.voiceId).apply()
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            selectedVoiceId = voice.voiceId,
                            status = "音色已创建并选用",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "音色创建失败",
                            error = throwable.message ?: "音色创建失败",
                        )
                    }
                },
            )
        }
    }

    fun cancelVoiceRecording() {
        recorder.cancel()
    }

    fun renameVoice(voice: VoiceProfileEntity, name: String) {
        if (isLocalBusy()) return
        val value = name.trim().take(60)
        if (value.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { dao.renameVoice(voice.voiceId, value) }
    }

    fun playReference(voice: VoiceProfileEntity) {
        if (isLocalBusy()) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { readWav(voice.referenceWavPath) } }
                .onSuccess { wav ->
                    stopAudio()
                    generatedAudio = wav.samples
                    _uiState.update { it.copy(sampleRate = wav.sampleRate, sampleCount = wav.samples.size) }
                    playAudio()
                }.onFailure { error -> _uiState.update { it.copy(error = "参考音频播放失败：${error.message}") } }
        }
    }

    fun deleteVoice(voice: VoiceProfileEntity) {
        if (isLocalBusy()) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoice(voice)
            runCatching { File(voice.referenceWavPath).parentFile?.deleteRecursively() }
            if (_uiState.value.selectedVoiceId == voice.voiceId) {
                _uiState.update { it.copy(selectedVoiceId = null) }
            }
        }
    }

    fun playGeneration(generationId: Long, denoised: Boolean = false) {
        if (_uiState.value.playing && _uiState.value.playingGenerationId == generationId) {
            stopAudio()
            return
        }
        viewModelScope.launch {
            val generation = withContext(Dispatchers.IO) { dao.getGeneration(generationId) } ?: return@launch
            val path = if (denoised) generation.denoisedWavPath ?: return@launch else generation.wavPath
            val loaded = runCatching { withContext(Dispatchers.IO) { readWav(path) } }
            loaded.onSuccess { wav ->
                stopAudio()
                generatedAudio = wav.samples
                _uiState.update {
                    it.copy(
                        sampleRate = wav.sampleRate,
                        sampleCount = wav.samples.size,
                        status = if (denoised) "正在试听降噪版" else "正在试听原声",
                        error = null,
                    )
                }
                playAudio(generationId)
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "无法播放这条历史音频") }
            }
        }
    }

    fun deleteGeneration(generation: GenerationEntity) {
        if (isLocalBusy()) return
        if (_uiState.value.playingGenerationId == generation.generationId) stopAudio()
        viewModelScope.launch(Dispatchers.IO) {
            val latest = dao.getGeneration(generation.generationId) ?: return@launch
            dao.deleteGeneration(latest)
            runCatching { File(latest.wavPath).delete() }
            latest.denoisedWavPath?.let { runCatching { File(it).delete() } }
        }
    }

    fun denoiseGeneration(generationId: Long) {
        if (isLocalBusy() || recorderState.value.isRecording) return
        stopAudio()
        val cancelled = AtomicBoolean(false)
        denoiseCancelled = cancelled
        startBusy("正在进行本机降噪")
        _uiState.update { it.copy(denoisingGenerationId = generationId, denoiseProgress = 0) }
        denoiseJob = viewModelScope.launch {
            val taskJob = coroutineContext[Job]!!
            val started = SystemClock.elapsedRealtime()
            var candidate: File? = null
            var committed = false
            try {
                val result = withContext(Dispatchers.IO) {
                    val generation = dao.getGeneration(generationId) ?: error("配音记录已删除")
                    check(generation.denoisedWavPath == null) { "这条配音已有降噪版，可直接切换试听" }
                    generationDir.mkdirs()
                    val output = File(generationDir, "denoised-${generationId}-${UUID.randomUUID()}.wav")
                    candidate = output
                    val processed = LocalSpeechDenoiser.process(getApplication(), File(generation.wavPath), output,
                        { cancelled.get() || cleared || !taskJob.isActive },
                        { progress -> _uiState.update { it.copy(denoiseProgress = progress) } })
                    coroutineContext.ensureActive()
                    // Once persistence starts, finish the DB/file handoff even if the screen closes.
                    withContext(NonCancellable) {
                        check(!cancelled.get() && !cleared) { "降噪已取消，原声保留" }
                        val rows = dao.attachDenoised(generationId, generation.wavPath, output.absolutePath,
                            processed.sampleRate, processed.sampleCount, SystemClock.elapsedRealtime() - started, LocalSpeechDenoiser.MODEL)
                        check(rows == 1) { "配音记录已变化，未保存降噪结果" }
                        committed = true
                    }
                    processed
                }
                val message = if (result.changedSamples == 0L) "处理完成；保声检查后音频与原声一致" else "本机降噪完成，可切换原声与降噪版试听"
                _uiState.update { it.copy(status = message, snackbarMessage = message) }
            } catch (error: Exception) {
                if (!cleared) {
                    val message = if (cancelled.get()) "降噪已取消，原声保留" else "降噪失败，原声保留：${error.message ?: "处理未完成"}"
                    _uiState.update { it.copy(status = message, error = if (cancelled.get()) null else message, snackbarMessage = message) }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { if (!committed) candidate?.delete() }
                stopBusyTicker()
                _uiState.update { it.copy(busy = false, denoisingGenerationId = null, denoiseProgress = 0) }
                denoiseJob = null
            }
        }
    }

    fun cancelDenoise() {
        denoiseCancelled.set(true)
    }

    fun upgradeVoice(voice: VoiceProfileEntity, transcript: String) {
        if (isLocalBusy() || transcript.isBlank()) return
        if (selectedVariant().customVoice) selectModel(QwenModel.defaultVariant.id)
        startBusy("正在提取完整克隆提示")
        viewModelScope.launch {
            var pending: File? = null
            val result = runCatching { withContext(Dispatchers.IO) {
                check(transcript.length <= 2000) { "逐字稿最多 2000 字" }
                check(isModelReady(QwenModel.defaultVariant)) { "请先准备 Base 模型" }
                val output = File(File(voice.referenceWavPath).parentFile, "icl-${UUID.randomUUID()}.bin")
                pending = output
                synchronized(nativeLock) {
                    val native = ensureEngineLoaded(QwenModel.defaultVariant)
                    check(native.extractIclPrompt(voice.referenceWavPath, transcript.trim(), output.absolutePath)) { native.getLastError() ?: "完整克隆提示提取失败" }
                }
                withContext(NonCancellable) {
                    check(dao.attachIcl(voice.voiceId, transcript.trim(), output.absolutePath) == 1) { "音色已删除" }
                    pending = null
                    voice.iclPromptPath?.let { File(it).delete() }
                }
            } }
            pending?.delete()
            stopBusyTicker()
            _uiState.update { it.copy(busy = false, status = if (result.isSuccess) "完整克隆已保存" else "完整克隆失败，原音色保留", error = result.exceptionOrNull()?.message) }
        }
    }

    private fun isLocalBusy() = _uiState.value.busy || EngineHost.apiBusy.value

    fun playAudio(historyGenerationId: Long? = null) {
        val samples = generatedAudio ?: return
        val sampleRate = _uiState.value.sampleRate.takeIf { it > 0 } ?: 24_000
        stopAudio()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            _uiState.update { it.copy(playing = true, playingGenerationId = historyGenerationId) }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val durationMillis = (samples.size.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
            kotlinx.coroutines.delay(durationMillis.coerceAtLeast(200L))
            track.release()
            if (activeTrack == track) activeTrack = null
            _uiState.update { it.copy(playing = false, playingGenerationId = null) }
        }
    }

    fun stopAudio() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        _uiState.update { it.copy(playing = false, playingGenerationId = null) }
    }

    fun suggestedExportFileName(generation: GenerationEntity, denoised: Boolean = false): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        return "qwen3-tts-export-${LocalDateTime.now().format(formatter)}-${generation.generationId}-${if (denoised) "denoised" else "original"}.wav"
    }

    fun exportGenerationToUri(generation: GenerationEntity, uri: Uri, denoised: Boolean = false) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { output ->
                        val path = if (denoised) generation.denoisedWavPath ?: error("降噪版尚未生成") else generation.wavPath
                        File(path).inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("无法打开保存位置")
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            savedFileName = null,
                            snackbarMessage = "导出完成",
                            status = "WAV 已导出",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            snackbarMessage = throwable.message ?: "无法导出 WAV",
                            error = null,
                        )
                    }
                },
            )
        }
    }

    private fun ensureEngineLoaded(variant: ModelVariant): QwenEngine = synchronized(nativeLock) {
        check(!cleared) { "配音页面已关闭。" }
        val native = engine ?: QwenEngine().also { engine = it }
        val backend = selectedBackendOption()
        if (!native.setBackendPreference(backend.preference)) {
            error("无法切换运行设备：${backend.label}")
        }
        val threads = selectedCpuThreadCount()
        native.setCpuThreads(threads)
        if (!_uiState.value.loaded || EngineHost.modelName != variant.talkerName) {
            EngineHost.modelName = null
            if (!native.loadModels(modelDir.absolutePath, variant.talkerName)) {
                error(native.getLastError() ?: "本地模型加载失败，请检查文件是否完整。")
            }
            val caps = native.getModelCapabilities()
            val speakers = if (caps?.supportsNamedSpeakers == true) native.getAvailableSpeakers() else emptyList()
            EngineHost.modelName = variant.talkerName
            if (speakers.isNotEmpty()) preferences.edit().putString("presetSpeakers", speakers.joinToString("\n")).apply()
            check(!variant.customVoice || speakers.isNotEmpty()) { "此 CustomVoice 文件没有返回预置音色，请核对模型版本。" }
            _uiState.update {
                it.copy(
                    loaded = true,
                    cpuThreads = native.getCpuThreads(),
                    activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() },
                    supportsCloning = caps?.supportsCloning,
                    speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                    namedSpeakers = speakers,
                    selectedSpeaker = _uiState.value.selectedSpeaker?.takeIf { it in speakers } ?: speakers.firstOrNull(),
                )
            }
        }
        native
    }

    private fun preferredCpuThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 6
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    private fun selectedCpuThreadCount(): Int =
        _uiState.value.selectedCpuThreads.takeIf { it > 0 } ?: preferredCpuThreadCount()

    private fun selectedBackendOption(): BackendOption =
        backendOptionById(_uiState.value.selectedBackendId)

    private fun startBusy(
        status: String,
        downloading: Boolean = false,
        resetSynthesis: Boolean = false,
        synthesisEstimate: GenerationTimeEstimate? = null,
    ) {
        EngineHost.uiBusy.value = true
        operationTickerJob?.cancel()
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
                downloading = downloading,
                error = null,
                status = status,
                operationElapsedMillis = 0L,
                synthesisFrames = if (resetSynthesis) 0 else it.synthesisFrames,
                sampleCount = if (resetSynthesis) 0 else it.sampleCount,
                synthesisMillis = if (resetSynthesis) 0 else it.synthesisMillis,
                tokenizeMillis = if (resetSynthesis) 0 else it.tokenizeMillis,
                encodeMillis = if (resetSynthesis) 0 else it.encodeMillis,
                generateMillis = if (resetSynthesis) 0 else it.generateMillis,
                decodeMillis = if (resetSynthesis) 0 else it.decodeMillis,
                decodeFrames = if (resetSynthesis) 0 else it.decodeFrames,
                decodeSamples = if (resetSynthesis) 0 else it.decodeSamples,
                decodeGraphComputeMillis = if (resetSynthesis) 0 else it.decodeGraphComputeMillis,
                savedFileName = if (resetSynthesis) null else it.savedFileName,
                estimatedSynthesisMillis = synthesisEstimate?.totalMillis ?: if (resetSynthesis) null else it.estimatedSynthesisMillis,
                estimateSampleCount = synthesisEstimate?.sampleSize ?: if (resetSynthesis) 0 else it.estimateSampleCount,
            )
        }
        operationTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250L)
                val elapsed = SystemClock.elapsedRealtime() - start
                _uiState.update { current ->
                    if (current.busy) current.copy(operationElapsedMillis = elapsed) else current
                }
            }
        }
    }

    private fun stopBusyTicker() {
        operationTickerJob?.cancel()
        operationTickerJob = null
    }

    private fun estimateSynthesisMillis(text: String): GenerationTimeEstimate? {
        val ratios = generations.value
            .asSequence()
            .filter { it.synthesisMillis > 0L && it.text.isNotBlank() }
            .take(20)
            .map { generation ->
                generation.synthesisMillis.toDouble() / generation.text.length.coerceAtLeast(1).toDouble()
            }
            .filter { it.isFinite() && it > 0.0 }
            .sorted()
            .toList()

        if (ratios.size < 3) return null

        val trimmed = if (ratios.size >= 5) {
            ratios.drop(1).dropLast(1)
        } else {
            ratios
        }
        val millisPerCharacter = trimmed.average()
        val estimate = (millisPerCharacter * text.length.coerceAtLeast(1)).toLong()
            .coerceAtLeast(1_000L)
        return GenerationTimeEstimate(estimate, ratios.size)
    }

    private fun refreshModelState(status: String? = null) {
        val variant = selectedVariant()
        val ready = isModelReady(variant)
        stopBusyTicker()
        _uiState.update {
            it.copy(
                modelReady = ready,
                busy = false,
                downloading = false,
                downloadTotalBytes = variant.totalBytes,
                status = status ?: if (ready) "模型文件已就绪" else "模型尚未下载",
            )
        }
    }

    private fun selectedVariant(): ModelVariant =
        QwenModel.variantById(_uiState.value.selectedModelId)

    private fun isModelReady(variant: ModelVariant): Boolean =
        variant.files.all { file ->
            val local = File(modelDir, file.name)
            local.isFile && local.length() == file.sizeBytes
        }

    private fun downloadFiles(variant: ModelVariant) {
        var completed = 0L
        variant.files.forEachIndexed { index, file ->
            val target = File(modelDir, file.name)
            if (target.isFile && target.length() == file.sizeBytes) {
                completed += file.sizeBytes
                return@forEachIndexed
            }
            val original = file.url ?: error("此模型没有可用的下载地址。")
            val url = if (_uiState.value.downloadMirror) original.replace("https://huggingface.co/", "https://hf-mirror.com/") else original
            _uiState.update { it.copy(status = "正在连接下载 ${index + 1}/${variant.files.size}：${file.name}") }
            ModelStorage.download(url, target, file.sizeBytes, file.sha256) { bytes ->
                val total = completed + bytes
                _uiState.update { it.copy(
                    downloadBytes = total,
                    downloadProgress = total.toFloat() / variant.totalBytes,
                    status = if (bytes == file.sizeBytes) "文件校验完成：${file.name}" else "下载 ${index + 1}/${variant.files.size}：${file.name}",
                ) }
            }
            completed += file.sizeBytes
        }
    }

    private suspend fun persistGeneratedAudio(
        text: String,
        samples: FloatArray,
        sampleRate: Int,
        voiceName: String,
        voiceId: String?,
        synthesisMillis: Long,
    ) {
        withContext(Dispatchers.IO) {
            generationDir.mkdirs()
            val file = File(generationDir, "generation-${System.currentTimeMillis()}.wav")
            file.outputStream().use { writeWav(it, samples, sampleRate) }
            dao.insertGeneration(
                GenerationEntity(
                    text = text,
                    voiceId = voiceId,
                    voiceName = voiceName,
                    wavPath = file.absolutePath,
                    sampleRate = sampleRate,
                    sampleCount = samples.size,
                    synthesisMillis = synthesisMillis,
                ),
            )
        }
    }

    private fun makeUniqueVoiceName(name: String): String {
        val existing = voices.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index += 1
        return "$name $index"
    }

    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataBytes)
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
        output.writeIntLe(dataBytes)
        samples.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            output.writeShortLe((clamped * 32767f).toInt())
        }
    }

    override fun onCleared() {
        kokoroCancel.set(true)
        cleared = true
        denoiseCancelled.set(true)
        denoiseJob?.cancel()
        recorder.cancel()
        stopAudio()
        stopBusyTicker()
        EngineHost.uiBusy.value = false
        if (!EngineHost.serviceEnabled) Thread({ synchronized(nativeLock) { if (!EngineHost.serviceEnabled) EngineHost.close() } }, "qwen-engine-cleanup").start()
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            QwenTtsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QwenTtsApp()
                }
            }
        }
    }
}

private sealed class AppDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
) {
    object Studio : AppDestination("studio", "配音", { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) })
    object Voices : AppDestination("voices", "音色库", { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) })
    object Cloud : AppDestination("cloud", "云端配音", { Icon(Icons.Default.Cloud, contentDescription = null) })
    object History : AppDestination("history", "历史记录", { Icon(Icons.Default.History, contentDescription = null) })
    object Settings : AppDestination("settings", "设置", { Icon(Icons.Default.Settings, contentDescription = null) })
}

private val appDestinations = listOf(
    AppDestination.Studio,
    AppDestination.Voices,
    AppDestination.Cloud,
    AppDestination.History,
    AppDestination.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QwenTtsApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Studio.route
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbarMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                appDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigateToAppTab(destination.route)
                        },
                        icon = destination.icon,
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Studio.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideIntoContainer(direction, animationSpec = tween(260))
            },
            exitTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideOutOfContainer(direction, animationSpec = tween(260))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
        ) {
            composable(AppDestination.Studio.route) {
                StudioScreen(viewModel)
            }
            composable(AppDestination.Voices.route) {
                VoicesScreen(viewModel)
            }
            composable(AppDestination.Cloud.route) {
                CloudVoiceScreen()
            }
            composable(AppDestination.History.route) {
                HistoryScreen(viewModel)
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(viewModel)
            }
        }
    }
}

private fun destinationIndex(route: String?): Int =
    appDestinations.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    var showVoicePicker by remember { mutableStateOf(false) }
    val isCustom = QwenModel.variantById(state.selectedModelId).customVoice
    val selectedVoiceName = if (isCustom) speakerLabel(state.selectedSpeaker.orEmpty()) else voices.firstOrNull { it.voiceId == state.selectedVoiceId }?.name ?: "默认音色"

    if (showVoicePicker) {
        if (isCustom) {
            ModalBottomSheet(onDismissRequest = { showVoicePicker = false }) {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("选择预置音色", style = MaterialTheme.typography.titleLarge) }
                    if (state.namedSpeakers.isEmpty()) item { Text("请先到设置页加载 CustomVoice 模型。") }
                    items(state.namedSpeakers) { speaker ->
                        VoicePickerItem(speakerLabel(speaker), speaker, state.selectedSpeaker == speaker) {
                            viewModel.selectSpeaker(speaker)
                            showVoicePicker = false
                        }
                    }
                }
            }
        } else {
        VoicePickerSheet(
            voices = voices,
            selectedVoiceId = state.selectedVoiceId,
            onDismiss = { showVoicePicker = false },
            onVoiceSelected = { voiceId ->
                viewModel.selectVoice(voiceId)
                showVoicePicker = false
            },
        )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("文字配音", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            StudioModelPicker(state,viewModel::selectStudioModel)
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ComposerPanel(
                state = state,
                selectedVoiceName = selectedVoiceName,
                onTextChange = viewModel::updateText,
                onVoicePickerClick = { if (!state.busy) showVoicePicker = true },
                onLanguageChange = viewModel::updateLanguage,
                showQwenOptions = !state.studioKokoro,
            )
            if(state.studioKokoro) KokoroScreen(viewModel)
            if (!state.studioKokoro && ((state.busy && !state.downloading) || state.sampleCount > 0)) {
                ResultPanel(
                    state = state,
                    onPlay = viewModel::playAudio,
                    onStop = viewModel::stopAudio,
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Button(
                onClick = { if(state.studioKokoro)viewModel.synthesizeKokoro() else viewModel.synthesize() },
                enabled = (if(state.studioKokoro)state.kokoroReady else state.modelReady) && state.text.isNotBlank() && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy && !state.downloading) "处理中…" else "生成配音")
            }
        }
    }
}

@Composable
internal fun StudioModelPicker(state: QwenTtsUiState,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected=if(state.studioKokoro) (if(state.studioLightModel=="paradee")"Paradee · 超轻量英文 · 8.5 MB" else "Kokoro · 轻量中英文 · 82M") else "千问 · "+QwenModel.variantById(state.selectedModelId).displayName
    Box {
        OutlinedButton(onClick={expanded=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                Text("配音模型",style=MaterialTheme.typography.labelMedium)
                Text(selected)
            }
            Text("▾")
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            QwenModel.variants.forEach { variant ->
                DropdownMenuItem(text={Text("千问 · "+variant.displayName)},onClick={onSelect(variant.id);expanded=false})
            }
            DropdownMenuItem(text={Text("Kokoro · 轻量中英文 · 82M")},onClick={onSelect("kokoro");expanded=false})
            DropdownMenuItem(text={Text("Paradee · 超轻量英文 · 8.5 MB")},onClick={onSelect("paradee");expanded=false})
        }
    }
}

@Composable
private fun VoicesScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val context = LocalContext.current
    var voiceName by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var transcriptVoice by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    var editTranscript by remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose { if (viewModel.recorderState.value.isRecording && !viewModel.uiState.value.busy) viewModel.cancelVoiceRecording() }
    }
    LaunchedEffect(recorderState.elapsedMillis) {
        if (recorderState.isRecording && recorderState.elapsedMillis >= 30_000) {
            viewModel.stopVoiceRecordingAndCreate(voiceName, transcript)
        }
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.startVoiceRecording()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importVoice(uri, voiceName, transcript)
    }
    var editingVoice by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    var editingName by remember { mutableStateOf("") }
    var deletingVoice by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    editingVoice?.let { voice ->
        AlertDialog(
            onDismissRequest = { editingVoice = null },
            title = { Text("重命名音色") },
            text = { OutlinedTextField(editingName, { editingName = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.renameVoice(voice, editingName); editingVoice = null }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editingVoice = null }) { Text("取消") } },
        )
    }
    transcriptVoice?.let { voice ->
        AlertDialog(onDismissRequest = { transcriptVoice = null }, title = { Text("补充逐字稿 · 完整克隆") },
            text = { OutlinedTextField(editTranscript, { editTranscript = it.take(2000) }, label = { Text("与已保存参考音频完全对应的逐字稿") }) },
            confirmButton = { TextButton(onClick = { viewModel.upgradeVoice(voice, editTranscript); transcriptVoice = null }, enabled = editTranscript.isNotBlank()) { Text("保存并提取") } },
            dismissButton = { TextButton(onClick = { transcriptVoice = null }) { Text("取消") } })
    }
    deletingVoice?.let { voice ->
        AlertDialog(
            onDismissRequest = { deletingVoice = null }, title = { Text("删除音色？") },
            text = { Text("删除“${voice.name}”的参考音频和音色数据，已生成的配音仍保留。") },
            confirmButton = { TextButton(onClick = { viewModel.deleteVoice(voice); deletingVoice = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deletingVoice = null }) { Text("取消") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("本地音色库", style = MaterialTheme.typography.headlineSmall)
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("创建克隆音色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("音色名称") },
                )
                Text("导入清晰的单人讲话，建议 3～30 秒；长文件自动使用前 30 秒。音频在手机本地处理。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(transcript, { transcript = it.take(2000) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("参考音频逐字稿（完整克隆）") },
                    supportingText = { Text("填写与 3～30 秒音频完全对应的文字。留空使用音色向量克隆；完整克隆不截断长音频。") })
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*")) },
                    enabled = !state.busy && !recorderState.isRecording,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("导入音频并创建音色")
                }
                if (recorderState.isRecording) {
                    LinearProgressIndicator(
                        progress = { recorderState.level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("正在录音 ${formatDuration(recorderState.elapsedMillis)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (recorderState.isRecording) {
                                viewModel.stopVoiceRecordingAndCreate(voiceName, transcript)
                                voiceName = ""
                            } else if (hasPermission) {
                                viewModel.startVoiceRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = !state.busy || recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (recorderState.isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (recorderState.isRecording) "停止并创建" else "开始录音")
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelVoiceRecording,
                        enabled = recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }
                }
                if (state.supportsCloning == false) {
                    Text(
                        "当前模型不支持克隆音色，请在设置中选择 Base 模型。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("音色库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (voices.isEmpty()) {
                    Text("还没有音色，导入音频或录音创建第一个吧。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    voices.forEach { voice ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { viewModel.playReference(voice) }, enabled = !state.busy) { Text("试听原声") }
                            TextButton(onClick = { editingVoice = voice; editingName = voice.name }, enabled = !state.busy) { Text("重命名") }
                            TextButton(onClick = { transcriptVoice = voice; editTranscript = voice.referenceText.orEmpty() }, enabled = !state.busy) { Text(if (voice.iclPromptPath != null) "修改逐字稿" else "完整克隆") }
                        }
                        Text(if (voice.iclPromptPath != null) "完整克隆 · 音频＋逐字稿" else "音色向量克隆", style = MaterialTheme.typography.bodySmall)
                        VoiceRow(
                            voice = voice,
                            selected = state.selectedVoiceId == voice.voiceId,
                            onSelect = { viewModel.selectVoice(voice.voiceId) },
                            onDelete = { deletingVoice = voice },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val generations by viewModel.generations.collectAsState()
    val recording by viewModel.recorderState.collectAsState()
    var pendingExport by remember { mutableStateOf<Pair<GenerationEntity, Boolean>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri != null && pending != null) viewModel.exportGenerationToUri(pending.first, uri, pending.second)
    }
    HistoryContent(
        generations = generations, busy = state.busy || recording.isRecording,
        denoisingId = state.denoisingGenerationId, progress = state.denoiseProgress,
        elapsedMillis = state.operationElapsedMillis,
        playingId = if (state.playing) state.playingGenerationId else null,
        message = state.status.takeIf { state.denoisingGenerationId != null || it.contains("降噪") },
        onPlay = viewModel::playGeneration, onStop = viewModel::stopAudio,
        onDenoise = viewModel::denoiseGeneration, onCancel = viewModel::cancelDenoise,
        onExport = { generation, clean ->
            pendingExport = generation to clean
            exportLauncher.launch(viewModel.suggestedExportFileName(generation, clean))
        },
        onDelete = viewModel::deleteGeneration,
    )
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.importModels(it)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        LocalApiPanel()
        Text(state.status, style = MaterialTheme.typography.bodyMedium)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QwenModel.variants.forEach { variant ->
                FilterChip(
                    selected = state.selectedModelId == variant.id,
                    onClick = { viewModel.selectModel(variant.id) },
                    enabled = !state.busy,
                    label = { Text(if (variant.customVoice) "预置音色" else "音频克隆") },
                )
            }
        }
        Text(if (QwenModel.variantById(state.selectedModelId).customVoice)
            "CustomVoice 用于选择预置音色。下载并加载后，配音页会显示模型实际提供的音色。"
            else "Base 用于导入音频或录音创建克隆音色。两个模型共用语音解码文件。")
        ModelPanel(
            state = state,
            onDownload = viewModel::downloadModel,
            onLoad = viewModel::loadModel,
        )
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("从手机导入模型文件（可多选）")
        }
        Text("需要的文件：\n" + QwenModel.variantById(state.selectedModelId).files.joinToString("\n") { it.name }, style = MaterialTheme.typography.bodySmall)
        Text("下载源", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !state.downloadMirror, onClick = { viewModel.setDownloadMirror(false) }, enabled = !state.busy, label = { Text("Hugging Face") })
            FilterChip(selected = state.downloadMirror, onClick = { viewModel.setDownloadMirror(true) }, enabled = !state.busy, label = { Text("HF 镜像（第三方）") })
        }
        Text("下载失败可重试续传，也可用浏览器下载后导入；两种方式都会校验文件 SHA-256。", style = MaterialTheme.typography.bodySmall)
        RuntimePanel(
            state = state,
            onCpuThreadsChange = viewModel::updateCpuThreads,
        )
        Text("声芽配音 0.8.0 · 社区开源版\n基于 Danmoreng/qwen3-tts-android（MIT）。模型单独下载。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ComposerPanel(
    state: QwenTtsUiState,
    selectedVoiceName: String,
    onTextChange: (String) -> Unit,
    onVoicePickerClick: () -> Unit,
    onLanguageChange: (Int) -> Unit,
    showQwenOptions: Boolean = true,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = { Text("输入配音文案") },
                enabled = !state.busy,
            )
            if(showQwenOptions) {
            OutlinedButton(
                onClick = onVoicePickerClick,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text("音色", style = MaterialTheme.typography.labelMedium)
                    Text(selectedVoiceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LanguageDropdown(
                selectedLanguageId = state.selectedLanguageId,
                enabled = !state.busy,
                onLanguageChange = onLanguageChange,
            )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    voices: List<VoiceProfileEntity>,
    selectedVoiceId: String?,
    onDismiss: () -> Unit,
    onVoiceSelected: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("选择本地音色", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            VoicePickerItem(
                title = "默认音色",
                subtitle = "使用 Base 模型默认声音；可在音色库创建克隆音色",
                selected = selectedVoiceId == null,
                onClick = { onVoiceSelected(null) },
            )
            voices.forEach { voice ->
                VoicePickerItem(
                    title = voice.name,
                    subtitle = "参考音频 ${formatDuration(voice.durationMillis)}",
                    selected = selectedVoiceId == voice.voiceId,
                    onClick = { onVoiceSelected(voice.voiceId) },
                )
            }
        }
    }
}

@Composable
private fun VoicePickerItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = "已选择", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceProfileEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterChip(selected = selected, onClick = onSelect, label = { Text(if (selected) "已选" else "选用") })
        Column(Modifier.weight(1f)) {
            Text(voice.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "参考音频 ${formatDuration(voice.durationMillis)} · ${formatDate(voice.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "删除")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selectedLanguageId: Int,
    enabled: Boolean,
    onLanguageChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = languageOptions.firstOrNull { it.id == selectedLanguageId } ?: languageOptions.first()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
            label = { Text("语言") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languageOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onLanguageChange(option.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPanel(
    state: QwenTtsUiState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    val selectedVariant = QwenModel.variantById(state.selectedModelId)
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(selectedVariant.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "模型文件共 ${formatBytes(selectedVariant.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(onClick = {}, label = { Text(if (state.modelReady) "已就绪" else "待下载") })
            }

            if (state.downloading) {
                LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(state.downloadBytes)} / ${formatBytes(state.downloadTotalBytes)}", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = !state.busy && !state.modelReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.modelReady) "模型文件已就绪" else "下载模型")
                }
                OutlinedButton(
                    onClick = onLoad,
                    enabled = state.modelReady && !state.loaded && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.loaded) "已加载" else "加载模型")
                }
            }
        }
    }
}

@Composable
private fun RuntimePanel(
    state: QwenTtsUiState,
    onCpuThreadsChange: (Int) -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Text("性能设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "本地 CPU 运行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6).forEach { threads ->
                    FilterChip(
                        selected = state.selectedCpuThreads == threads,
                        onClick = { onCpuThreadsChange(threads) },
                        enabled = !state.busy,
                        label = { Text("$threads 线程") },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(state: QwenTtsUiState, onPlay: () -> Unit, onStop: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy && !state.downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                GenerationStats(state)
            } else {
                if (state.sampleCount > 0 && state.sampleRate > 0) {
                    Text(
                        "音频 ${formatSeconds(state.sampleCount.toDouble() / state.sampleRate.toDouble())} · 合成耗时 ${formatDuration(state.synthesisMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { if (state.playing) onStop() else onPlay() },
                    enabled = state.sampleCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        if (state.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.playing) "停止" else "播放")
                }
            }
        }
    }
}

@Composable
private fun GenerationStats(state: QwenTtsUiState) {
    val generatedAudioSeconds = state.synthesisFrames * 0.08
    val remainingMillis = state.estimatedSynthesisMillis
        ?.minus(state.operationElapsedMillis)
        ?.coerceAtLeast(0L)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "已用时 ${formatDuration(state.operationElapsedMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.synthesisFrames > 0) {
            Text(
                "已生成约 ${formatSeconds(generatedAudioSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (remainingMillis != null && state.estimateSampleCount >= 3) {
            Text(
                "预计剩余 ${formatDuration(remainingMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class WavData(val samples: FloatArray, val sampleRate: Int)

private fun readWav(path: String): WavData {
    val bytes = File(path).readBytes()
    require(bytes.size > 44) { "WAV 音频为空" }
    fun intLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    fun shortLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    var cursor = 12
    var sampleRate = 24_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = intLe(cursor + 4)
        val payload = cursor + 8
        when (id) {
            "fmt " -> {
                channels = shortLe(payload + 2).coerceAtLeast(1)
                sampleRate = intLe(payload + 4)
                bits = shortLe(payload + 14)
            }
            "data" -> {
                dataOffset = payload
                dataSize = size
                break
            }
        }
        cursor = payload + size
    }
    require(dataOffset >= 0 && bits == 16) { "此音频不是受支持的 PCM16 WAV 文件" }
    val frameBytes = channels * 2
    val frameCount = dataSize / frameBytes
    val samples = FloatArray(frameCount)
    var offset = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0f
        for (channel in 0 until channels) {
            val value = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort()
            sum += value / 32768f
            offset += 2
        }
        samples[i] = sum / channels
    }
    return WavData(samples, sampleRate)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "%d:%02d".format(Locale.US, minutes, seconds) else "%d 秒".format(Locale.US, seconds)
}

private fun formatSeconds(seconds: Double): String =
    "%.1f 秒".format(Locale.US, seconds.coerceAtLeast(0.0))

private fun formatDate(epochMillis: Long): String {
    val date = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return date.format(java.util.Date(epochMillis))
}

private fun backendOptionById(id: String): BackendOption =
    backendOptions.firstOrNull { it.id == id } ?: defaultBackendOption

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

private fun speakerLabel(name: String): String = when (name.lowercase(Locale.ROOT)) {
    "vivian" -> "Vivian · 中文女声"
    "serena" -> "Serena · 中文女声"
    "uncle_fu" -> "Uncle Fu · 中文男声"
    "dylan" -> "Dylan · 北京话男声"
    "eric" -> "Eric · 四川话男声"
    "ryan" -> "Ryan · 英语男声"
    "aiden" -> "Aiden · 英语男声"
    "ono_anna" -> "Ono Anna · 日语女声"
    "sohee" -> "Sohee · 韩语女声"
    "" -> "请先加载预置音色模型"
    else -> name
}
