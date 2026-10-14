package com.echo.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.FilterChips
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.components.splitSources
import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FILTERS = listOf("all" to "All", "recordings" to "Recordings", "photos" to "Photos", "people" to "People", "research" to "Research", "notes" to "Notes")
private val TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.systemDefault())

/**
 * Memories (redesign 2026-10) = the old Timeline + Gallery in one place: a question-style search
 * (semantic recall), filters (All · Photos · People · Research · Notes) and a card per kind.
 * Photos open the existing photo/video detail (ask about it, delete, play).
 */
@Composable
fun MemoriesScreen(vm: HomeViewModel, onAskAboutPhoto: (Memory) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshLibrary(); vm.reconcileOrphanMedia(); vm.refreshRecordings() }
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<Memory?>(null) }
    var openRec by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(com.echo.app.meeting.MeetingNav.openRecording) {
        com.echo.app.meeting.MeetingNav.openRecording?.let { filter = "recordings"; openRec = it; com.echo.app.meeting.MeetingNav.openRecording = null }
    }
    LaunchedEffect(filter) {
        if (filter == "recordings") {
            vm.kickRecordingUploads()
            while (true) { vm.refreshRecordings(); kotlinx.coroutines.delay(15_000) } // live status (uploading → ready)
        }
    }
    vm.library.firstOrNull { it.id == openRec }?.let { rec ->
        BackHandler { openRec = null }
        RecordingDetail(vm, rec, onBack = { openRec = null })
        return
    }
    selected?.let { mem ->
        BackHandler { selected = null }
        PhotoDetailScreen(vm, mem, onBack = { selected = null }, onAskAboutPhoto = { onAskAboutPhoto(mem) })
        return
    }

    val source = vm.searchHits ?: vm.timeline
    val shown = remember(source, filter) {
        when (filter) {
            "photos" -> (if (vm.searchHits != null) source else vm.gallery).filter { it.type == MemoryType.PHOTO }
            "people" -> source.filter { it.type == MemoryType.PERSON }
            "research" -> source.filter { "research" in it.tags }
            "notes" -> source.filter { it.type in setOf(MemoryType.NOTE, MemoryType.VOICE_NOTE, MemoryType.QA, MemoryType.JOURNAL, MemoryType.MEETING) && "research" !in it.tags }
            else -> source
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(Neo.Ground),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(if (filter == "photos") 6.dp else 10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item(span = { GridItemSpan(3) }) {
            Column(Modifier.statusBarsPadding().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Memories", style = Neo.display.copy(fontSize = 28.sp), modifier = Modifier.weight(1f))
                    Text("${vm.timeline.size} saved", style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp))
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it; if (it.isBlank()) vm.clearSearch() }, singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Neo.Cyan) },
                    trailingIcon = { if (query.isNotBlank()) RoundButton(Icons.Outlined.Close, "Clear search", onClick = { query = ""; vm.clearSearch() }, size = 36.dp) },
                    placeholder = { Text("Who was the supplier I met last week?", style = Neo.body.copy(fontSize = 15.sp, color = Neo.Faint), maxLines = 1) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) vm.searchMemories(query) }),
                    shape = RoundedCornerShape(28.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
                FilterChips(FILTERS, filter, onSelect = { filter = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel(
                        when {
                            filter == "recordings" -> "Meetings & recordings"
                            vm.searching -> "Searching…"
                            vm.searchHits != null -> "Results"
                            else -> "Latest"
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.pendingSync > 0) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(14.dp))
                        Text(" ${vm.pendingSync} on phone", style = Neo.mono.copy(fontSize = 11.sp, color = Neo.Amber, letterSpacing = 0.sp))
                    }
                }
            }
        }
        if (filter == "recordings") {
            item(span = { GridItemSpan(3) }) { LibraryStrip(vm.libraryInfo, vm.libraryOffline) }
            if (vm.library.isEmpty()) item(span = { GridItemSpan(3) }) {
                Text(
                    "No recordings yet — tap Meeting on Home, or say “Jarvis, record this meeting”. Finished recordings land here.",
                    style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = Neo.Muted),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 30.dp),
                )
            }
            items(vm.library, key = { "rec-" + it.id }, span = { GridItemSpan(3) }) { r ->
                RecordingCard(r, onOpen = { openRec = r.id }, onStar = { starred -> vm.starRecording(r.id, starred) })
            }
        } else if (shown.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        when (filter) {
                            "photos" -> "Photos from your glasses land here after a sync."
                            "people" -> "No people yet — say “Jarvis, remember this person: Mr Ade, supplier, met at the yard.”"
                            "research" -> "No research yet — tap Research on Home, or say “Jarvis, research…”."
                            else -> if (vm.searchHits != null) "Nothing matched." else "Say “Jarvis, remember…”, take a photo with the glasses, or ask something — it all lands here."
                        },
                        style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = Neo.Muted),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (filter == "photos") GhostButton(if (vm.syncing) "Syncing…" else "Sync from glasses", onClick = vm::syncGlasses, icon = Icons.Outlined.Sync, enabled = !vm.syncing)
                }
            }
        } else if (filter == "photos") {
            items(shown, key = { it.id ?: it.clientId ?: it.hashCode().toString() }) { m -> PhotoTile(m, onClick = { selected = m }) }
        } else {
            var lastDay: LocalDate? = null
            shown.forEach { m ->
                val day = m.createdAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
                if (day != null && day != lastDay) {
                    lastDay = day
                    item(span = { GridItemSpan(3) }, key = "day-$day") { MonoLabel(dayLabel(day), modifier = Modifier.padding(top = 8.dp)) }
                }
                item(span = { GridItemSpan(3) }, key = m.id ?: m.clientId ?: m.hashCode().toString()) {
                    MemoryRow(m, onOpenPhoto = { selected = m }, onOpenMeeting = { key ->
                        // key = the recording id, or the meeting's title (cloud search hits carry no metadata).
                        val id = if (key.startsWith("rec-")) key else vm.library.firstOrNull { it.title.equals(key, ignoreCase = true) }?.id
                        filter = "recordings"
                        if (id != null) openRec = id
                    })
                }
            }
        }
    }
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY.format(d.atStartOfDay(ZoneId.systemDefault()))
    }
}

