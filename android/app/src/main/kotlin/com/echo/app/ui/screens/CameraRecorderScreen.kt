package com.echo.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.echo.app.HomeViewModel
import com.echo.app.meeting.MeetingRecorder
import com.echo.app.ui.components.Neo
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RED = Color(0xFFFF5C5C)

/**
 * Phase 3 · V5 — record a video (or a site walk) with the PHONE camera when the glasses are charging or a steadier
 * shot is wanted. Big red button, flip camera, running time. The screen stays on while it records (Android won't let
 * the camera run with the screen off). After Stop the file goes through the same pipeline as a glasses video.
 */
@SuppressLint("MissingPermission")
@Composable
fun CameraRecorderScreen(vm: HomeViewModel, walk: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var hasPerms by remember { mutableStateOf(granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasPerms = r.values.all { it } || (granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(Unit) { if (!hasPerms) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

    var back by remember { mutableStateOf(true) }               // back camera by default
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf("") }
    val capture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build(),
        )
    }
    val previewView = remember { PreviewView(ctx).apply { keepScreenOn = true } }

    // (Re)bind the camera when permissions arrive or the user flips it — never while recording.
    LaunchedEffect(hasPerms, back) {
        if (!hasPerms) return@LaunchedEffect
        val provider = ProcessCameraProvider.getInstance(ctx).get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycle, if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture)
        }.onFailure { error = "The camera couldn't start: ${it.message}" }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
        }
    }
    LaunchedEffect(recording) {
        while (recording != null) { elapsed = System.currentTimeMillis() - startedAt; delay(250) }
    }

    fun start() {
        val dir = File(ctx.filesDir, "videos").apply { mkdirs() }
        val file = File(dir, "VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4")
        startedAt = System.currentTimeMillis()
        val began = startedAt
        recording = capture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { e ->
                if (e is VideoRecordEvent.Finalize) {
                    if (!e.hasError() || e.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        vm.phoneVideoDone(file, walk, began)
                    } else {
                        error = "The video couldn't be saved (error ${e.error})."
                        file.delete()
                    }
                }
            }
    }
    fun stop() { recording?.stop(); recording = null }

    BackHandler { if (recording != null) stop(); onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPerms) AndroidView({ previewView }, Modifier.fillMaxSize())
        else Text(
            "JARVIS needs the camera and microphone to record. Allow them when Android asks — or in Settings → Apps → JARVIS → Permissions.",
            style = Neo.body.copy(fontSize = 16.sp, lineHeight = 23.sp, color = Neo.Text), textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
        )

        // Top: back, what this is, running time.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { if (recording != null) stop(); onClose() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                if (walk) "Site walk · phone camera" else "Video · phone camera",
                style = Neo.body.copy(fontSize = 15.sp, color = Color.White), modifier = Modifier.weight(1f),
            )
            if (recording != null) Row(
                Modifier.clip(RoundedCornerShape(50)).background(RED.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Text(MeetingRecorder.clock(elapsed), style = Neo.mono.copy(fontSize = 14.sp, color = Color.White, letterSpacing = 0.sp))
            }
        }

        // Bottom: hint, big red button, flip.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (error.isNotBlank()) Text(error, style = Neo.body.copy(fontSize = 14.sp, color = Neo.Danger), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                when {
                    recording != null && walk -> "Walk and talk: say what you see and what's wrong. Tap the button to finish."
                    recording != null -> "Recording — tell people you're filming. Tap the button to stop."
                    walk -> "Tap the red button and walk the site, talking about what you see."
                    else -> "Tap the red button to start."
                },
                style = Neo.body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = Color.White), textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Spacer(Modifier.size(52.dp))
                // The record button: a red circle (start) / a red square (stop).
                Box(
                    Modifier.size(84.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .clickable(enabled = hasPerms) { if (recording == null) start() else { stop() } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (recording == null) Box(Modifier.size(64.dp).clip(CircleShape).background(RED))
                    else Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(RED))
                }
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (recording == null) 0.5f else 0.2f))
                        .clickable(enabled = recording == null) { back = !back },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Cameraswitch, contentDescription = "Flip camera", tint = Color.White.copy(alpha = if (recording == null) 1f else 0.4f)) }
            }
            if (recording == null && startedAt > 0) com.echo.app.ui.components.GlowButton("Done — back to JARVIS", onClick = onClose)
        }
    }
}
