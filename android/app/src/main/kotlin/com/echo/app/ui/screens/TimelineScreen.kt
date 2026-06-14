package com.echo.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.style.TextAlign
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisSearchField
import com.echo.app.ui.components.MemoryCard
import com.echo.app.ui.components.MemorySyncState
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d")

private fun dayLabel(instant: Instant): String {
    val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY_FMT.format(day)
    }
}

/**
 * Timeline (timeline_memory_river / timeline_search_results / timeline_empty_state): the memory
 * river browsed from the local-first store (read-only `recent` query), with semantic search via
 * the read-only recall path — typing and submitting never speaks or writes.
 */
@Composable
fun TimelineScreen(vm: HomeViewModel) {
    LaunchedEffect(Unit) { vm.refreshLibrary(); vm.reconcileOrphanMedia() }
    var query by rememberSaveable { mutableStateOf("") }
    val hits = vm.searchHits

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md)) {
                Spacer(Modifier.height(JarvisSpacing.xs))
                JarvisSearchField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.isBlank()) vm.clearSearch()
                    },
                    placeholder = "Search your memories…",
                    onSearch = { vm.searchMemories(query) },
                )
                if (vm.pendingSync > 0) {
                    StatusChip(
                        "${vm.pendingSync} on phone · will sync",
                        accent = JarvisTheme.colors.presenceAmber,
                        icon = Icons.Outlined.Sync,
                    )
                }
            }
        }

        when {
            vm.searching -> item {
                Text(
                    "Searching…",
                    style = JarvisTheme.dataMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }

            hits != null -> { // search results mode
                item {
                    Text(
                        if (hits.isEmpty()) "No matches" else "Results",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                items(hits.size) { i -> TimelineCard(hits[i]) }
            }

            vm.timeline.isEmpty() -> item { // timeline_empty_state
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(JarvisSpacing.xl))
                    Text(
                        "Your story starts here",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(JarvisSpacing.sm))
                    Text(
                        "Say “Jarvis, remember…”, press the glasses button, or capture a photo — everything lands in this river.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            else -> { // the river, grouped by day
                val groups = vm.timeline
                    .filter { it.createdAt != null }
                    .groupBy { dayLabel(it.createdAt!!) }
                groups.forEach { (day, memories) ->
                    item {
                        Text(
                            day,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    items(memories.size) { i -> TimelineCard(memories[i]) }
                }
            }
        }

        item { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}

@Composable
private fun TimelineCard(m: Memory) {
    val localPath = m.metadata["localMediaPath"]
    val image = rememberLocalImage(localPath, targetPx = 256)
    // Tap to expand: long memories (esp. research, with their Sources) are truncated to 4 lines until
    // tapped, then show in full so the director can read the whole thing.
    var expanded by rememberSaveable(m.id ?: m.text) { mutableStateOf(false) }
    MemoryCard(
        text = m.text.orEmpty(),
        timestamp = m.createdAt?.let { TIME_FMT.format(it) } ?: "—",
        thumbnail = image?.let { BitmapPainter(it) },
        isVoiceNote = m.type == MemoryType.VOICE_NOTE,
        tags = m.tags.filterNot { it.startsWith("needs_") },
        // Rows straight from the server (recall hits) have no syncState metadata — they're synced.
        syncState = if (m.metadata["syncState"] in listOf(null, "SYNCED")) MemorySyncState.Synced else MemorySyncState.OnPhone,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        modifier = Modifier.clickable { expanded = !expanded },
    )
}
