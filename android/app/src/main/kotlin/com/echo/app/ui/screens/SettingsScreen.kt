package com.echo.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.BuildConfig
import com.echo.app.CompanionPrefs
import com.echo.app.ConnectedCompanionService
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.GlowButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoCard
import com.echo.app.ui.components.NeoTopBar
import com.echo.app.ui.components.SettingsGroup
import com.echo.app.ui.components.SettingsRow
import com.echo.app.ui.components.rememberGlassesAudioConnected

/**
 * Settings (redesign 2026-10): a header (you + your glasses), then short grouped rows — each shows its
 * state in a word and opens its detail one tap deeper. Nothing removed: Gmail & Calendar, notifications,
 * personality + memory editor, export/delete, voice engine, sync and the developer console all live here.
 */
@Composable
fun SettingsScreen(vm: HomeViewModel, onOpenDevConsole: () -> Unit, onOpenDevice: () -> Unit = {}, onHelp: () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf("main") }
    if (page != "main") BackHandler { page = "main" }
    when (page) {
        "gmail" -> GmailPage(vm) { page = "main" }
        "notifications" -> NotificationsPage(vm) { page = "main" }
        "personality" -> PersonalityPage(vm) { page = "main" }
        "data" -> DataPage(vm) { page = "main" }
        "advanced" -> AdvancedPage(vm, onOpenDevConsole, onOpen = { page = it }) { page = "main" }
        "audio" -> AudioKeepPage(vm) { page = "advanced" }
        "costs" -> AiCostsPage(vm) { page = "advanced" }
        else -> MainPage(vm, onOpen = { page = it }, onOpenDevice = onOpenDevice, onHelp = onHelp)
    }
}

