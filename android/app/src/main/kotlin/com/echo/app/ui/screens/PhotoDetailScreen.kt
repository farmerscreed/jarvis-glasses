package com.echo.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.SectionLabel
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.components.TagChip
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.components.rememberRemoteImage
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import com.echo.core.model.Memory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DETAIL_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault())

/**
 * Photo memory detail (memory_detail_photo): full-width hero image, capture time, AI OBSERVATION
 * (the Claude caption), and identified-element tags. Read-only — the hero loads from the local
 * capture file if present, else a signed URL for the synced copy.
 */
@Composable
fun PhotoDetailScreen(vm: HomeViewModel, memory: Memory, onBack: () -> Unit) {
    val local = rememberLocalImage(memory.metadata["localMediaPath"], targetPx = 1280)
    // Only fetch a signed URL if there's no local file to show.
    val remoteUrl by produceState<String?>(initialValue = null, memory.id) {
        value = if (memory.metadata["localMediaPath"] == null) vm.signedUrlFor(memory) else null
    }
    val remote = rememberRemoteImage(remoteUrl)
    val image: ImageBitmap? = local ?: remote
    val loading = local == null && remote == null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Hero with a back affordance overlaid top-left.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = memory.text,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                loading -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primaryContainer,
                )
                else -> Icon(
                    Icons.Outlined.ImageNotSupported,
                    contentDescription = "image unavailable",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(40.dp),
                )
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(JarvisSpacing.sm)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f), CircleShape),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Column(
            modifier = Modifier.padding(JarvisSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.lg),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                memory.createdAt?.let {
                    StatusChip(DETAIL_FMT.format(it), accent = MaterialTheme.colorScheme.primaryContainer)
                }
                val synced = memory.metadata["syncState"] in listOf(null, "SYNCED")
                StatusChip(
                    if (synced) "Synced" else "On phone",
                    accent = if (synced) MaterialTheme.colorScheme.primaryContainer else JarvisTheme.colors.presenceAmber,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                SectionLabel("AI Observation")
                Text(
                    memory.text?.takeIf { it.isNotBlank() } ?: "No description yet.",
                    style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val tags = memory.tags.filterNot { it.startsWith("needs_") }
            if (tags.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    SectionLabel("Identified Elements")
                    Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                        tags.take(6).forEach { TagChip(it) }
                    }
                }
            }
            Spacer(Modifier.height(JarvisSpacing.md))
        }
    }
}
