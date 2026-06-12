package com.echo.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.OrbState
import com.echo.app.ui.components.PresenceOrb
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import com.echo.core.model.Memory
import com.echo.core.model.MemoryType

/**
 * Gallery (gallery_visual_memories): a 3-up grid of photo memories from the read-only media
 * listing, with sync always reachable from the header. Tapping a photo opens the detail view
 * (memory_detail_photo). Empty state until the first sync.
 */
@Composable
fun GalleryScreen(vm: HomeViewModel) {
    LaunchedEffect(Unit) { vm.refreshLibrary(); vm.reconcileOrphanMedia() }
    var selected by remember { mutableStateOf<Memory?>(null) }
    val photos = vm.gallery.filter { it.type == MemoryType.PHOTO }

    // Detail overlay — device back returns to the grid.
    selected?.let { mem ->
        BackHandler { selected = null }
        PhotoDetailScreen(vm, mem, onBack = { selected = null })
        return
    }

    if (photos.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = JarvisSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PresenceOrb(state = if (vm.online) OrbState.Idle else OrbState.OffGrid)
            Spacer(Modifier.height(JarvisSpacing.lg))
            Text(
                "Visual memories",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(
                "Photos captured on the glasses land here after a sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(JarvisSpacing.lg))
            JarvisSecondaryButton("Sync from glasses", onClick = vm::syncGlasses, enabled = !vm.syncing)
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(
                vm.status,
                style = JarvisTheme.dataMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = JarvisSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) {
        // Header row (full-width): title + count, sync action always available.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = JarvisSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Visual memories",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${photos.size} ITEMS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (vm.syncing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.height(20.dp).padding(end = JarvisSpacing.sm),
                        )
                    }
                    IconButton(onClick = vm::syncGlasses, enabled = !vm.syncing) {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = "sync from glasses",
                            tint = MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                }
                if (vm.syncing) {
                    Text(
                        vm.status,
                        style = JarvisTheme.dataMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(photos.size) { i ->
            val m = photos[i]
            val image = rememberLocalImage(m.metadata["localMediaPath"], targetPx = 384)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable { selected = m },
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(
                        bitmap = image,
                        contentDescription = m.text,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = m.text,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}
