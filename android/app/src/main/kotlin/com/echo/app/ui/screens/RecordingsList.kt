package com.echo.app.ui.screens

import android.content.Intent
import com.echo.app.ui.components.rememberRemoteImage
import com.echo.app.ui.components.rememberVideoControl
import com.echo.app.ui.components.VideoPlayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.meeting.Bookmark
import com.echo.app.meeting.CloudRec
import com.echo.app.meeting.LibraryInfo
import com.echo.app.meeting.LibraryItem
import com.echo.app.meeting.MeetingDetail
import com.echo.app.meeting.MeetingRecorder
import com.echo.app.meeting.TLine
import com.echo.app.ui.components.FilterChips
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.RoundStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm").withZone(ZoneId.systemDefault())
private val SPEAKER_COLORS = listOf(Color(0xFF5CE1E6), Color(0xFFFFC46B), Color(0xFFB79CFF), Color(0xFF7EE0A1), Color(0xFFFF9EB5))

private fun whenText(ms: Long) = if (ms > 0) WHEN.format(Instant.ofEpochMilli(ms)) else ""
private fun minutes(s: Int) = when {
    s < 60 -> "$s s"
    s < 3600 -> "${(s + 30) / 60} min"
    else -> "${s / 3600} h ${(s / 60) % 60} min"
}
private fun sizeText(bytes: Long) = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    else -> "${bytes / 1000} KB"
}
private fun clockS(s: Double) = MeetingRecorder.clock((s * 1000).toLong())

/** Where a recording is in its journey, in plain words, and the colour to show it in. */
fun statusOf(item: LibraryItem): Pair<String, Color> {
    val c = item.cloud
    if (c == null) {
        val l = item.local
        return if (l != null && l.uploadedChunks > 0) "Uploading ${l.uploadedChunks}/${l.chunks}" to Neo.Amber else "On phone" to Neo.Muted
    }
    return when (c.status) {
        "recording", "uploading" -> "Uploading" to Neo.Amber
        "uploaded", "transcribing" -> "Transcribing…" to Neo.Amber
        "transcribed", "summarising" -> "Summarising…" to Neo.Amber
        "ready" -> when {
            c.audioDeleted -> "Audio expired · text kept" to Neo.Muted
            !c.isMeeting -> "No conversation" to Neo.Muted
            else -> "Ready" to Neo.Cyan
        }
        "failed" -> "Problem" to Neo.Danger
        else -> c.status to Neo.Muted
    }
}

// ── library ──────────────────────────────────────────────────────────────────────────────────────

/** "Audio kept 90 days · 2 starred kept forever · 212 MB" (mockup Recordings). */
@Composable
fun LibraryStrip(info: LibraryInfo, offline: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Neo.CardLow).border(1.dp, Neo.Border, shape).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (offline) "Offline — showing what's on this phone"
            else (if (info.keepDays == 0) "Audio kept forever" else "Audio kept ${info.keepDays} days") +
                (if (info.starred > 0) " · ${info.starred} starred kept forever" else ""),
            style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted), modifier = Modifier.weight(1f),
        )
        if (!offline) Text(sizeText(info.audioBytes), style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
    }
}

/** One recording in the list: title, when/how long, where it's at, the gist, counts, and a star. */
@Composable
fun RecordingCard(item: LibraryItem, onOpen: () -> Unit, onStar: ((Boolean) -> Unit)?) {
    val shape = RoundedCornerShape(20.dp)
    val c = item.cloud
    val (status, statusColor) = statusOf(item)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape).clickable(onClick = onOpen).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Neo.Cyan.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = Neo.Cyan, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = Neo.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                whenText(item.startedAt) + " · " + minutes(item.durationS) + " · " + (c?.source ?: "phone"),
                style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp),
            )
            MonoLabel(status, color = statusColor)
            if (c != null && c.status == "ready" && c.inShort.isNotBlank() && c.isMeeting) {
                Text(c.inShort, style = Neo.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Neo.TextSoft), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val counts = listOfNotNull(
                    c.actionCount.takeIf { it > 0 }?.let { "$it action item" + if (it == 1) "" else "s" },
                    c.decisionCount.takeIf { it > 0 }?.let { "$it decision" + if (it == 1) "" else "s" },
                ).joinToString(" · ")
                if (counts.isNotBlank()) Text(counts, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Cyan))
            }
        }
        if (onStar != null && c != null) {
            Icon(
                if (c.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (c.starred) "Unstar" else "Star — keep the audio forever",
                tint = if (c.starred) Neo.Amber else Neo.Faint,
                modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onStar(!c.starred) }.padding(8.dp),
            )
        }
    }
}

