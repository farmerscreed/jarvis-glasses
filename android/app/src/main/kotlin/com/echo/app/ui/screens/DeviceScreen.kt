package com.echo.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.echo.app.ui.components.JarvisSecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisPrimaryButton
import com.echo.app.ui.components.rememberGlassesAudioConnected
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/**
 * Device screen (from the Stitch `device_jarvis_glasses` design): glasses connection + the decoded
 * **battery %** (BLE-pushed), the decoded buttons map (recon), and a "Fix connection" action.
 * Read-only over the engine — surfaces state the ViewModel already exposes.
 */
@Composable
fun DeviceScreen(vm: HomeViewModel) {
    val audioConnected = rememberGlassesAudioConnected()
    val bleUp = vm.bleStatus.contains("listening", true) || vm.bleStatus.contains("connected", true) ||
        vm.bleStatus.contains("IP", true)
    val battery = vm.glassesBattery

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        // ── Status + battery ──────────────────────────────────────────────
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(JarvisSpacing.lg), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    Icon(
                        Icons.Outlined.Bluetooth,
                        contentDescription = null,
                        tint = if (bleUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        when {
                            bleUp && audioConnected -> "Connected · audio in glasses"
                            bleUp -> "Connected over Bluetooth"
                            else -> "Not connected"
                        },
                        style = JarvisTheme.dataMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    Icon(
                        Icons.Outlined.BatteryFull,
                        contentDescription = "battery",
                        tint = when {
                            battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            battery <= 20 -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        battery?.let { "$it%" } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (battery == null) "battery unknown (turn the glasses on)" else "glasses battery",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(vm.bleStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── Find my glasses ───────────────────────────────────────────────
        FindGlassesCard(vm, bleUp)

        // ── Buttons map (decoded — recon/Glasses_Controls.md) ─────────────
        SectionTitle("Buttons")
        ButtonRow(Icons.Outlined.CameraAlt, "Front button", "Single press — take a photo")
        ButtonRow(Icons.Outlined.Mic, "BACK button — hold", "Start/stop a voice recording")
        ButtonRow(Icons.Outlined.Visibility, "BACK button — double-click", "Listen & translate on / off")

        Spacer(Modifier.height(JarvisSpacing.sm))

        // ── Fix connection ────────────────────────────────────────────────
        JarvisPrimaryButton(
            text = if (vm.syncing) "Reconnecting…" else "Fix connection",
            onClick = vm::syncGlasses,
            enabled = !vm.busy && !vm.syncing,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Re-establishes the Bluetooth + Wi-Fi Direct link and pulls anything new off the glasses.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Find my glasses: one plain card. Not set up → one button that asks for location, then "Allow all the
 * time" (so the spot is noted even with the app closed). Set up → when/where they were last connected
 * and a big "Show on map" button.
 */
@Composable
private fun FindGlassesCard(vm: HomeViewModel, bleUp: Boolean) {
    val ctx = LocalContext.current
    val locator = vm.glassesLocator
    var refresh by remember { mutableIntStateOf(0) }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh++
        // Step 2 (Android 11+ shows it as a separate screen): "Allow all the time".
        if (res.values.any { it } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !locator.hasBackgroundPermission()) {
            background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    // Re-read when the screen comes back (e.g. from the permission settings page).
    LaunchedEffect(Unit) { refresh++ }
    val hasLoc = remember(refresh) { locator.hasLocationPermission() }
    val hasBg = remember(refresh) { locator.hasBackgroundPermission() }
    val last = remember(refresh, bleUp) { locator.lastSeen() }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(JarvisSpacing.lg), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Find my glasses", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            when {
                !hasLoc -> {
                    Text(
                        "When your glasses disconnect, JARVIS notes where your phone is — so you can find them later. " +
                            "Then just ask “Jarvis, where are my glasses?”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    JarvisPrimaryButton(
                        "Turn on Find my glasses",
                        onClick = { foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    Text(
                        when {
                            bleUp -> "Connected now — they're with you."
                            last == null -> "No spot saved yet. It's noted the next time the glasses disconnect."
                            else -> "Last connected ${agoText(last.at)}" + (if (last.place.isNotBlank()) ", near ${last.place}" else "") + "."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (last != null) {
                        JarvisPrimaryButton(
                            "Show on map",
                            onClick = { runCatching { ctx.startActivity(locator.mapIntent(last)) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (!hasBg) {
                        Text(
                            "To note the spot while the app is closed, allow location “All the time”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        JarvisSecondaryButton("Allow all the time", onClick = { background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) })
                    }
                }
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = JarvisTheme.dataMono,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ButtonRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(JarvisSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
