package com.echo.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisPrimaryButton
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.OrbState
import com.echo.app.ui.components.PresenceOrb
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.components.rememberGlassesAudioConnected
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

private const val TOTAL_STEPS = 6

/**
 * First-run Companion Setup wizard (onboarding_* designs): Welcome → Sign In → Permissions →
 * Finding Glasses → Hardware Check → Wake Word. Wired to the real engine — the sign-in step uses
 * the actual email-OTP flow, Permissions requests the real runtime permissions, and the glasses
 * steps read the live BT-audio signal. Completes into the Live console.
 */
@Composable
fun OnboardingScreen(vm: HomeViewModel, onFinish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val back: () -> Unit = { if (step > 0) step -= 1 }
    BackHandler(enabled = step > 0) { back() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = JarvisSpacing.sm)
                    .height(56.dp),
            ) {
                if (step > 0) {
                    IconButton(onClick = back, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text(
                    "Companion Setup",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            AnimatedContent(targetState = step, label = "onboardingStep", modifier = Modifier.weight(1f)) { s ->
                when (s) {
                    0 -> WelcomeStep(onNext = { step = 1 })
                    1 -> SignInStep(vm, onNext = { step = 2 })
                    2 -> PermissionsStep(onNext = { step = 3 })
                    3 -> FindingGlassesStep(onNext = { step = 4 })
                    4 -> HardwareCheckStep(vm, onNext = { step = 5 })
                    else -> WakeWordStep(vm, onFinish = onFinish)
                }
            }
        }
    }
}

@Composable
private fun StepScaffold(
    stepIndex: Int,
    orb: OrbState,
    title: String,
    subtitle: String?,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(JarvisSpacing.xl))
        if (stepIndex > 0) {
            Text(
                "STEP $stepIndex / $TOTAL_STEPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(JarvisSpacing.md))
        }
        PresenceOrb(state = orb)
        Spacer(Modifier.height(JarvisSpacing.lg))
        Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(JarvisSpacing.xl))
        content()
        Spacer(Modifier.height(JarvisSpacing.xl))
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    StepScaffold(0, OrbState.Idle, "Welcome to JARVIS", "Your intelligent companion. Let's get your glasses paired and ready.") {
        JarvisPrimaryButton("Get Started", onClick = onNext, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SignInStep(vm: HomeViewModel, onNext: () -> Unit) {
    StepScaffold(
        1, OrbState.Idle, "Sign In",
        if (vm.otpSent) "Enter the 6-digit code from your email." else "Sign in with your email — no password needed.",
    ) {
        if (vm.loggedIn) {
            StatusChip("Signed in", accent = MaterialTheme.colorScheme.primaryContainer)
            Spacer(Modifier.height(JarvisSpacing.md))
            JarvisPrimaryButton("Next", onClick = onNext, modifier = Modifier.fillMaxWidth())
            return@StepScaffold
        }
        if (vm.googleSignInEnabled) {
            val ctx = LocalContext.current
            JarvisPrimaryButton("Continue with Google", onClick = { vm.signInWithGoogle(ctx) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text("or", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(JarvisSpacing.sm))
        }
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
        Spacer(Modifier.height(JarvisSpacing.sm))
        Text(vm.status, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PermissionsStep(onNext: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onNext() }
    StepScaffold(2, OrbState.Listening, "Permission to Assist", "We only use these to bridge your glasses to your phone.") {
        PermissionRow(Icons.Outlined.Mic, "Microphone", "To hear your requests and answer in your ear.")
        Spacer(Modifier.height(JarvisSpacing.md))
        PermissionRow(Icons.Outlined.Wifi, "Nearby devices", "To securely connect to your JARVIS glasses.")
        Spacer(Modifier.height(JarvisSpacing.lg))
        JarvisPrimaryButton(
            "Enable Access",
            onClick = {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.NEARBY_WIFI_DEVICES,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    androidx.compose.material3.Card(
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(JarvisSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primaryContainer)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FindingGlassesStep(onNext: () -> Unit) {
    val connected = rememberGlassesAudioConnected()
    StepScaffold(
        3,
        if (connected) OrbState.Speaking else OrbState.Thinking,
        if (connected) "Glasses Found" else "Searching for Glasses",
        if (connected) "Your AIMB-G2 glasses are connected." else "Make sure your glasses are powered on and nearby.",
    ) {
        StatusChip(
            if (connected) "Connected" else "Scanning…",
            accent = if (connected) MaterialTheme.colorScheme.primaryContainer else JarvisTheme.colors.presenceAmber,
        )
        Spacer(Modifier.height(JarvisSpacing.lg))
        if (connected) {
            JarvisPrimaryButton("Next", onClick = onNext, modifier = Modifier.fillMaxWidth())
        } else {
            JarvisSecondaryButton("Skip for now", onClick = onNext)
        }
    }
}

@Composable
private fun HardwareCheckStep(vm: HomeViewModel, onNext: () -> Unit) {
    StepScaffold(4, OrbState.Idle, "Hardware Check", "A quick check that your glasses are talking to the app.") {
        JarvisSecondaryButton("Test capture (press temple)", onClick = { vm.capturePhoto() })
        Spacer(Modifier.height(JarvisSpacing.md))
        Text(vm.bleStatus, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(JarvisSpacing.lg))
        JarvisPrimaryButton("All Good", onClick = onNext, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WakeWordStep(vm: HomeViewModel, onFinish: () -> Unit) {
    val context = LocalContext.current
    StepScaffold(6, OrbState.Listening, "Now, just say…", "“Hey Jarvis”") {
        StatusChip("Voice profile active", accent = MaterialTheme.colorScheme.primaryContainer)
        Spacer(Modifier.height(JarvisSpacing.lg))
        JarvisPrimaryButton(
            "Go to Live Console",
            onClick = {
                if (!vm.handsFree) vm.toggleHandsFree(true)
                OnboardingPrefs.setDone(context)
                onFinish()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(JarvisSpacing.sm))
        JarvisSecondaryButton("Skip", onClick = { OnboardingPrefs.setDone(context); onFinish() })
    }
}