// ── detail ───────────────────────────────────────────────────────────────────────────────────────

/** A recording opened from the library (mockups MeetingSummary / MeetingTranscript). */
@Composable
fun RecordingDetail(vm: HomeViewModel, item: LibraryItem, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p by vm.player.ui.collectAsState()
    var detail by remember { mutableStateOf<MeetingDetail?>(null) }
    var loadError by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("summary") }
    var renameTitle by remember { mutableStateOf(false) }
    var renameSpeaker by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    // Follow the cloud until the notes are ready (or it was never uploaded).
    LaunchedEffect(item.id, reload) {
        while (true) {
            runCatching { vm.meetingLib.get(item.id) }
                .onSuccess { detail = it; loadError = "" }
                .onFailure { loadError = it.message ?: "offline" }
            if (detail?.rec?.status in setOf("ready", "failed") || (detail == null && item.cloud == null)) break
            delay(10_000)
        }
    }
    // Phase 3: a video plays its ORIGINAL (full quality, on this phone) at the top of the page, notes underneath.
    val videoFile = remember(item.id) { videoOriginal(item) }
    val video = rememberVideoControl()
    // Audio: from the phone if it still has it, otherwise from the cloud (until the cloud's copy expires).
    val audioDeleted = detail?.rec?.audioDeleted ?: item.cloud?.audioDeleted ?: false
    LaunchedEffect(item.id, item.hasLocalAudio, audioDeleted) {
        if (videoFile != null) return@LaunchedEffect // the video player does the playing
        val local = item.local
        if (local != null && item.hasLocalAudio) vm.player.loadLocal(item.id, local.files())
        else if (item.cloud != null && !audioDeleted) runCatching { vm.meetingLib.audioUrls(item.id) }.getOrNull()?.let { vm.player.loadCloud(item.id, it) }
    }
    DisposableEffect(item.id) { onDispose { vm.player.release() } }

    val rec: CloudRec? = detail?.rec ?: item.cloud
    val title = rec?.title?.takeIf { it.isNotBlank() } ?: item.title
    val speakers = rec?.speakers.orEmpty()
    val lines = detail?.lines.orEmpty()
    val playable = videoFile != null || (p.recordingId == item.id && p.durationMs > 0)
    val positionMs = if (videoFile != null) video.positionMs else p.positionMs
    val seek: (Double) -> Unit = { sec ->
        if (videoFile != null) video.seekTo((sec * 1000).toLong())
        else if (playable) vm.player.seekTo((sec * 1000).toLong(), andPlay = true)
    }
    val isVideo = videoFile != null || rec?.kind in setOf("video", "walk")

    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        // Header
        Column(Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                if (rec != null) {
                    RoundButton(if (rec.starred) Icons.Outlined.Star else Icons.Outlined.StarBorder, if (rec.starred) "Unstar" else "Star — keep the audio forever", onClick = {
                        val s = !rec.starred
                        detail = detail?.copy(rec = rec.copy(starred = s))
                        scope.launch { runCatching { vm.meetingLib.star(item.id, s) }; vm.refreshRecordings() }
                    })
                    Spacer(Modifier.width(8.dp))
                    RoundButton(Icons.Outlined.Share, "Share the notes", onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(title, rec)), "Share meeting notes"))
                    })
                    Spacer(Modifier.width(8.dp))
                }
                RoundButton(Icons.Outlined.Delete, "Delete", onClick = { confirmDelete = true }, style = RoundStyle.Danger)
            }
            Row(Modifier.padding(horizontal = 8.dp).clickable(enabled = rec != null) { renameTitle = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = Neo.display.copy(fontSize = 22.sp), modifier = Modifier.weight(1f, fill = false))
                if (rec != null) Icon(Icons.Outlined.Edit, contentDescription = "Rename", tint = Neo.Faint, modifier = Modifier.padding(start = 8.dp).size(16.dp))
            }
            val nSpeakers = lines.map { it.speaker }.filter { it != "Media" }.distinct().size
            Text(
                whenText(item.startedAt) + " · " + minutes(item.durationS) +
                    (if (nSpeakers > 0) " · $nSpeakers speaker" + (if (nSpeakers == 1) "" else "s") else ""),
                style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (videoFile != null) {
                VideoPlayer(
                    videoFile, control = video, autoPlay = false,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                )
            }
            if (isVideo) KeyMoments(vm, rec, detail?.moments.orEmpty(), onSeek = seek)
            FilterChips(
                if (isVideo) listOf("summary" to "Notes", "transcript" to "Seen & said") else listOf("summary" to "Summary", "transcript" to "Transcript"),
                tab, onSelect = { tab = it }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.weight(1f)) {
            if (tab == "summary") SummaryTab(vm, item, rec, loadError, onSeek = seek)
            else TranscriptTab(lines, rec, speakers, positionMs, playable, onSeek = seek, onRename = { renameSpeaker = it })
        }

        if (videoFile == null) PlayerBar(vm, item, rec, audioDeleted)
    }

    if (renameTitle && rec != null) {
        TextDialog("Rename meeting", title, onDone = { t ->
            renameTitle = false
            if (t.isNotBlank() && t != title) {
                detail = detail?.copy(rec = rec.copy(title = t))
                scope.launch { runCatching { vm.meetingLib.rename(item.id, t) }; vm.refreshRecordings() }
            }
        }, onCancel = { renameTitle = false })
    }
    renameSpeaker?.let { label ->
        TextDialog("Who is $label?", speakers[label] ?: "", hint = "e.g. Mr Ade", onDone = { n ->
            renameSpeaker = null
            val r = detail?.rec
            if (r != null) {
                val map = r.speakers.toMutableMap().apply { if (n.isBlank()) remove(label) else put(label, n) }
                detail = detail?.copy(rec = r.copy(speakers = map))
                scope.launch { runCatching { vm.meetingLib.renameSpeaker(item.id, label, n) } }
            }
        }, onCancel = { renameSpeaker = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Neo.Card,
            title = { Text("Delete this recording?", style = Neo.display.copy(fontSize = 20.sp)) },
            text = {
                Text(
                    if (rec != null) "“Audio only” keeps the summary, transcript and memory. “Everything” removes it all, from this phone and the cloud."
                    else "The audio is removed from this phone for good.",
                    style = Neo.body.copy(color = Neo.TextSoft),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (rec != null && !audioDeleted) TextButton(onClick = { confirmDelete = false; vm.deleteRecording(item.id, audioOnly = true); reload++ }) { Text("Audio only", color = Neo.Amber) }
                    TextButton(onClick = { confirmDelete = false; vm.deleteRecording(item.id); onBack() }) { Text("Everything", color = Neo.Danger) }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = Neo.Text) } },
        )
    }
}

