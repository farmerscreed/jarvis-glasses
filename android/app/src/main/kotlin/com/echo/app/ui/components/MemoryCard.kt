package com.echo.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/** Where a memory lives right now — drives the card's trailing status icon color. */
enum class MemorySyncState { Synced, OnPhone }

/**
 * Timeline memory card: surface-container-low, 16dp radius, mono timestamp, optional photo
 * thumbnail or voice-note treatment, tag chips, amber/cyan sync state icon top-right.
 */
@Composable
fun MemoryCard(
    text: String,
    timestamp: String,
    modifier: Modifier = Modifier,
    thumbnail: Painter? = null,
    isVoiceNote: Boolean = false,
    tags: List<String> = emptyList(),
    syncState: MemorySyncState = MemorySyncState.Synced,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(JarvisSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
        ) {
            if (thumbnail != null) {
                Image(
                    painter = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isVoiceNote) {
                        Icon(
                            Icons.Outlined.Mic,
                            contentDescription = "voice note",
                            tint = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(16.dp).padding(end = JarvisSpacing.xs),
                        )
                    }
                    Text(
                        timestamp,
                        style = JarvisTheme.dataMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    val (icon, tint, label) = when (syncState) {
                        MemorySyncState.Synced ->
                            Triple(Icons.Outlined.Cloud, MaterialTheme.colorScheme.primaryContainer, "synced")
                        MemorySyncState.OnPhone ->
                            Triple(Icons.Outlined.PhoneAndroid, JarvisTheme.colors.presenceAmber, "on phone")
                    }
                    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium.let {
                        if (isVoiceNote) it.copy(fontStyle = FontStyle.Italic) else it
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                        tags.take(3).forEach { TagChip(it) }
                    }
                }
            }
        }
    }
}
