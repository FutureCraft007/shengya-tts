package com.qwen.tts.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.qwen.tts.android.QwenTtsUiState
import com.qwen.tts.android.StudioModelPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp")
class StudioModelPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseKokoroAndReturnToQwenPreservesDraft() {
        var state by mutableStateOf(QwenTtsUiState(text="切换模型时保留这段文案。"))
        compose.setContent {
            MaterialTheme {
                StudioModelPicker(state) { id ->
                    state=state.copy(studioKokoro=id=="kokoro",selectedModelId=if(id=="kokoro")state.selectedModelId else id)
                }
            }
        }
        compose.onNodeWithText("配音模型").performClick()
        compose.onNodeWithText("Kokoro · 轻量中英文 · 82M").performClick()
        compose.runOnIdle {
            assertTrue(state.studioKokoro)
            assertEquals("切换模型时保留这段文案。",state.text)
        }
        compose.onNodeWithText("配音模型").performClick()
        compose.onAllNodesWithText("千问 · ",substring=true)[0].performClick()
        compose.runOnIdle { assertFalse(state.studioKokoro) }
    }

    @Test fun cannotChangeModelDuringGeneration() {
        compose.setContent { MaterialTheme { StudioModelPicker(QwenTtsUiState(busy=true)) { error("Must remain disabled") } } }
        compose.onNodeWithText("配音模型").assertIsNotEnabled()
    }

    @Test fun paradeeIsAvailableInTheSameModelPicker() {
        var selected=""
        compose.setContent {MaterialTheme {StudioModelPicker(QwenTtsUiState()) {selected=it}}}
        compose.onNodeWithText("配音模型").performClick()
        compose.onNodeWithText("Paradee · 超轻量英文 · 8.5 MB").performClick()
        compose.runOnIdle {assertEquals("paradee",selected)}
    }
}
