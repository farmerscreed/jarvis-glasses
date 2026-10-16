package com.echo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.FeatureTile
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.GlowButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoCard
import com.echo.app.ui.components.OrbState
import com.echo.app.ui.components.PresenceOrb
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.SourcesRow
import com.echo.app.ui.components.StatusDot
import com.echo.app.ui.components.TranscriptBubble
import com.echo.app.ui.components.rememberGlassesAudioConnected
import com.echo.app.ui.components.splitSources

/** Engine status → orb animation. */
private fun orbStateOf(vm: HomeViewModel): OrbState = when {
    !vm.online -> OrbState.OffGrid
    vm.status.startsWith("Researching") -> OrbState.Deliberating
    vm.status.contains("istening") || vm.status.startsWith("Translating") -> OrbState.Listening
    vm.status.startsWith("Transcribing") || vm.status.startsWith("Thinking") || vm.status.startsWith("Looking") ||
        vm.status.startsWith("Claude") || vm.status.startsWith("Signing") || vm.status.startsWith("Verifying") -> OrbState.Thinking
    vm.status.startsWith("Speaking") -> OrbState.Speaking
    else -> OrbState.Idle
}

/** Status strings that are just "nothing happening" — not worth showing under the orb. */
private fun quietStatus(s: String) = s.isBlank() || s == "Done" || s == "Idle" || s.startsWith("Signed in") || s == "Saved" ||
    s == "Look & Ask done" || s == "Conversation ended" || s == "Checked your calendar"

/**
 * Home (redesign 2026-10): the orb IS the talk button; one status line; a "Do something" grid so every
 * feature is one tap away (voice stays the fast path); the conversation or the last answer below.
 */
@Composable
fun LiveConsoleScreen(
    vm: HomeViewModel,
    onOpenAsk: () -> Unit = {},
    onOpenTranslate: () -> Unit = {},
    onOpenResearch: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
    onOpenMeeting: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    if (!vm.loggedIn) {
        SignInPane(vm)
        return
    }
    val audioInGlasses = rememberGlassesAudioConnected()
    val bleUp = vm.bleStatus.contains("listening", true) || vm.bleStatus.contains("glasses IP", true) || vm.bleStatus.contains("already connected", true)
    var showRemember by rememberSaveable { mutableStateOf(false) }
    var showRecord by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Neo.Ground).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
        // Header.
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS", style = Neo.display.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.18.em, color = Neo.Cyan), modifier = Modifier.weight(1f))
            if (vm.micActive) MicLive()
            Spacer(Modifier.size(8.dp))
            RoundButton(Icons.AutoMirrored.Outlined.HelpOutline, "Help", onClick = onHelp)
        }

        // Orb = talk.
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(184.dp).clip(CircleShape)
                    .clickable(enabled = !vm.busy || vm.inConversation, onClickLabel = if (vm.inConversation) "End conversation" else "Talk to JARVIS", onClick = vm::talk),
                contentAlignment = Alignment.Center,
            ) {
                PresenceOrb(state = orbStateOf(vm), modifier = Modifier.size(176.dp))
                if (orbStateOf(vm) == OrbState.Idle || orbStateOf(vm) == OrbState.OffGrid) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, tint = Neo.CyanPale.copy(alpha = 0.9f), modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    vm.inConversation -> "I'm listening — tap to end"
                    !vm.online -> "Off-grid — I'll keep everything on the phone"
                    else -> "Say “Jarvis”, or tap"
                },
                style = Neo.display.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenDevice).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(
                    when {
                        vm.glassesBattery != null -> "Glasses ${vm.glassesBattery}%"
                        bleUp || audioInGlasses -> "Glasses on"
                        else -> "Glasses off"
                    },
                    if (bleUp || audioInGlasses) Neo.Cyan else Neo.Amber,
                )
                StatusDot(if (!vm.online) "Off-grid" else if (vm.tier == "lean") "Slow" else "Online", if (vm.online && vm.tier != "lean") Neo.Cyan else Neo.Amber)
                if (vm.pendingSync > 0) StatusDot("${vm.pendingSync} to sync", Neo.Amber)
            }
            if (!quietStatus(vm.status)) {
                Text(
                    vm.status, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp, color = if (vm.status.startsWith("Error")) Neo.Danger else Neo.Faint),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (vm.sttModelStatus.isNotBlank()) Text(vm.sttModelStatus, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Cyan), modifier = Modifier.padding(top = 4.dp))
        }

        // The conversation, while it's going.
        if (vm.transcript.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            NeoCard(padding = 14.dp) {
                val scroll = rememberScrollState()
                LaunchedEffect(vm.transcript.size) { scroll.animateScrollTo(scroll.maxValue) }
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.transcript.forEach { line ->
                        val (body, sources) = if (line.fromUser) line.text to emptyList() else splitSources(line.text)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start) { TranscriptBubble(body, fromUser = line.fromUser) }
                        if (sources.isNotEmpty()) SourcesRow(sources)
                    }
                }
                if (vm.inConversation) GhostButton("End conversation", onClick = vm::endConversation, height = 44.dp, modifier = Modifier.fillMaxWidth())
            }
        }

        // Record: Meeting (audio) · Video · Site walk — with the glasses or the phone camera.
        if (showRecord) RecordChooser(
            glassesReady = vm.glassesCanFilm,
            glassesWhyNot = vm.glassesFilmWhyNot,
            onMeeting = { showRecord = false; onOpenMeeting() },
            onGlasses = { walk -> showRecord = false; vm.startGlassesVideoFromApp(walk) },
            onPhone = { walk -> showRecord = false; vm.cameraRequest = if (walk) "walk" else "video" },
            onDismiss = { showRecord = false },
        )

        // Do something.
        Spacer(Modifier.height(22.dp))
        MonoLabel("Do something", modifier = Modifier.padding(bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureTile(Icons.Outlined.Visibility, "Look", onClick = vm::lookNow, modifier = Modifier.weight(1f))
                FeatureTile(Icons.Outlined.Translate, "Translate", onClick = onOpenTranslate, modifier = Modifier.weight(1f))
                FeatureTile(Icons.Outlined.AutoAwesome, "Ask", onClick = onOpenAsk, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FeatureTile(Icons.Outlined.Search, "Research", onClick = onOpenResearch, modifier = Modifier.weight(1f))
                FeatureTile(Icons.Outlined.PersonAddAlt, "Remember", onClick = { showRemember = true }, modifier = Modifier.weight(1f))
                FeatureTile(Icons.Outlined.RadioButtonChecked, "Record", onClick = { showRecord = true }, accent = androidx.compose.ui.graphics.Color(0xFFFF7A7A), modifier = Modifier.weight(1f))
            }
        }

        // Last answer (the typed/one-shot paths; the voice conversation shows above).
        if (vm.answer.isNotBlank() && vm.transcript.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            NeoCard(padding = 16.dp) {
                MonoLabel("Last answer", color = Neo.Cyan)
                val (body, sources) = splitSources(vm.answer)
                Text(body, style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Neo.TextSoft))
                if (sources.isNotEmpty()) SourcesRow(sources)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showRemember) RememberPersonDialog(vm, onClose = { showRemember = false })
}

