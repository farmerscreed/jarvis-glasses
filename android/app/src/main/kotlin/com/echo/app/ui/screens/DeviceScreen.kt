package com.echo.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.GlowButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoCard
import com.echo.app.ui.components.SettingsGroup
import com.echo.app.ui.components.SettingsRow
import com.echo.app.ui.components.StatusDot
import com.echo.app.ui.components.rememberGlassesAudioConnected

/**
 * Glasses (redesign 2026-10): a status hero (battery %, Bluetooth + audio link), Find my glasses (one-button
 * setup, then when/where + Show on map), the decoded buttons map, and Fix connection.
 */
@Composable
fun DeviceScreen(vm: HomeViewModel) {
    val audioConnected = rememberGlassesAudioConnected()
    val bleUp = vm.bleStatus.contains("listening", true) || vm.bleStatus.contains("connected", true) || vm.bleStatus.contains("IP", true)
    val battery = vm.glassesBattery
    val up = bleUp || audioConnected

    Column(Modifier.fillMaxSize().background(Neo.Ground).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NeoCard(hero = true, padding = 22.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoLabel(if (up) "Connected" else "Not connected", color = if (up) Neo.Cyan else Neo.Amber)
                    Text(battery?.let { "$it%" } ?: "—", style = Neo.display.copy(fontSize = 48.sp, fontWeight = FontWeight.Bold, color = when {
                        battery == null -> Neo.Muted
                        battery <= 20 -> Neo.Danger
                        else -> androidx.compose.ui.graphics.Color.White
                    }))
                    Text(if (battery == null) "Battery unknown — turn the glasses on" else "Glasses battery", style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))
                }
                BatteryRing(battery)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusDot(if (bleUp) "Bluetooth on" else "Bluetooth off", if (bleUp) Neo.Cyan else Neo.Amber)
                StatusDot(if (audioConnected) "Sound in glasses" else "Sound on phone", if (audioConnected) Neo.Cyan else Neo.Amber)
            }
        }

        FindGlassesCard(vm, bleUp)

        // Video (Phase 3 · V1): length + a battery note — the glasses won't film at 15 % or less.
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadVideoLength() }
        var pickLength by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        SettingsGroup("Video") {
            SettingsRow(
                Icons.Outlined.Videocam, "Video length",
                sub = "How long a glasses video runs before it stops by itself (site walks have no limit)",
                value = com.echo.app.video.VideoPrefs.label(vm.videoLength), onClick = { pickLength = !pickLength },
                divider = pickLength || (battery != null && battery <= 15),
            )
            if (pickLength) {
                val choices = com.echo.device.ble.GlassesBleManager.VIDEO_LENGTHS
                choices.forEachIndexed { i, s ->
                    SettingsRow(
                        null, com.echo.app.video.VideoPrefs.label(s) + if (s == com.echo.app.video.VideoPrefs.DEFAULT_LENGTH) " (recommended)" else "",
                        value = if (vm.videoLength == s) "✓" else null, valueColor = Neo.Cyan,
                        divider = i < choices.size - 1 || (battery != null && battery <= 15),
                        onClick = { vm.chooseVideoLength(s); pickLength = false },
                    )
                }
            }
            if (battery != null && battery <= 15) {
                SettingsRow(null, "Battery too low to record video ($battery%)", sub = "The glasses only film above 15 %. Charge them.", titleColor = Neo.Amber, divider = false)
            }
        }

        SettingsGroup("Buttons on your glasses") {
            SettingsRow(Icons.Outlined.CameraAlt, "Front button", sub = "Press once — take a photo")
            SettingsRow(Icons.Outlined.Videocam, "Front button — double-click", sub = "Start / stop a video (or say “Jarvis, record a video”)")
            SettingsRow(Icons.Outlined.Mic, "Back button — hold", sub = "Record a voice note")
            SettingsRow(Icons.Outlined.GraphicEq, "Back button — double-click", sub = "Listen & translate on / off", divider = false)
        }

        GlowButton(if (vm.syncing) "Reconnecting…" else "Fix connection", onClick = vm::syncGlasses, enabled = !vm.busy && !vm.syncing, icon = Icons.Outlined.Sync, modifier = Modifier.fillMaxWidth())
        Text("Reconnects Bluetooth + Wi-Fi and pulls anything new off the glasses.", style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted))
        Text(vm.bleStatus, style = Neo.mono.copy(fontSize = 11.sp, letterSpacing = 0.sp, color = Neo.Faint))
        Spacer(Modifier.height(20.dp))
    }
}

/** A simple ring that fills with the battery level. */
@Composable
private fun BatteryRing(level: Int?) {
    val pct = (level ?: 0).coerceIn(0, 100) / 100f
    val color = when {
        level == null -> Neo.AccentBorder
        level <= 20 -> Neo.Danger
        else -> Neo.Cyan
    }
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(84.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(Neo.Border, 0f, 360f, false, style = stroke)
            drawArc(color, -90f, 360f * pct, false, style = stroke)
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    }
}

/**
 * Find my glasses: not set up → one button that asks for location, then "Allow all the time" (so the
 * spot is noted even with the app closed). Set up → when/where they were last connected + Show on map.
 */
@Composable
private fun FindGlassesCard(vm: HomeViewModel, bleUp: Boolean) {
    val ctx = LocalContext.current
    val locator = vm.glassesLocator
    var refresh by remember { mutableIntStateOf(0) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh++
        if (res.values.any { it } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !locator.hasBackgroundPermission()) {
            background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    LaunchedEffect(Unit) { refresh++ }
    val hasLoc = remember(refresh) { locator.hasLocationPermission() }
    val hasBg = remember(refresh) { locator.hasBackgroundPermission() }
    val last = remember(refresh, bleUp) { locator.lastSeen() }

    NeoCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Neo.Cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = Neo.Cyan, modifier = Modifier.size(18.dp))
            }
            Text("Find my glasses", style = Neo.display.copy(fontSize = 17.sp))
        }
        if (!hasLoc) {
            Text("When your glasses disconnect, JARVIS notes where your phone is — then ask “Jarvis, where are my glasses?”",
                style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Neo.Muted))
            GlowButton("Turn on", onClick = { foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                when {
                    bleUp -> "Connected now — they're with you."
                    last == null -> "No spot saved yet — it's noted the next time they disconnect."
                    else -> "Last connected ${agoText(last.at)}" + (if (last.place.isNotBlank()) ", near ${last.place}" else "") + "."
                },
                style = Neo.body.copy(fontSize = 16.sp, lineHeight = 23.sp),
            )
            if (last != null) GhostButton("Show on map", onClick = { runCatching { ctx.startActivity(locator.mapIntent(last)) } }, icon = Icons.Outlined.Map, modifier = Modifier.fillMaxWidth(), color = Neo.CyanPale)
            if (!hasBg) {
                Text("To note the spot while the app is closed, allow location “All the time”.", style = Neo.body.copy(fontSize = 13.sp, color = Neo.Amber))
                GhostButton("Allow all the time", onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, height = 44.dp)
            }
        }
    }
}

private fun agoText(at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        mins < 2 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 24 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} days ago"
    }
}
