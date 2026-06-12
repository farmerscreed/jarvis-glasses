package com.echo.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.echo.core.model.MemoryType

/**
 * Gallery (gallery_visual_memories): a 3-up grid of photo memories from the read-only media
 * listing; local thumbnails where the capture file is still on the phone. Falls back to the
 * designed empty state until the first sync.
 */
@Composable
fun GalleryScreen(vm: HomeViewModel) {
    LaunchedEffect(Unit) { vm.refreshLibrary() }
    val photos = vm.gallery.filter { it.type == MemoryType.PHOTO }

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
            JarvisSecondaryButton("Sync from glasses", onClick = vm::syncGlasses, enabled = !vm.busy)
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text(
                vm.syncStatus,
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
        items(photos.size) { i ->
            val m = photos[i]
            val image = rememberLocalImage(m.metadata["localMediaPath"], targetPx = 384)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
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
                    // Synced-away capture with no local file — placeholder glyph.
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = m.text,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}
