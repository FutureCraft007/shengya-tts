package com.qwen.tts.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qwen.tts.android.data.db.GenerationEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp")
class HistoryContentTest {
    @get:Rule val compose = createComposeRule()
    private val record = GenerationEntity(7, "一段测试配音", null, "测试音色", "original.wav", 24000, 24000, 1000)

    private fun show(record: GenerationEntity = this.record, busy: Boolean = false, denoising: Long? = null,
        denoise: (Long) -> Unit = {}, cancel: () -> Unit = {}, play: (Long, Boolean) -> Unit = { _, _ -> },
        export: (GenerationEntity, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent { MaterialTheme {
            HistoryContent(listOf(record), busy, denoising, 45, 3000, null, null, play, {}, denoise, cancel, export, {})
        } }
    }

    @Test fun historyHasManualDenoiseAndPreventsConcurrentLocalTask() {
        var id = 0L
        show(denoise = { id = it })
        compose.onNodeWithTag("history_denoise_7").assertIsEnabled().performClick()
        assertEquals(7L, id)
    }

    @Test fun localBusyDisablesDenoiseButAllowsOriginalPlayback() {
        show(busy = true)
        compose.onNodeWithTag("history_denoise_7").assertIsNotEnabled()
        compose.onNodeWithTag("history_play_7").assertIsEnabled()
    }

    @Test fun processedAndOriginalVariantsAreExplicitForPlaybackAndExport() {
        val choices = mutableListOf<Boolean>()
        var exported: Boolean? = null
        show(record.copy(denoisedWavPath = "clean.wav"), play = { _, clean -> choices += clean }, export = { _, clean -> exported = clean })
        compose.onNodeWithTag("history_play_7").performClick()
        compose.onNodeWithTag("history_original_7").performClick()
        compose.onNodeWithTag("history_play_7").performClick()
        assertEquals(listOf(true, false), choices)
        compose.onNodeWithTag("history_more_7").performClick()
        compose.onNodeWithText("导出原声").performClick()
        assertEquals(false, exported)
        compose.onNodeWithTag("history_clean_7").performClick()
        compose.onNodeWithTag("history_more_7").performClick()
        compose.onNodeWithText("导出降噪版").performClick()
        assertEquals(true, exported)
        compose.onNodeWithTag("history_denoise_7").assertDoesNotExist()
    }

    @Test fun processingHasProgressAndCancelAndDisablesDestructiveActions() {
        var cancelled = false
        show(busy = true, denoising = 7, cancel = { cancelled = true })
        compose.onNodeWithText("正在本机降噪 45% · 已用 3 秒").assertExists()
        compose.onNodeWithTag("history_play_7").assertIsNotEnabled()
        compose.onNodeWithTag("history_cancel_7").performClick()
        assertTrue(cancelled)
        compose.onNodeWithTag("history_more_7").performClick()
        compose.onNodeWithText("删除记录及音频").assertIsNotEnabled()
        compose.onNodeWithText("导出原声").assertIsNotEnabled()
    }
}
