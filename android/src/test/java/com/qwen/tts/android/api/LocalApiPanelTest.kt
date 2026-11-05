package com.qwen.tts.android.api

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp")
class LocalApiPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectingAndSavingSixLanesDoesNotStartTheListenerOrAnyWorker() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        LocalApiService.state.value = ApiServiceState()
        app.getSharedPreferences("local-api", 0).edit().putInt("concurrency", 1).commit()
        shadowOf(app).clearStartedServices()
        compose.setContent { MaterialTheme { LocalApiPanel() } }
        compose.onNodeWithTag("local_api_concurrency_6").performClick()
        assertEquals(6, LocalApiService.concurrency(app))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(LocalApiService.state.value.running)
        compose.onNodeWithTag("local_api_concurrency_2").performClick()
        assertEquals(2, LocalApiService.concurrency(app))
        assertNull(shadowOf(app).nextStartedService)
    }
}
