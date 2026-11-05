package com.qwen.tts.android.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.qwen.tts.android.cloud.CloudUiState
import com.qwen.tts.android.cloud.CloudVoiceContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp")
class AppNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private val tabs = listOf("studio", "voices", "cloud", "history", "settings")

    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                nav = rememberNavController()
                Column {
                    Row {
                        tabs.forEach { route ->
                            Button(onClick = { nav.navigateToAppTab(route) }, modifier = Modifier.testTag("tab_$route")) {
                                Text(route.take(1))
                            }
                        }
                    }
                    NavHost(nav, startDestination = "studio", modifier = Modifier.weight(1f),
                        enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None }, popExitTransition = { ExitTransition.None }) {
                        tabs.forEach { route -> composable(route) {
                            if (route == "cloud") {
                                CloudVoiceContent(CloudUiState(), onConfigure = { _, _ -> true },
                                    onClearKey = {}, onPrepare = {}, onPlay = {})
                            } else {
                                var count by rememberSaveable { mutableStateOf(0) }
                                Button(onClick = { count++ }, modifier = Modifier.testTag("page_$route")) { Text("$route:$count") }
                            }
                        } }
                    }
                }
            }
        }
        return restoration
    }

    private fun tab(route: String) {
        compose.onNodeWithTag("tab_$route").performClick()
        compose.runOnIdle { assertEquals(route, nav.currentDestination?.route) }
    }

    @Test fun repairsCloudStackContainingVoicesFromAnOlderVersion() {
        show()
        tab("cloud")
        // The previous clone callback pushed Voices on top of Cloud.
        compose.runOnIdle { nav.navigate("voices") { launchSingleTop = true } }
        tab("history")
        tab("cloud")
        compose.onNodeWithTag("cloud_api_key").assertIsEnabled()
    }

    @Test fun visitingEveryTabAndReturningToCloudShowsItsConfiguration() {
        show()
        tab("cloud")
        tab("voices")
        compose.onNodeWithTag("page_voices").assertExists()
        listOf("history", "settings", "studio", "voices").forEach { route ->
            tab(route)
            tab("cloud")
            compose.onNodeWithTag("cloud_page").performScrollToNode(hasTestTag("cloud_api_key"))
            compose.onNodeWithTag("cloud_api_key").assertIsEnabled()
        }
    }

    @Test fun restoringTabsRetainsTheirSavedStateAndReselectingCloudIsStable() {
        show()
        tab("voices")
        compose.onNodeWithTag("page_voices").performClick()
        tab("cloud")
        tab("cloud")
        tab("voices")
        compose.onNodeWithText("voices:1").assertExists()
        tab("cloud")
        compose.onNodeWithTag("cloud_api_key").assertIsEnabled()
    }

    @Test fun repairsLegacyCloudStackAfterSavedInstanceRestoration() {
        val restoration = show()
        tab("cloud")
        compose.runOnIdle { nav.navigate("voices") { launchSingleTop = true } }
        tab("settings")
        restoration.emulateSavedInstanceStateRestore()
        tab("cloud")
        compose.onNodeWithTag("cloud_api_key").assertIsEnabled()
    }

    @Test fun switchingToVoicesUsesRootTab() {
        show()
        tab("cloud")
        tab("voices")
        compose.runOnIdle {
            assertEquals("voices", nav.currentDestination?.route)
            nav.popBackStack()
            assertEquals("studio", nav.currentDestination?.route)
        }
    }
}
