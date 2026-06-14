package com.echo.app.ui.screens

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

        // ── Buttons map (decoded — recon/Glasses_Controls.md) ─────────────
        SectionTitle("Buttons")
        ButtonRow(Icons.Outlined.CameraAlt, "Front button", "Single press — take a photo")
        ButtonRow(Icons.Outlined.Mic, "BACK button — hold", "Start/stop a voice recording")
        ButtonRow(Icons.Outlined.Visibility, "BACK button — double-click", "AI gesture: Look & Ask (describe what you see)")

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