@Composable
private fun MainPage(vm: HomeViewModel, onOpen: (String) -> Unit, onOpenDevice: () -> Unit, onHelp: () -> Unit) {
    val ctx = LocalContext.current
    val audio = rememberGlassesAudioConnected()
    val bleUp = vm.bleStatus.contains("listening", true) || vm.bleStatus.contains("glasses IP", true) || vm.bleStatus.contains("already connected", true)
    LaunchedEffect(vm.loggedIn) { if (vm.loggedIn) vm.refreshGoogleStatus() }
    var bgOn by remember { mutableStateOf(CompanionPrefs.isEnabled(ctx)) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().background(Neo.Ground).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = Neo.display.copy(fontSize = 28.sp), modifier = Modifier.padding(top = 16.dp))

        // You + glasses.
        NeoCard(hero = true, padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val who = vm.email.ifBlank { "You" }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neo.Cyan, Color(0xFF0B6E86)))), contentAlignment = Alignment.Center) {
                    Text(who.take(1).uppercase(), style = Neo.display.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Neo.OnCyan))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (vm.loggedIn) "Signed in" else "Not signed in", style = Neo.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                    if (vm.email.isNotBlank()) Text(vm.email, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted), maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable(onClick = onOpenDevice)) {
                    Text(vm.glassesBattery?.let { "$it%" } ?: if (bleUp || audio) "On" else "Off", style = Neo.display.copy(fontSize = 20.sp, color = if (bleUp || audio) Neo.Cyan else Neo.Amber))
                    MonoLabel("Glasses", size = 10)
                }
            }
        }

        SettingsGroup("Connections") {
            SettingsRow(Icons.Outlined.Visibility, "Glasses", value = if (bleUp || audio) "Connected" else "Not connected", valueColor = if (bleUp || audio) Neo.Cyan else Neo.Amber, onClick = onOpenDevice)
            val g = vm.google
            SettingsRow(
                Icons.Outlined.Email, "Gmail & Calendar", accent = Neo.Amber,
                value = when { g?.connected == true -> "Connected"; g?.needsReconnect == true -> "Reconnect"; else -> "Connect" },
                valueColor = if (g?.connected == true) Neo.Cyan else Neo.Amber, onClick = { onOpen("gmail") },
            )
            SettingsRow(Icons.Outlined.Notifications, "Notifications", value = if (vm.pushAvailable) "On" else "Not set up", onClick = { onOpen("notifications") })
            SettingsRow(Icons.Outlined.LocationOn, "Find my glasses", value = if (vm.glassesLocator.hasLocationPermission()) "On" else "Off", divider = false, onClick = onOpenDevice)
        }

        SettingsGroup("JARVIS") {
            SettingsRow(Icons.Outlined.Mic, "“Jarvis” wake word", checked = vm.handsFree, onCheckedChange = { vm.toggleHandsFree(it) })
            SettingsRow(
                Icons.Outlined.GraphicEq, "Listen with the app closed", sub = "Glasses buttons work in the background",
                checked = bgOn,
                onCheckedChange = { on ->
                    bgOn = on
                    CompanionPrefs.setEnabled(ctx, on)
                    if (on) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        ConnectedCompanionService.start(ctx)
                    } else ConnectedCompanionService.stop(ctx)
                },
            )
            SettingsRow(Icons.Outlined.AutoAwesome, "Personality & what it knows", divider = false, onClick = { onOpen("personality") })
        }

        SettingsGroup("Privacy & help") {
            SettingsRow(Icons.Outlined.Shield, "Your data — export or delete", onClick = { onOpen("data") })
            SettingsRow(Icons.AutoMirrored.Outlined.HelpOutline, "Help, tips & voice commands", divider = false, onClick = onHelp)
        }

        Text(
            "JARVIS ${BuildConfig.VERSION_NAME} · Advanced & developer tools ›",
            style = Neo.mono.copy(fontSize = 11.sp, letterSpacing = 0.sp, color = Neo.Faint),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen("advanced") }.padding(vertical = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar(title, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Explain(text: String) = Text(text, style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Neo.Muted))

@Composable
private fun GmailPage(vm: HomeViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.finishGoogleConnect(if (res.resultCode == android.app.Activity.RESULT_OK) com.echo.app.GoogleSignInHelper.codeFromConsentResult(ctx, res.data) else null)
    }
    LaunchedEffect(Unit) { vm.refreshGoogleStatus() }
    val g = vm.google
    val connected = g?.connected == true
    SubPage("Gmail & Calendar", onBack) {
        NeoCard(hero = true) {
            MonoLabel(if (connected) "Connected" else if (g?.needsReconnect == true) "Needs reconnecting" else "Not connected", color = if (connected) Neo.Cyan else Neo.Amber)
            if (connected && !g?.email.isNullOrBlank()) Text(g?.email.orEmpty(), style = Neo.body.copy(fontSize = 16.sp))
            Explain("JARVIS can check and add to your calendar, and write email drafts for you to send from Gmail. It never sends email itself, and always asks before adding anything to your calendar.")
        }
        if (!connected) {
            val ready = com.echo.app.GoogleSignInHelper.isConfigured && g?.serverReady != false
            GlowButton(if (vm.googleBusy) "Connecting…" else "Connect Gmail & Calendar", enabled = ready && !vm.googleBusy, modifier = Modifier.fillMaxWidth(),
                onClick = { vm.connectGoogle(ctx) { pi -> consent.launch(IntentSenderRequest.Builder(pi).build()) } })
            if (!ready) Explain("Not available yet — Google still needs to be set up for JARVIS.")
        } else {
            GhostButton(if (vm.googleBusy) "Working…" else "Disconnect", onClick = vm::disconnectGoogle, enabled = !vm.googleBusy, modifier = Modifier.fillMaxWidth(), color = Neo.Danger)
        }
        if (vm.googleMessage.isNotBlank()) Text(vm.googleMessage, style = Neo.body.copy(fontSize = 14.sp, color = Neo.Cyan))
        MonoLabel("Try saying")
        Explain("“What's on my calendar tomorrow?”\n“Add a site meeting on Tuesday at 10.”\n“Draft an email to Mr Ade about the cement delivery.”")
    }
}

