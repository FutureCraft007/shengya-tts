package com.qwen.tts.android.ui

import androidx.navigation.NavHostController

/** Root tabs must never become child pages of another tab's saved stack. */
internal fun NavHostController.navigateToAppTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        launchSingleTop = true
        restoreState = true
        popUpTo("studio") { saveState = true }
    }
    // Older versions pushed Voices on top of Cloud after cloning. Restoring
    // that saved tab also restores its child; reveal the requested root while
    // retaining its ViewModel, configuration, sample and saved scroll position.
    if (currentDestination?.route != route) {
        popBackStack(route, inclusive = false)
    }
}
