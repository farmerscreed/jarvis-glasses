package com.echo.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisSearchField
import com.echo.app.ui.components.MemoryCard
import com.echo.app.ui.components.MemorySyncState
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import com.echo.core.model.MemoryType
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Timeline (timeline_memory_river / timeline_search_results). Wired to what the engine exposes
 * today: semantic search via the existing ask/recall path's results (vm.recalled) + the outbox
 * counter. A full browse-by-day river needs a read-only engine query — deliberately NOT added
 * here (no engine changes in the UI pass); see the Step 5 notes.
 */
@Composable
fun TimelineScreen(vm: HomeViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        Spacer(Modifier.height(JarvisSpacing.xs))
        JarvisSearchField(
            value = vm.question,
            onValueChange = { vm.question = it },
            placeholder = "Search your memories…",
        )
        if (vm.pendingSync > 0) {
            StatusChip(
                "${vm.pendingSync} on phone · will sync",
                accent = JarvisTheme.colors.presenceAmber,
                icon = Icons.Outlined.Sync,
            )
        }
        if (vm.recalled.isEmpty()) {
            Spacer(Modifier.height(JarvisSpacing.xl))
            Text(
                "Nothing recalled yet",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(
                "Ask Jarvis something on the Live tab — the memories it recalls appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                "Recalled",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            vm.recalled.forEach { m ->
                MemoryCard(
                    text = m.text.orEmpty(),
                    timestamp = m.createdAt?.let { TIME_FMT.format(it) } ?: "—",
                    isVoiceNote = m.type == MemoryType.VOICE_NOTE,
                    tags = m.tags,
                    syncState = if (m.id != null) MemorySyncState.Synced else MemorySyncState.OnPhone,
                )
            }
        }
        Spacer(Modifier.height(JarvisSpacing.lg))
    }
}
