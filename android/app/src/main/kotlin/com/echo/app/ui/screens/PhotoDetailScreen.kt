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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.echo.app.ui.components.VideoPlayer
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.components.rememberRemoteImage
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import com.echo.core.model.Memory
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DETAIL_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault())

/**
 * Photo memory detail (memory_detail_photo): full-width hero image, capture time, AI OBSERVATION
 * (the Claude caption), and identified-element tags. Read-only — the hero loads from the local
 * capture file if present, else a signed URL for the synced copy.
 */
@Composable
fun PhotoDetailScreen(vm: HomeViewModel, memory: Memory, onBack: () -> Unit, onAskAboutPhoto: () -> Unit = {}) {
    val localPath = memory.metadata["localMediaPath"]
    val localFile = remember(localPath) { localPath?.let { File(it) }?.takeIf { it.exists() } }
    val isVideo = (localFile?.extension ?: memory.mediaPath?.substringAfterLast('.', ""))
        ?.lowercase() == "mp4"
    val local = rememberLocalImage(localPath, targetPx = 1280)
    // Only fetch a signed URL if there's no local file to show.
    val remoteUrl by produceState<String?>(initialValue = null, memory.id) {
        value = if (localPath == null) vm.signedUrlFor(memory) else null
    }
    val remote = rememberRemoteImage(remoteUrl)
    val image: ImageBitmap? = local ?: remote
    val loading = !isVideo && local == null && remote == null
    var confirmDelete by remember { mutableStateOf(false) }

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
                isVideo && localFile != null -> VideoPlayer(localFile, modifier = Modifier.fillMaxSize())
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
                    if (isVideo) Icons.Outlined.Videocam else Icons.Outlined.ImageNotSupported,
                    contentDescription = if (isVideo) "video not on this device" else "image unavailable",
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

            Spacer(Modifier.height(JarvisSpacing.sm))
            // Drill down on this photo in the deliberate lane (count things, read text, etc.).
            com.echo.app.ui.components.JarvisPrimaryButton(
                text = "Ask about this photo",
                onClick = onAskAboutPhoto,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(JarvisSpacing.sm))
            // Destructive: removes the capture locally AND from the cloud (row + storage).
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(JarvisSpacing.sm))
                Text("Delete this memory", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(JarvisSpacing.md))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this memory?") },
            text = { Text("This removes the capture from your phone and the cloud. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteMemory(memory, onDone = onBack)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
