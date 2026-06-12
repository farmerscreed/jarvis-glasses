package com.echo.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.GlowFab
import com.echo.app.ui.components.JarvisPrimaryButton
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.NeutralChip
import com.echo.app.ui.components.OrbState
import com.echo.app.ui.components.PresenceOrb
import com.echo.app.ui.components.RoundIconButton
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.components.rememberGlassesAudioConnected
import com.echo.app.ui.components.StatusLabel
import com.echo.app.ui.components.TranscriptBubble
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/** The agreed mapping from real assistant state to the orb's cognitive state. */
private fun orbStateOf(vm: HomeViewModel): OrbState = when {
    !vm.online -> OrbState.OffGrid
    vm.status.contains("istening") -> OrbState.Listening
    vm.status.startsWith("Transcribing") || vm.status.startsWith("Thinking") ||
        vm.status.startsWith("Signing") || vm.status.startsWith("Verifying") -> OrbState.Thinking
    vm.status.startsWith("Speaking") -> OrbState.Speaking
    else -> OrbState.Idle
}

/**
 * Home — the Live console (live_console_online / _off_grid / _disconnected / _type_focus).
 * All variants are one composable driven by real HomeViewModel state; the keyboard variant is
 * the only local UI state (input mode toggle).
 */
@OptIn(ExperimentalLayoutApi::class) // FlowRow
@Composable
fun LiveConsoleScreen(vm: HomeViewModel) {
    if (!vm.loggedIn) {
        SignInPane(vm)
        return
    }

    var typeMode by remember { mutableStateOf(false) }
    // The link that matters for voice is BT AUDIO (SCO), not the BLE control link: when the
    // glasses hold the audio path, the mic records from THEM and answers play in THEIR speaker.
    val glassesConnected = rememberGlassesAudioConnected()
    val amber = JarvisTheme.colors.presenceAmber
    val cyan = MaterialTheme.colorScheme.primaryContainer

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(JarvisSpacing.md))
        PresenceOrb(state = orbStateOf(vm))
        Spacer(Modifier.height(JarvisSpacing.lg))

        // Connection chips — glasses link + cloud tier, from real BLE/governor state.
        // FlowRow: the off-grid design stacks the chips when they don't fit on one line.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
        ) {
            if (glassesConnected) {
                StatusChip("Glasses · Connected", accent = cyan)
            } else {
                StatusChip("Glasses · Not connected", accent = amber)
            }
            when {
                !vm.online -> StatusChip("Cloud · Off-grid", accent = amber)
                vm.tier == "lean" -> StatusChip("Cloud · Slow", accent = amber)
                else -> StatusChip("Cloud · Full", accent = cyan)
            }
        }

        if (!vm.online) {
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(
                "Saving everything on your phone — will sync later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(JarvisSpacing.lg))

        // Transcript card: last turn (question + answer) or the live status as an empty-state hint.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(
                modifier = Modifier.padding(JarvisSpacing.md),
                verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
            ) {
                if (vm.answer.isEmpty() && !vm.busy) {
                    Text(
                        if (glassesConnected) "Say “Jarvis”, press the glasses button, or tap the mic." else "Awaiting local connection…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = JarvisSpacing.lg),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    if (vm.question.isNotBlank()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TranscriptBubble(vm.question, fromUser = true)
                        }
                    }
                    if (!vm.online) {
                        StatusLabel("Local processing", accent = amber)
                    }
                    if (vm.answer.isNotEmpty()) {
                        Text(
                            "JARVIS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        )
                        TranscriptBubble(vm.answer, fromUser = false)
                    }
                }
            }
        }

        Spacer(Modifier.height(JarvisSpacing.sm))

        // The engine's status line, ALWAYS visible — failures like "Didn't catch that" or
        // "Error: …" must never vanish the instant busy flips off.
        Text(
            vm.status + if (glassesConnected) "  ·  audio in glasses" else "",
            style = JarvisTheme.dataMono,
            color = if (vm.status.startsWith("Error")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(JarvisSpacing.md))

        // Sync + input-route chips, from real outbox/BLE state. The mic-route chip only appears
        // when the glasses are NOT the mic (per the disconnected design); otherwise the caption
        // above the mic carries it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
        ) {
            if (vm.pendingSync > 0) {
                StatusChip("${vm.pendingSync} on phone", accent = amber, icon = Icons.Outlined.Sync)
            }
            if (!glassesConnected) {
                StatusChip("MIC: PHONE", accent = cyan, icon = Icons.Outlined.Mic)
            }
        }

        Spacer(Modifier.height(JarvisSpacing.lg))

        if (typeMode) {
            // live_console_type_focus: pill input, cyan focus, send → the existing ask() path.
            OutlinedTextField(
                value = vm.question,
                onValueChange = { vm.question = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = CircleShape,
                placeholder = {
                    Text("Type to Jarvis…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    IconButton(onClick = { if (!vm.busy) vm.ask() }) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "send", tint = cyan)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedBorderColor = cyan,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
            Spacer(Modifier.height(JarvisSpacing.sm))
            NeutralChip("INPUT: KEYBOARD", icon = Icons.Outlined.Keyboard)
            Spacer(Modifier.height(JarvisSpacing.sm))
            JarvisSecondaryButton("Use voice", onClick = { typeMode = false })
        } else {
            if (glassesConnected) {
                Text(
                    "MIC: GLASSES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(JarvisSpacing.sm))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
            ) {
                GlowFab(
                    icon = Icons.Outlined.Mic,
                    contentDescription = "talk to Jarvis",
                    onClick = vm::talk,
                    enabled = !vm.busy,
                    accent = if (vm.online) cyan else amber,
                )
                RoundIconButton(
                    icon = Icons.Outlined.Keyboard,
                    contentDescription = "type instead",
                    onClick = { typeMode = true },
                )
            }
            if (!vm.online) {
                Spacer(Modifier.height(JarvisSpacing.sm))
                Text(
                    "TYPE INSTEAD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(JarvisSpacing.lg))
    }
}

/** Sign-in gate styled after onboarding_sign_in: orb + email → 6-digit code, on the console. */
@Composable
private fun SignInPane(vm: HomeViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(JarvisSpacing.xl))
        PresenceOrb(state = OrbState.Idle)
        Spacer(Modifier.height(JarvisSpacing.lg))
        Text("Welcome", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(JarvisSpacing.sm))
        Text(
            if (vm.otpSent) "Enter the 6-digit code from your email." else "Sign in with your email — no password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(JarvisSpacing.lg))
        OutlinedTextField(
            value = vm.email,
            onValueChange = { vm.email = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !vm.otpSent,
            label = { Text("Email") },
        )
        if (vm.otpSent) {
            Spacer(Modifier.height(JarvisSpacing.sm))
            OutlinedTextField(
                value = vm.otpCode,
                onValueChange = { vm.otpCode = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("6-digit code") },
            )
        }
        Spacer(Modifier.height(JarvisSpacing.md))
        if (!vm.otpSent) {
            JarvisPrimaryButton("Email me a code", onClick = vm::sendOtp, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
        } else {
            JarvisPrimaryButton("Sign in", onClick = vm::verifyOtp, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(JarvisSpacing.sm))
            JarvisSecondaryButton("Resend code", onClick = vm::sendOtp, enabled = !vm.busy)
        }
        if (vm.devLoginEnabled) {
            Spacer(Modifier.height(JarvisSpacing.sm))
            JarvisSecondaryButton("Sign in (dev)", onClick = vm::signIn, enabled = !vm.busy)
        }
        Spacer(Modifier.height(JarvisSpacing.md))
        Text(
            vm.status,
            style = JarvisTheme.dataMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(JarvisSpacing.lg))
    }
}