/** The glasses video this recording was made from, if it's still on the phone. */
private fun videoOriginal(item: LibraryItem): java.io.File? = item.local?.dir?.let { d ->
    runCatching { org.json.JSONObject(java.io.File(d, "meta.json").readText()).optString("original") }.getOrNull()
}?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }?.takeIf { it.exists() }

/** Photos JARVIS took at a video's key moments (or, before they're ready, the moments themselves) — tap to jump there. */
@Composable
private fun KeyMoments(vm: HomeViewModel, rec: CloudRec?, moments: List<Pair<Double, String>>, onSeek: (Double) -> Unit) {
    val frames = rec?.frames.orEmpty()
    if (frames.isEmpty() && moments.isEmpty()) return
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLabel("Key moments — tap to watch")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (frames.isNotEmpty()) items(frames) { f ->
                val url by produceState<String?>(null, f.path) { value = vm.signedMediaUrl(f.path) }
                val img = rememberRemoteImage(url)
                Column(Modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).clickable { onSeek(f.t) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Neo.Card), contentAlignment = Alignment.Center) {
                        if (img != null) Image(img, contentDescription = f.why, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else CircularProgressIndicator(strokeWidth = 2.dp, color = Neo.Cyan, modifier = Modifier.size(18.dp))
                    }
                    Text(clockS(f.t) + " · " + f.why, style = Neo.body.copy(fontSize = 12.sp, lineHeight = 16.sp, color = Neo.TextSoft), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else items(moments) { (t, why) ->
                Text(
                    clockS(t) + " · " + why, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(16.dp)).background(Neo.Card).clickable { onSeek(t) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryTab(vm: HomeViewModel, item: LibraryItem, rec: CloudRec?, loadError: String, onSeek: (Double) -> Unit) {
    val scope = rememberCoroutineScope()
    var done by remember(rec?.clientId, rec?.actionDone) { mutableStateOf(rec?.actionDone.orEmpty()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val summary = rec?.summary
        if (rec == null || rec.status != "ready" || summary == null) {
            val (label, _) = statusOf(item.copy(cloud = rec))
            Text(
                when {
                    rec == null && loadError.isNotBlank() && item.cloud == null -> "On this phone only — the notes come after it uploads (it needs a connection)."
                    rec == null -> "Waiting to upload — the notes come once it's in the cloud."
                    rec.status == "failed" -> "Couldn't make the notes: ${rec.error}"
                    rec.error.startsWith("summary:") -> "$label The summary had a problem and will be retried automatically in a few minutes."
                    else -> "$label The notes appear here — usually within a few minutes of stopping. You'll get a notification."
                },
                style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = Neo.Muted),
            )
            MarkedMoments(item.bookmarks, onSeek)
            return@Column
        }
        NoteCard {
            MonoLabel("In short", color = Neo.Cyan)
            Text(rec.inShort, style = Neo.body.copy(fontSize = 16.sp, lineHeight = 23.sp))
            if (rec.error.isNotBlank()) Text(rec.error, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Amber))
        }
        val actions = rec.list("action_items").map { it as org.json.JSONObject }
        if (actions.isNotEmpty()) NoteCard {
            MonoLabel("Action items — tap when done")
            actions.forEachIndexed { i, a ->
                val checked = i in done
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        done = if (checked) done - i else done + i
                        scope.launch { runCatching { vm.meetingLib.tick(item.id, i, !checked) } }
                    }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = if (checked) "Done" else "Not done", tint = if (checked) Neo.Cyan else Neo.Faint, modifier = Modifier.size(24.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(a.optString("task"), style = Neo.body.copy(fontSize = 15.sp, lineHeight = 21.sp, color = if (checked) Neo.Muted else Neo.Text))
                        val who = listOf(a.optString("owner"), a.optString("due")).filter { it.isNotBlank() }.joinToString(" · ")
                        if (who.isNotBlank()) Text(who, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))
                    }
                }
            }
        }
        val numbers = rec.list("numbers").map { it as org.json.JSONObject }
        if (numbers.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            numbers.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { n -> NumberTile(n.optString("label"), n.optString("value"), Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Bullets("Decisions", rec.list("decisions").map { it.toString() })
        Bullets("Follow-ups", rec.list("follow_ups").map { it.toString() })
        Bullets("Open questions", rec.list("open_questions").map { it.toString() })
        val people = rec.list("people").map { it.toString() }
        if (people.isNotEmpty()) Text("People: " + people.joinToString(", "), style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))
        MarkedMoments(item.bookmarks, onSeek)
        if (rec.starred) Text(
            "★ Starred — the audio is kept forever. " + when (rec.driveStatus) {
                "copied" -> "A copy is in your Google Drive (JARVIS › Recordings)."
                "copying" -> "Copying it to your Google Drive…"
                "failed" -> "Couldn't copy it to Google Drive — unstar and star it again to retry."
                else -> "Connect Google in Settings to also keep a copy in your Drive."
            },
            style = Neo.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Neo.Amber),
        )
        AskBox(vm, item.id, onSeek)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun NoteCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun NumberTile(label: String, value: String, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MonoLabel(label, size = 10)
        Text(value, style = Neo.display.copy(fontSize = 17.sp, color = Neo.CyanPale), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Bullets(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLabel(title)
        items.forEach { Text("•  $it", style = Neo.body.copy(fontSize = 15.sp, lineHeight = 21.sp)) }
    }
}

@Composable
private fun MarkedMoments(marks: List<Bookmark>, onSeek: (Double) -> Unit) {
    if (marks.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MonoLabel("Marked moments — tap to listen")
        marks.forEach { b ->
            val s = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(s).background(Neo.Card).border(1.dp, Neo.Border, s).clickable { onSeek(b.tS.toDouble()) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(18.dp))
                Text(MeetingRecorder.clock(b.tS * 1000L), style = Neo.mono.copy(fontSize = 13.sp, color = Neo.Amber, letterSpacing = 0.sp))
                Text(b.label.ifBlank { "Marked" }, style = Neo.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "Ask about this meeting" — answered from this meeting's transcript, with the moments it used. */
@Composable
private fun AskBox(vm: HomeViewModel, id: String, onSeek: (Double) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable(id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var answer by remember(id) { mutableStateOf<Pair<String, List<Pair<Double, String>>>?>(null) }
    val send: () -> Unit = {
        val question = q.trim()
        if (question.isNotEmpty() && !busy) {
            busy = true
            scope.launch {
                answer = runCatching { vm.meetingLib.ask(id, question) }.getOrElse { ("Couldn't ask right now — ${it.message}") to emptyList() }
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MonoLabel("Ask about this meeting")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("What did Mr Ade say about delivery?", style = Neo.body.copy(fontSize = 14.sp, color = Neo.Faint), maxLines = 1) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                shape = RoundedCornerShape(24.dp), colors = neoFieldColors(),
            )
            if (busy) CircularProgressIndicator(color = Neo.Cyan, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            else RoundButton(Icons.AutoMirrored.Outlined.Send, "Ask", onClick = send, style = RoundStyle.Primary)
        }
        answer?.let { (a, moments) ->
            NoteCard {
                Text(a, style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp))
                moments.forEach { (t, quote) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSeek(t) }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Play from here", tint = Neo.Cyan, modifier = Modifier.size(18.dp))
                        Text(clockS(t), style = Neo.mono.copy(fontSize = 12.sp, color = Neo.Cyan, letterSpacing = 0.sp))
                        Text("“$quote”", style = Neo.body.copy(fontSize = 13.sp, color = Neo.TextSoft), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Transcript lines with time + speaker; marks inline; tap a line to play from it, a name to rename. */
@Composable
private fun TranscriptTab(
    lines: List<TLine>, rec: CloudRec?, speakers: Map<String, String>, positionMs: Long, playable: Boolean,
    onSeek: (Double) -> Unit, onRename: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    if (lines.isEmpty()) {
        Text(
            if (rec?.status == "ready") "No speech was found in this recording." else "The transcript appears here once it's written (a few minutes after the recording uploads).",
            style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = Neo.Muted), modifier = Modifier.padding(20.dp),
        )
        return
    }
    val labels = remember(lines) { lines.map { it.speaker }.distinct() }
    val pos = positionMs / 1000.0
    val current = if (playable) lines.indexOfLast { it.t <= pos } else -1
    val marks = rec?.bookmarks.orEmpty()
    // Lines and marks on one timeline (a mark sits just before the first line at or after its time).
    val rows: List<Any> = remember(lines, marks, query, speakers) {
        if (query.isNotBlank()) lines.filter { it.text.contains(query, true) || (speakers[it.speaker] ?: it.speaker).contains(query, true) }
        else (lines + marks).sortedBy { if (it is TLine) it.t else (it as Bookmark).tS - 0.01 }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Neo.Cyan) },
                placeholder = { Text("Search the transcript", style = Neo.body.copy(fontSize = 14.sp, color = Neo.Faint)) },
                shape = RoundedCornerShape(24.dp), colors = neoFieldColors(),
            )
        }
        items(rows) { row ->
            if (row is Bookmark) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onSeek(row.tS.toDouble()) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(16.dp))
                    Text("Marked: " + row.label.ifBlank { MeetingRecorder.clock(row.tS * 1000L) }, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Amber))
                }
            } else {
                val l = row as TLine
                val isNow = current >= 0 && lines.getOrNull(current) === l
                val media = l.speaker == "Media" || l.speaker == "Seen"
                val color = if (media) Neo.Faint else SPEAKER_COLORS[labels.indexOf(l.speaker).coerceAtLeast(0) % SPEAKER_COLORS.size]
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isNow) Neo.Cyan.copy(alpha = 0.08f) else Color.Transparent)
                        .clickable { onSeek(l.t) }.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(clockS(l.t), style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
                        Text(
                            (speakers[l.speaker] ?: l.speaker) + if (!media) " ✎" else "",
                            style = Neo.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color),
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !media && rec != null) { onRename(l.speaker) }.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                    Text(l.text, style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = if (media) Neo.Muted else Neo.Text))
                }
            }
        }
    }
}

/** Scrubber with mark ticks, time, −15 / play / +15 and speed. */
@Composable
private fun PlayerBar(vm: HomeViewModel, item: LibraryItem, rec: CloudRec?, audioDeleted: Boolean) {
    val p by vm.player.ui.collectAsState()
    val ready = p.recordingId == item.id && p.durationMs > 0
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier.fillMaxWidth().background(Neo.Bar).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (!ready) {
            Text(
                when {
                    audioDeleted -> "The audio was deleted — the notes and transcript are kept."
                    item.hasLocalAudio || rec != null -> "Loading the audio… (from the cloud it needs a connection)"
                    else -> "No audio on this phone."
                },
                style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted), modifier = Modifier.padding(vertical = 10.dp),
            )
            return@Column
        }
        val dur = p.durationMs.coerceAtLeast(1)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Slider(
                value = dragging ?: (p.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { vm.player.seekTo((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Neo.Cyan, activeTrackColor = Neo.Cyan, inactiveTrackColor = Neo.Border),
            )
            // Marked moments as small amber ticks on the scrubber.
            item.bookmarks.forEach { b ->
                val f = (b.tS * 1000f / dur).coerceIn(0f, 1f)
                Box(Modifier.offset(x = (maxWidth - 3.dp) * f, y = 4.dp).size(width = 3.dp, height = 8.dp).background(Neo.Amber))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MeetingRecorder.clock(dragging?.let { (it * dur).toLong() } ?: p.positionMs) + " / " + MeetingRecorder.clock(p.durationMs),
                style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), modifier = Modifier.weight(1f),
            )
            if (p.error.isNotBlank()) Text(p.error, style = Neo.body.copy(fontSize = 11.sp, color = Neo.Amber), maxLines = 1, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−15", onClick = { vm.player.skip(-15_000) }, height = 44.dp)
            if (p.loading) {
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Neo.Cyan, strokeWidth = 3.dp) }
            } else {
                RoundButton(if (p.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (p.playing) "Pause" else "Play", onClick = { vm.player.toggle() }, size = 60.dp, style = RoundStyle.Primary)
            }
            GhostButton("+15", onClick = { vm.player.skip(15_000) }, height = 44.dp)
            GhostButton(if (p.speed == 1f) "1×" else "${p.speed}×", onClick = { vm.player.cycleSpeed() }, height = 44.dp)
        }
    }
}

@Composable
private fun TextDialog(title: String, initial: String, hint: String = "", onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Neo.Card,
        title = { Text(title, style = Neo.display.copy(fontSize = 20.sp)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { if (hint.isNotBlank()) Text(hint, color = Neo.Faint) },
                shape = RoundedCornerShape(16.dp), colors = neoFieldColors(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text("Save", color = Neo.Cyan) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = Neo.Text) } },
    )
}

/** The notes as plain text for WhatsApp / email. */
private fun shareText(title: String, rec: CloudRec): String = buildString {
    appendLine(title)
    if (rec.startedAt > 0) appendLine(whenText(rec.startedAt))
    if (rec.inShort.isNotBlank()) { appendLine(); appendLine(rec.inShort) }
    fun section(name: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine(); appendLine("$name:"); items.forEach { appendLine("• $it") }
    }
    section("Decisions", rec.list("decisions").map { it.toString() })
    section("Action items", rec.list("action_items").map { a ->
        val o = a as org.json.JSONObject
        o.optString("task") + listOf(o.optString("owner"), o.optString("due")).filter { it.isNotBlank() }.joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
    })
    section("Numbers", rec.list("numbers").map { (it as org.json.JSONObject).let { n -> n.optString("label") + ": " + n.optString("value") } })
    section("Follow-ups", rec.list("follow_ups").map { it.toString() })
    appendLine(); append("— notes by JARVIS")
}
