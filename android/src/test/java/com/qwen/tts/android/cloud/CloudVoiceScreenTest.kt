package com.qwen.tts.android.cloud

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp")
class CloudVoiceScreenTest {
    @get:Rule val compose = createComposeRule()
    private val config = CloudConfig("https://example.com:8443/custom/tts", "fixture-model", "fixture-voice")

    private fun show(state: CloudUiState, play: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme { CloudVoiceContent(state, { _, _ -> true }, {}, {}, play) }
        }
    }

    @Test fun addressChangeClearsDraftKey() {
        show(CloudUiState(config = config))
        val page = compose.onNodeWithTag("cloud_page")
        page.performScrollToNode(hasTestTag("cloud_api_key"))
        compose.onNodeWithTag("cloud_api_key").assertIsEnabled().performTextInput("test-key")
        page.performScrollToNode(hasTestTag("cloud_endpoint"))
        compose.onNodeWithTag("cloud_endpoint").performTextInput("/changed")
        page.performScrollToNode(hasTestTag("cloud_api_key"))
        compose.onNodeWithTag("cloud_api_key").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun cloudResultCanBePlayedWithoutAnImportAction() {
        var played = false
        show(CloudUiState(config = config, hasKey = true, sample = File("sample.wav"))) { played = true }
        compose.onNodeWithTag("cloud_page").performScrollToNode(hasText("播放音频"))
        compose.onNodeWithText("播放音频").performClick()
        compose.runOnIdle { assertEquals(true, played) }
        compose.onNodeWithTag("cloud_clone_local").assertDoesNotExist()
    }

    @Test fun customEndpointUsesSpeechSchemaAndRejectsCredentialBearingUrls() {
        assertEquals(8443, config.url().port)
        val request = JSONObject(config.requestBody("测试文案").toString(Charsets.UTF_8))
        assertEquals("fixture-model", request.getString("model"))
        assertEquals("fixture-voice", request.getString("voice"))
        assertEquals("测试文案", request.getString("input"))
        assertEquals("wav", request.getString("response_format"))
        for (endpoint in listOf("http://example.com/tts", "https://key@example.com/tts", "https://example.com/tts?key=x")) {
            assertThrows(IllegalArgumentException::class.java) { config.copy(endpoint = endpoint).url() }
        }
    }
}