@Composable
private fun PhotoTile(m: Memory, onClick: () -> Unit) {
    val img = rememberLocalImage(m.metadata["localMediaPath"], targetPx = 384)
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Neo.Card).clickable(onClick = onClick)) {
        if (img != null) Image(BitmapPainter(img), contentDescription = m.text, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if ("video" in m.tags) Icon(Icons.Outlined.PlayCircle, contentDescription = "video", tint = Neo.Text, modifier = Modifier.align(Alignment.Center).size(32.dp))
    }
}

/** One memory, styled by kind: person, research, photo, or a plain note/voice note/answer. */
@Composable
private fun MemoryRow(m: Memory, onOpenPhoto: () -> Unit, onOpenMeeting: (String) -> Unit = {}) {
    var open by rememberSaveable(m.id ?: m.clientId ?: m.text) { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val base = Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape)
    val time = m.createdAt?.let { TIME.format(it) } ?: ""
    val (body, sources) = remember(m.text) { splitSources(m.text.orEmpty()) }
    val onPhone = m.metadata["syncState"] !in listOf(null, "SYNCED")
    when {
        m.type == MemoryType.PERSON -> {
            val text = body.removePrefix("Person:").trim()
            val name = text.substringBefore(" — ").trim()
            val rest = text.substringAfter(" — ", "").split(" · ")
            Row(base.clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Neo.Cyan, androidx.compose.ui.graphics.Color(0xFF0B6E86)))), contentAlignment = Alignment.Center) {
                    Text(initials(name), style = Neo.display.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Neo.OnCyan))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row { Text(name, style = Neo.body.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f)); MonoLabel("Person", color = Neo.Cyan) }
                    Text(rest.filterNot { it.startsWith("saved ") || it.startsWith("on ") }.joinToString(" · "), style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted),
                        maxLines = if (open) 8 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        m.type == MemoryType.PHOTO -> {
            val img = rememberLocalImage(m.metadata["localMediaPath"], targetPx = 256)
            val research = "research" in m.tags
            Row(base.clickable(onClick = onOpenPhoto).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(Neo.CardLow)) {
                    if (img != null) Image(BitmapPainter(img), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text(time, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), modifier = Modifier.weight(1f)); MonoLabel(if (research) "Research" else if ("video" in m.tags) "Video" else "Look", color = if (research) Neo.Amber else Neo.Cyan) }
                    Text(body.removePrefix("Research — "), style = Neo.body.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        "research" in m.tags -> {
            val title = body.removePrefix("Research — ").substringBefore(":\n")
            val answer = body.substringAfter(":\n", body).trim()
            Column(base.clickable { open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text(time, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), modifier = Modifier.weight(1f)); MonoLabel("Research", color = Neo.Amber) }
                Text(title, style = Neo.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Text(answer, style = Neo.body.copy(fontSize = 13.sp, lineHeight = 19.sp, color = androidx.compose.ui.graphics.Color(0xFF9AA8AD)), maxLines = if (open) 40 else 3, overflow = TextOverflow.Ellipsis)
                if (open && sources.isNotEmpty()) com.echo.app.ui.components.SourcesRow(sources)
            }
        }
        else -> {
            val meetingId = if (m.type != MemoryType.MEETING) null
            else m.metadata["client_id"]?.takeIf { it.startsWith("rec-") }
                ?: body.removePrefix("Meeting:").substringBefore(" — ").trim().takeIf { body.startsWith("Meeting:") && it.isNotBlank() }
            val tag = when {
                m.type == MemoryType.MEETING -> "Meeting"
                m.type == MemoryType.VOICE_NOTE -> "Voice note"
                m.type == MemoryType.QA -> "Answer"
                "translation" in m.tags -> "Translation"
                "email" in m.tags -> "Email"
                else -> "Note"
            }
            Column(base.clickable { if (meetingId != null) onOpenMeeting(meetingId) else open = !open }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.type == MemoryType.VOICE_NOTE) Icon(Icons.Outlined.Mic, contentDescription = null, tint = Neo.Muted, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                    Text(time, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp), modifier = Modifier.weight(1f))
                    if (onPhone) MonoLabel("On phone", color = Neo.Amber, modifier = Modifier.padding(end = 8.dp))
                    MonoLabel(tag)
                }
                Text(body, style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp), maxLines = if (open) 60 else 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun initials(name: String): String {
    val words = name.replace(Regex("^(mr|mrs|ms|miss|dr|chief|engr|alhaji|madam|pastor)\\.?\\s+", RegexOption.IGNORE_CASE), "")
        .split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

@Suppress("unused")
private fun Instant.local() = atZone(ZoneId.systemDefault())
