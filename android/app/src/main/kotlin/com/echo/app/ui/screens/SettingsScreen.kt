package com.echo.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.echo.app.BuildConfig
import com.echo.app.CompanionPrefs
import com.echo.app.ConnectedCompanionService
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.SectionLabel
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.components.StatusLabel
import com.echo.app.ui.components.rememberGlassesAudioConnected
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/**
 * Settings (settings_system_console), wired to what exists: account/sign-out, the Jarvis Lite
 * offline floor status, the wake-word toggle, live device/cloud state + manual sync, and the
 * developer console. Export/delete-my-data are Phase F (GDPR) — intentionally absent until real.
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
fun SettingsScreen(vm: HomeViewModel, onOpenDevConsole: () -> Unit, onOpenDevice: () -> Unit = {}) {
    val cyan = MaterialTheme.colorScheme.primaryContainer
    val amber = JarvisTheme.colors.presenceAmber
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)

        SectionLabel("Account")
        SettingsCard {
            Text(
                if (vm.loggedIn) "Signed in" + (vm.email.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else "Not signed in",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (vm.loggedIn) {
                Spacer(Modifier.height(JarvisSpacing.sm))
                JarvisSecondaryButton("Sign out", onClick = vm::signOut, enabled = !vm.busy)
            }
        }

        SectionLabel("AI & Intelligence")
        SettingsCard {
            Text("Offline floor", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(JarvisSpacing.xs))
            Text(
                "Jarvis Lite answers from on-device memory when there's no connection. The full Offline Pack (local LLM) is a future optional download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(JarvisSpacing.sm))
            StatusLabel("Local fallback ready", accent = amber)
        }

        if (vm.loggedIn) {
            SectionLabel("JARVIS's memory")
            SettingsCard {
                LaunchedEffect(Unit) { vm.loadProfile() }
                var soul by remember { mutableStateOf("") }
                var facts by remember { mutableStateOf("") }
                LaunchedEffect(vm.profileSoul) { soul = vm.profileSoul }
                LaunchedEffect(vm.profileFacts) { facts = vm.profileFacts }

                Text("Character (SOUL)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Who JARVIS is and how it acts for you — injected into every answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(JarvisSpacing.xs))
                OutlinedTextField(
                    value = soul,
                    onValueChange = { soul = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 12,
                )
                Spacer(Modifier.height(JarvisSpacing.md))
                Text("What JARVIS knows about you", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Curated facts it has learned (and you can edit). Grows as you talk.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(JarvisSpacing.xs))
                OutlinedTextField(
                    value = facts,
                    onValueChange = { facts = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 12,
                )
                Spacer(Modifier.height(JarvisSpacing.sm))
                JarvisSecondaryButton(
                    if (vm.profileBusy) "Saving…" else "Save memory",
                    onClick = { vm.saveProfile(soul, facts) },
                    enabled = !vm.profileBusy,
                )
            }
        }

        SectionLabel("Control")
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Voice wake word", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("“Jarvis”", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = vm.handsFree,
                    onCheckedChange = { vm.toggleHandsFree(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = cyan),
                )
            }
        }
        SettingsCard {
            val ctx = LocalContext.current
            var bgOn by remember { mutableStateOf(CompanionPrefs.isEnabled(ctx)) }
            val notifLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* runs regardless; the service shows even if the notification is suppressed */ }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep listening in the background", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "React to glasses captures with the app closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = bgOn,
                    onCheckedChange = { on ->
                        bgOn = on
                        CompanionPrefs.setEnabled(ctx, on)
                        if (on) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            ConnectedCompanionService.start(ctx)
                        } else {
                            ConnectedCompanionService.stop(ctx)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = cyan),
                )
            }
        }

        SectionLabel("Device & Cloud")
        SettingsCard {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
            ) {
                val glassesConnected = rememberGlassesAudioConnected()
                StatusChip(
                    if (glassesConnected) "Glasses · Connected" else "Glasses · Not connected",
                    accent = if (glassesConnected) cyan else amber,
                )
                when {
                    !vm.online -> StatusChip("Cloud · Off-grid", accent = amber)
                    vm.tier == "lean" -> StatusChip("Cloud · Slow", accent = amber)
                    else -> StatusChip("Cloud · Full", accent = cyan)
                }
            }
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(
                if (vm.pendingSync > 0) "${vm.pendingSync} memories on phone — will sync when online." else "All memories synced.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(JarvisSpacing.sm))
            JarvisSecondaryButton("Sync from glasses", onClick = vm::syncGlasses, enabled = !vm.busy)
        }

        SectionLabel("Privacy & Data")
        SettingsCard {
            val ctx = LocalContext.current
            var confirmWipe by remember { mutableStateOf(false) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !vm.busy) { vm.exportData(ctx) },
            ) {
                Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(JarvisSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text("Export all my data", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text("Save your memories as a JSON file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(JarvisSpacing.md))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !vm.busy) { confirmWipe = true },
            ) {
                Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(JarvisSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text("Delete everything", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Text("Remove all memories from this phone and the cloud.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (confirmWipe) {
                AlertDialog(
                    onDismissRequest = { confirmWipe = false },
                    title = { Text("Delete everything?") },
                    text = { Text("This permanently removes every memory, photo, and recording from this phone and the cloud, and signs you out. It can't be undone.") },
                    confirmButton = { TextButton(onClick = { confirmWipe = false; vm.deleteEverything() }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
                    dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
                )
            }
        }

        SectionLabel("Device")
        SettingsCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDevice),
            ) {
                Text(
                    "Glasses & device" + (vm.glassesBattery?.let { "  ·  $it%" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionLabel("Advanced")
        SettingsCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDevConsole),
            ) {
                Text(
                    "Developer console",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionLabel("About")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                "JARVIS ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}",
                style = JarvisTheme.dataMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(JarvisSpacing.lg))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(JarvisSpacing.md)) { content() }
    }
}
