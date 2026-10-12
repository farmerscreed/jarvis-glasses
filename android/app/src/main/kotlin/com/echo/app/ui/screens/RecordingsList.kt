package com.echo.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.meeting.MeetingRecorder
import com.echo.app.meeting.SavedRecording
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.RoundStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm").withZone(ZoneId.systemDefault())

fun recordingTitle(r: SavedRecording): String = r.title.ifBlank { "Meeting" }
fun recordingWhen(r: SavedRecording): String = if (r.startedAt > 0) WHEN.format(Instant.ofEpochMilli(r.startedAt)) else ""
private fun sizeText(bytes: Long) = if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1e6) else "${bytes / 1000} KB"

/** One recording in the Memories → Recordings list. */
@Composable
fun RecordingCard(r: SavedRecording, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Neo.Cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = Neo.Cyan, modifier = Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(recordingTitle(r), style = Neo.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(recordingWhen(r), style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
            Text(
                MeetingRecorder.clock(r.durationS * 1000L) + " · " + sizeText(r.bytes) +
                    (if (r.bookmarks.isNotEmpty()) " · ${r.bookmarks.size} mark" + (if (r.bookmarks.size == 1) "" else "s") else ""),
                style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted),
            )
        }
        val (label, color) = cloudLabel(r)
        MonoLabel(label, color = color)
    }
}

/** Where the recording is: only on this phone, on its way up, or safely in the cloud. */
fun cloudLabel(r: SavedRecording) = when {
    r.inCloud -> "Backed up" to Neo.Cyan
    r.uploadedChunks > 0 -> "Uploading ${r.uploadedChunks}/${r.chunks}" to Neo.Amber
    else -> "On phone" to Neo.Muted
}

/** A recording opened from Memories: play it (±15 s, drag, jump to a mark) or delete it. */
@Composable
fun RecordingDetail(vm: HomeViewModel, r: SavedRecording, onBack: () -> Unit) {
    val p by vm.player.ui.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(r.id) { vm.player.load(r) }
    DisposableEffect(r.id) { onDispose { vm.player.release() } }

    Column(
        Modifier.fillMaxSize().background(Neo.Ground).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
            MonoLabel("Recording")
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recordingTitle(r), style = Neo.display.copy(fontSize = 24.sp))
            Text(recordingWhen(r) + " · " + MeetingRecorder.clock(r.durationS * 1000L), style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
            val (label, color) = cloudLabel(r)
            MonoLabel(label, color = color)
        }

        // Player
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val dur = p.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (p.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.player.seekTo((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Neo.Cyan, activeTrackColor = Neo.Cyan, inactiveTrackColor = Neo.Border),
            )
            Row {
                Text(MeetingRecorder.clock(dragging?.let { (it * dur).toLong() } ?: p.positionMs), style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), modifier = Modifier.weight(1f))
                Text(MeetingRecorder.clock(p.durationMs), style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("−15 s", onClick = { vm.player.skip(-15_000) })
                RoundButton(
                    if (p.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (p.playing) "Pause" else "Play",
                    onClick = { vm.player.toggle() }, size = 64.dp, style = RoundStyle.Primary,
                )
                GhostButton("+15 s", onClick = { vm.player.skip(15_000) })
            }
        }

        // Marked moments
        if (r.bookmarks.isNotEmpty()) {
            MonoLabel("Marked moments — tap to jump")
            r.bookmarks.forEach { b ->
                val s = RoundedCornerShape(16.dp)
                Row(
                    Modifier.fillMaxWidth().clip(s).background(Neo.Card).border(1.dp, Neo.Border, s)
                        .clickable { vm.player.seekTo(b.tS * 1000L); vm.player.play() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(18.dp))
                    Text(MeetingRecorder.clock(b.tS * 1000L), style = Neo.mono.copy(fontSize = 13.sp, color = Neo.Amber, letterSpacing = 0.sp))
                    Text(b.label.ifBlank { "Marked" }, style = Neo.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                }
            }
        }

        Text(
            "The written transcript and summary will appear here once meeting notes are switched on (coming next).",
            style = Neo.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Neo.Muted),
        )
        GhostButton("Delete recording", onClick = { confirmDelete = true }, icon = Icons.Outlined.Delete, color = Neo.Danger, modifier = Modifier.fillMaxWidth())
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Neo.Card,
            title = { Text("Delete this recording?", style = Neo.display.copy(fontSize = 20.sp)) },
            text = { Text("The audio is removed from this phone and from the cloud, for good.", style = Neo.body.copy(color = Neo.TextSoft)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteRecording(r.id); onBack() }) { Text("Delete", color = Neo.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = Neo.Text) } },
        )
    }
}
