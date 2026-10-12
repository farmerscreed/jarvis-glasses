package com.echo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.echo.app.CompanionPrefs
import com.echo.app.ConnectedCompanionService
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisBottomBar
import com.echo.app.ui.components.JarvisTab
import com.echo.app.ui.components.LiveBars
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoTopBar
import com.echo.app.ui.dev.DevConsoleScreen
import com.echo.app.ui.help.HelpScreen
import com.echo.app.ui.onboarding.OnboardingPrefs
import com.echo.app.ui.onboarding.OnboardingScreen
import com.echo.app.ui.screens.AskJarvisScreen
import com.echo.app.ui.screens.DeviceScreen
import com.echo.app.ui.screens.LiveConsoleScreen
import com.echo.app.ui.screens.MeetingScreen
import com.echo.app.ui.screens.MemoriesScreen
import com.echo.app.ui.screens.ResearchScreen
import com.echo.app.ui.screens.SettingsScreen
import com.echo.app.ui.screens.TranslateScreen

/**
 * The app shell (redesign 2026-10): four tabs — Home · Translate · Memories · Settings — plus full
 * screens with a back button (Ask JARVIS, Research, Device, Help, developer console). A slim
 * "Translating" bar follows you around while a translation runs. One HomeViewModel drives it all.
 */
@Composable
fun AppRoot() {
    val vm: HomeViewModel = hiltViewModel()
    val context = LocalContext.current
    var onboarded by rememberSaveable { mutableStateOf(OnboardingPrefs.isDone(context)) }
    var tab by rememberSaveable { mutableStateOf(JarvisTab.Home) }
    var devConsole by rememberSaveable { mutableStateOf(false) }
    var device by rememberSaveable { mutableStateOf(false) }
    var ask by rememberSaveable { mutableStateOf(false) }
    var research by rememberSaveable { mutableStateOf(false) }
    var meeting by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    // Restart the background companion on app launch if the user enabled it and is signed in.
    LaunchedEffect(vm.loggedIn, onboarded) {
        if (onboarded && vm.loggedIn && CompanionPrefs.isEnabled(context)) ConnectedCompanionService.start(context)
    }
    // Voice / glasses can open Translate ("Jarvis, translate to French", double-tap): jump to its tab.
    LaunchedEffect(vm.translateOpen) {
        if (vm.translateOpen) { tab = JarvisTab.Translate; ask = false; research = false; device = false; vm.translateOpen = false }
    }

    // Meeting screen: opened by voice ("record this meeting") or by tapping the recording notification.
    LaunchedEffect(vm.meetingOpen, com.echo.app.meeting.MeetingNav.openRequested) {
        if (vm.meetingOpen || com.echo.app.meeting.MeetingNav.openRequested) {
            meeting = true; ask = false; research = false; device = false
            vm.meetingOpen = false; com.echo.app.meeting.MeetingNav.openRequested = false
        }
    }

    LaunchedEffect(com.echo.app.meeting.MeetingNav.openRecording) {
        if (com.echo.app.meeting.MeetingNav.openRecording != null) { tab = JarvisTab.Memories; meeting = false; ask = false; research = false; device = false }
    }

    if (!onboarded) {
        OnboardingScreen(vm, onFinish = { onboarded = true })
        return
    }
    if (showHelp) {
        BackHandler { showHelp = false }
        HelpScreen(onClose = { showHelp = false })
        return
    }

    if (vm.showRecordingConsent) {
        AlertDialog(
            onDismissRequest = vm::dismissRecordingConsent,
            containerColor = Neo.Card,
            title = { Text("Microphone & recordings", style = Neo.display.copy(fontSize = 20.sp)) },
            text = {
                Text(
                    "JARVIS records your voice when you talk to it or use the wake word, and sends the audio to the cloud to " +
                        "transcribe it. Recordings become memories you can review or delete anytime. Continue?",
                    style = Neo.body.copy(color = Neo.TextSoft),
                )
            },
            confirmButton = { TextButton(onClick = vm::grantRecordingConsent) { Text("I agree", color = Neo.Cyan) } },
            dismissButton = { TextButton(onClick = vm::dismissRecordingConsent) { Text("Not now", color = Neo.Text) } },
        )
    }

    val fullScreen = devConsole || device || ask || research || meeting
    if (fullScreen) BackHandler { devConsole = false; device = false; ask = false; research = false; meeting = false }
    else if (tab != JarvisTab.Home) BackHandler { tab = JarvisTab.Home }

    Scaffold(
        containerColor = Neo.Ground,
        topBar = {
            when {
                devConsole -> NeoTopBar("Developer console", onBack = { devConsole = false })
                device -> NeoTopBar("Glasses", onBack = { device = false })
                ask -> NeoTopBar("Ask JARVIS", onBack = { ask = false })
            }
        },
        bottomBar = {
            if (!fullScreen) Column {
                if (vm.meetingUi.active) RecordingBar(vm.meetingUi.elapsedMs) { meeting = true }
                if (vm.translateUi.active && tab != JarvisTab.Translate) TranslatingBar(vm.translateUi.theirLang) { tab = JarvisTab.Translate }
                JarvisBottomBar(current = tab, onSelect = { tab = it })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                devConsole -> DevConsoleScreen(vm)
                device -> DeviceScreen(vm)
                ask -> AskJarvisScreen(vm)
                research -> ResearchScreen(vm, onBack = { research = false }, onVoice = vm::researchByVoice)
                meeting -> MeetingScreen(vm, onBack = { meeting = false })
                tab == JarvisTab.Home -> LiveConsoleScreen(
                    vm,
                    onOpenAsk = { vm.clearAskPhoto(); ask = true },
                    onOpenTranslate = { tab = JarvisTab.Translate },
                    onOpenResearch = { research = true },
                    onOpenDevice = { device = true },
                    onOpenMeeting = { meeting = true },
                    onHelp = { showHelp = true },
                )
                tab == JarvisTab.Translate -> TranslateScreen(vm)
                tab == JarvisTab.Memories -> MemoriesScreen(vm, onAskAboutPhoto = { mem -> vm.openAskPhoto(mem); ask = true })
                tab == JarvisTab.Settings -> SettingsScreen(
                    vm,
                    onOpenDevConsole = { devConsole = true },
                    onOpenDevice = { device = true },
                    onHelp = { showHelp = true },
                )
            }
        }
    }
}

