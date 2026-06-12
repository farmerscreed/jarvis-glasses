package com.echo.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.echo.app.CompanionPrefs
import com.echo.app.ConnectedCompanionService
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisBottomBar
import com.echo.app.ui.components.JarvisTab
import com.echo.app.ui.components.JarvisTopBar
import com.echo.app.ui.dev.DevConsoleScreen
import com.echo.app.ui.help.HelpScreen
import com.echo.app.ui.onboarding.OnboardingPrefs
import com.echo.app.ui.onboarding.OnboardingScreen
import com.echo.app.ui.screens.GalleryScreen
import com.echo.app.ui.screens.LiveConsoleScreen
import com.echo.app.ui.screens.SettingsScreen
import com.echo.app.ui.screens.TimelineScreen

/**
 * The Companion Console shell: JARVIS top bar, four tabs (Live/Timeline/Gallery/Settings), and
 * the legacy dev console tucked behind Settings → Developer console. Pure presentation — the
 * single HomeViewModel drives every screen.
 */
@Composable
fun AppRoot() {
    val vm: HomeViewModel = hiltViewModel()
    val context = LocalContext.current
    var onboarded by rememberSaveable { mutableStateOf(OnboardingPrefs.isDone(context)) }
    var tab by rememberSaveable { mutableStateOf(JarvisTab.Live) }
    var devConsole by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    // Restart the background companion on app launch if the user enabled it and is signed in.
    LaunchedEffect(vm.loggedIn, onboarded) {
        if (onboarded && vm.loggedIn && CompanionPrefs.isEnabled(context)) {
            ConnectedCompanionService.start(context)
        }
    }

    if (!onboarded) {
        OnboardingScreen(vm, onFinish = { onboarded = true })
        return
    }

    if (showHelp) {
        HelpScreen(onClose = { showHelp = false })
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (devConsole) {
                JarvisTopBar(
                    title = "Dev console",
                    wordmark = false,
                    onNavigate = { devConsole = false },
                    navigateBack = true,
                )
            } else {
                JarvisTopBar(onHelp = { showHelp = true })
            }
        },
        bottomBar = {
            if (!devConsole) {
                JarvisBottomBar(current = tab, onSelect = { tab = it })
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                devConsole -> DevConsoleScreen(vm)
                tab == JarvisTab.Live -> LiveConsoleScreen(vm)
                tab == JarvisTab.Timeline -> TimelineScreen(vm)
                tab == JarvisTab.Gallery -> GalleryScreen(vm)
                tab == JarvisTab.Settings -> SettingsScreen(vm, onOpenDevConsole = { devConsole = true })
            }
        }
    }

}