@Composable
private fun MicLive() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Neo.DangerFill).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Neo.Danger))
        Icon(Icons.Outlined.Mic, contentDescription = "Microphone is on", tint = Neo.Danger, modifier = Modifier.size(14.dp))
    }
}

/** Home "Remember" tile: type who you met. Voice works too ("Jarvis, remember this person…"). */
@Composable
private fun RememberPersonDialog(vm: HomeViewModel, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Neo.Card).border(1.dp, Neo.HeroBorder, RoundedCornerShape(28.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Remember a person", style = Neo.display.copy(fontSize = 20.sp))
            Text("Who they are, where you met, a number if you have it.", style = Neo.body.copy(fontSize = 14.sp, color = Neo.Muted))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 2,
                placeholder = { Text("Mr Ade, cement supplier, met at the Trans-Amadi yard", style = Neo.body.copy(color = Neo.Faint)) },
                shape = RoundedCornerShape(20.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
            GlowButton("Save", onClick = { vm.rememberPersonTyped(text); onClose() }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth())
            Text("Or just say: “Jarvis, remember this person…”", style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted))
        }
    }
}

/** Sign in (no password): Google if configured, else email → 6-digit code. */
@Composable
private fun SignInPane(vm: HomeViewModel) {
    Column(
        Modifier.fillMaxSize().background(Neo.Ground).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        PresenceOrb(state = OrbState.Idle)
        Spacer(Modifier.height(24.dp))
        Text("Welcome", style = Neo.display.copy(fontSize = 30.sp))
        Spacer(Modifier.height(8.dp))
        Text(
            if (vm.otpSent) "Enter the 6-digit code from your email." else "Sign in with your email — no password.",
            style = Neo.body.copy(fontSize = 15.sp, color = Neo.Muted), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (vm.googleSignInEnabled && !vm.otpSent) {
            val ctx = LocalContext.current
            GlowButton("Continue with Google", onClick = { vm.signInWithGoogle(ctx) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(
            value = vm.email, onValueChange = { vm.email = it }, singleLine = true, enabled = !vm.otpSent,
            label = { Text("Email") }, shape = RoundedCornerShape(20.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        if (vm.otpSent) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = vm.otpCode, onValueChange = { vm.otpCode = it }, singleLine = true,
                label = { Text("6-digit code") }, shape = RoundedCornerShape(20.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (!vm.otpSent) {
            GlowButton("Email me a code", onClick = vm::sendOtp, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        } else {
            GlowButton("Sign in", onClick = vm::verifyOtp, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            GhostButton("Resend code", onClick = vm::sendOtp, enabled = !vm.busy)
        }
        if (vm.devLoginEnabled) {
            Spacer(Modifier.height(10.dp))
            GhostButton("Sign in (dev)", onClick = vm::signIn, enabled = !vm.busy)
        }
        Spacer(Modifier.height(16.dp))
        Text(vm.status, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
    }
}