@Composable
private fun NotificationsPage(vm: HomeViewModel, onBack: () -> Unit) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.sendTestPush() }
    SubPage("Notifications", onBack) {
        NeoCard(hero = true) {
            MonoLabel(if (vm.pushAvailable) "On" else "Not set up", color = if (vm.pushAvailable) Neo.Cyan else Neo.Amber)
            Explain("JARVIS tells you when something it's working on is ready — a meeting summary, long research — even with the app closed.")
        }
        GlowButton("Send a test notification", enabled = vm.pushAvailable, modifier = Modifier.fillMaxWidth(), onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.sendTestPush()
        })
        if (vm.pushMessage.isNotBlank()) Text(vm.pushMessage, style = Neo.body.copy(fontSize = 14.sp, color = Neo.Cyan))
    }
}

@Composable
private fun PersonalityPage(vm: HomeViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadProfile() }
    var soul by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf("") }
    LaunchedEffect(vm.profileSoul) { soul = vm.profileSoul }
    LaunchedEffect(vm.profileFacts) { facts = vm.profileFacts }
    SubPage("Personality & memory", onBack) {
        MonoLabel("Character")
        Explain("Who JARVIS is and how it acts for you — used in every answer.")
        OutlinedTextField(value = soul, onValueChange = { soul = it }, minLines = 4, maxLines = 12, shape = RoundedCornerShape(20.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth())
        MonoLabel("What JARVIS knows about you")
        Explain("Facts it has learned — edit anything that's wrong. It grows as you talk.")
        OutlinedTextField(value = facts, onValueChange = { facts = it }, minLines = 3, maxLines = 12, shape = RoundedCornerShape(20.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth())
        GlowButton(if (vm.profileBusy) "Saving…" else "Save", onClick = { vm.saveProfile(soul, facts) }, enabled = !vm.profileBusy, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DataPage(vm: HomeViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirmWipe by remember { mutableStateOf(false) }
    SubPage("Your data", onBack) {
        SettingsGroup(null) {
            SettingsRow(Icons.Outlined.FileDownload, "Export all my data", sub = "Your memories as a file you can keep", onClick = { if (!vm.busy) vm.exportData(ctx) })
            SettingsRow(Icons.Outlined.DeleteForever, "Delete everything", sub = "From this phone and the cloud", titleColor = Neo.Danger, accent = Neo.Danger, divider = false, onClick = { confirmWipe = true })
        }
        SettingsGroup(null) {
            SettingsRow(Icons.Outlined.Logout, "Sign out", divider = false, onClick = vm::signOut)
        }
        if (vm.status.isNotBlank()) Text(vm.status, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
    }
    if (confirmWipe) AlertDialog(
        onDismissRequest = { confirmWipe = false },
        containerColor = Neo.Card,
        title = { Text("Delete everything?", style = Neo.display.copy(fontSize = 20.sp)) },
        text = { Text("This permanently removes every memory, photo and recording from this phone and the cloud, and signs you out. It can't be undone.", style = Neo.body.copy(color = Neo.TextSoft)) },
        confirmButton = { TextButton(onClick = { confirmWipe = false; vm.deleteEverything() }) { Text("Delete everything", color = Neo.Danger) } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel", color = Neo.Text) } },
    )
}

private fun keepLabel(days: Int) = when (days) { 0 -> "Forever"; 365 -> "1 year"; else -> "$days days" }

/** Settings → Advanced → Keep meeting audio (M6): 30 / 90 / 365 days / forever. */
@Composable
private fun AudioKeepPage(vm: HomeViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadAudioKeepDays() }
    SubPage("Keep meeting audio", onBack) {
        Explain(
            "Meeting audio is kept in your private cloud storage for this long after the meeting, then deleted to save space. " +
                "The summary, transcript and memory are kept forever. Starred meetings keep their audio forever. " +
                "The phone keeps its own copy for a week after it's backed up.",
        )
        SettingsGroup(null) {
            listOf(30, 90, 365, 0).forEachIndexed { i, d ->
                SettingsRow(
                    null, keepLabel(d) + if (d == 90) " (recommended)" else "",
                    value = if (vm.audioKeepDays == d) "✓" else null, valueColor = Neo.Cyan,
                    divider = i < 3, onClick = { vm.setAudioKeepDays(d) },
                )
            }
        }
        if (vm.audioKeepDays == null) Explain("Couldn't load the setting — you need to be online.")
    }
}

/** Settings → Advanced → AI models & cost (M7): this month's AI use per job and which models are used. */
@Composable
private fun AiCostsPage(vm: HomeViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadAiCosts() }
    val c = vm.aiCosts
    SubPage("AI models & cost", onBack) {
        if (c == null) { Explain("Loading… (you need to be online)"); return@SubPage }
        val jobs = c.optJSONObject("jobs") ?: org.json.JSONObject()
        Text("$" + "%.2f".format(c.optDouble("total_usd", 0.0)) + " this month", style = Neo.display.copy(fontSize = 28.sp))
        Explain("Estimated from the tokens each call used (meetings so far; other features join later). Bills come from each provider.")
        val names = mapOf("meeting_stt" to "Meeting transcripts", "meeting_summary" to "Meeting summaries", "meeting_ask" to "Questions about meetings")
        SettingsGroup("This month") {
            val keys = jobs.keys().asSequence().toList()
            if (keys.isEmpty()) SettingsRow(null, "No AI calls logged yet this month", divider = false)
            keys.forEachIndexed { i, k ->
                val j = jobs.getJSONObject(k)
                val models = j.optJSONObject("models")?.keys()?.asSequence()?.joinToString(", ").orEmpty()
                SettingsRow(
                    null, names[k] ?: k,
                    sub = "${j.optInt("calls")} calls" + (if (j.optInt("failed") > 0) " · ${j.optInt("failed")} failed" else "") + (if (models.isNotBlank()) " · $models" else ""),
                    value = "$" + "%.3f".format(j.optDouble("usd", 0.0)), divider = i < keys.size - 1,
                )
            }
        }
        val routes = c.optJSONArray("routes") ?: org.json.JSONArray()
        SettingsGroup("Models used, in order") {
            for (i in 0 until routes.length()) {
                val r = routes.getJSONObject(i)
                val list = r.optJSONArray("routes") ?: org.json.JSONArray()
                val chain = (0 until list.length()).map { list.getJSONObject(it) }.joinToString(" → ") { e ->
                    e.optString("provider") + "/" + e.optString("model").let { if (it == "default") "Claude (default)" else it }
                }
                SettingsRow(null, names[r.optString("job")] ?: r.optString("job"), sub = chain, divider = i < routes.length() - 1)
            }
        }
        Explain(
            if (c.optBoolean("openrouter")) "OpenRouter is connected — cheaper models can be switched on per job."
            else "Cheaper models (DeepSeek, Kimi, Qwen… via OpenRouter) switch on once the OpenRouter key is added on the server.",
        )
    }
}

@Composable
private fun AdvancedPage(vm: HomeViewModel, onOpenDevConsole: () -> Unit, onOpen: (String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadAudioKeepDays() }
    SubPage("Advanced", onBack) {
        SettingsGroup("Meetings") {
            SettingsRow(
                Icons.Outlined.GraphicEq, "Keep meeting audio",
                sub = "The notes and transcript are always kept",
                value = vm.audioKeepDays?.let { keepLabel(it) } ?: "…", onClick = { onOpen("audio") },
            )
            SettingsRow(Icons.Outlined.AutoAwesome, "AI models & cost", sub = "Which AI does each job, and this month's cost", divider = false, onClick = { onOpen("costs") })
        }
        SettingsGroup(null) {
            SettingsRow(Icons.Outlined.RecordVoiceOver, "Voice recognition", sub = vm.voiceEngineSummary)
            SettingsRow(Icons.Outlined.WifiOff, "Offline", sub = "Answers from memory on the phone when there's no connection")
            SettingsRow(
                Icons.Outlined.Sync, "Sync from glasses",
                sub = if (vm.pendingSync > 0) "${vm.pendingSync} memories on the phone — they sync when online" else "All memories synced",
                value = if (vm.syncing) "Syncing…" else null, onClick = { if (!vm.syncing) vm.syncGlasses() },
            )
            SettingsRow(Icons.Outlined.Code, "Developer console", divider = false, onClick = onOpenDevConsole)
        }
        Text("JARVIS ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}", style = Neo.mono.copy(fontSize = 11.sp, letterSpacing = 0.sp, color = Neo.Faint), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