/** Slim bar above the tabs while translation runs on another screen — tap to go back to it. */
@Composable
private fun TranslatingBar(lang: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Neo.Bar).padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50)).background(Neo.Cyan.copy(alpha = 0.10f)).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveBars(active = true, bars = 5, maxHeight = 16.dp, barWidth = 3.dp)
        Text(
            "Translating" + (if (lang.isNotBlank()) " · ${com.echo.app.translate.TranslationController.langName(lang)}" else "") + " — tap to open",
            style = Neo.body.copy(fontSize = 14.sp, color = Neo.CyanPale),
        )
        Box(Modifier.fillMaxWidth())
    }
}

/** Slim red bar above the tabs while a meeting records — tap to open it. */
@Composable
private fun RecordingBar(elapsedMs: Long, onOpen: () -> Unit) {
    val red = androidx.compose.ui.graphics.Color(0xFFFF5C5C)
    Row(
        Modifier.fillMaxWidth().background(Neo.Bar).padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50)).background(red.copy(alpha = 0.12f)).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.padding(2.dp).clip(RoundedCornerShape(50)).background(red).padding(4.dp))
        Text("Recording · ${com.echo.app.meeting.MeetingRecorder.clock(elapsedMs)} — tap to open", style = Neo.body.copy(fontSize = 14.sp, color = Neo.Danger))
    }
}
