package com.echo.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

/**
 * UI-2: the "Ask JARVIS" deliberate-lane surface (Stitch `ask_jarvis_multimodal_conversation`).
 * A reviewable thread of agent results (research / calendar / email / coding) submitted by text, with
 * **rich per-kind cards** (research shows a collapsible Sources list), and a **pinned photo** you can
 * drill down on with vision Q&A ("how many rods?", "read the text"). The fast voice loop stays in Live.
 */
@Composable
fun AskJarvisScreen(vm: HomeViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Pinned context shows ONLY when the user is actually discussing a photo (Gallery → "Ask about
    // this") — in the general lane there's no photo, so we don't imply one.
    val pinned = vm.askPhoto
    val pinnedImage = rememberLocalImage(pinned?.metadata?.get("localMediaPath"), targetPx = 128)
    val photoMode = vm.askPhoto != null

    LaunchedEffect(vm.askThread.size) {
        if (vm.askThread.isNotEmpty()) listState.animateScrollToItem(vm.askThread.size - 1)
    }

    vm.askConfirmPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = vm::dismissAskConfirm,
            title = { Text("Confirm") },
            text = { Text(prompt) },
            confirmButton = { TextButton(onClick = vm::confirmAsk) { Text("Go ahead") } },
            dismissButton = { TextButton(onClick = vm::dismissAskConfirm) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.lg)) {
        if (pinnedImage != null) {
            Card(
                Modifier.fillMaxWidth().padding(top = JarvisSpacing.sm),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(JarvisSpacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    Image(
                        painter = BitmapPainter(pinnedImage),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.medium),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(if (photoMode) "DISCUSSING THIS PHOTO" else "PINNED CONTEXT", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.primary)
                        Text(
                            pinned?.text?.take(70) ?: "Your latest capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (photoMode) {
                        IconButton(onClick = vm::clearAskPhoto) {
                            Icon(Icons.Outlined.Close, contentDescription = "stop discussing this photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
        ) {
            if (vm.askThread.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = JarvisSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (photoMode) "Ask about this photo" else "Ask JARVIS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(JarvisSpacing.sm))
                        Text(
                            if (photoMode) "Drill down: “how many are there?”, “read the text”, “what colour is it?”."
                            else "The deliberate lane. Try: “research the best noise-cancelling headphones”, " +
                                "“what's on my calendar this week”, “draft an email to Sam about Friday”, " +
                                "or “fix the typo in the README”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(vm.askThread.size) { i -> AskBubble(vm.askThread[i]) }
            if (vm.busy) {
                item {
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(Modifier.padding(JarvisSpacing.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(vm.status, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = JarvisSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (photoMode) "Ask about this photo…" else "Ask JARVIS…") },
                singleLine = false,
            )
            // Voice input: dictate the request hands-free (same STT as the voice loop).
            IconButton(onClick = vm::askByVoice, enabled = !vm.busy) {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = "ask by voice",
                    tint = if (vm.micActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { if (input.isNotBlank() && !vm.busy) { vm.askJarvis(input); input = "" } },
                enabled = input.isNotBlank() && !vm.busy,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "send", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AskBubble(turn: HomeViewModel.AskTurn) {
    if (turn.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(turn.text, Modifier.padding(JarvisSpacing.md), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        return
    }

    val error = turn.kind == "error"
    val accent = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    // Split the spoken body from a structured block (research Sources / calendar EVENTS / email DRAFT).
    val (body, blockLabel, blockLines) = remember(turn.text) { splitStructured(turn.text) }
    var showSources by remember(turn.text) { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(JarvisSpacing.md), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                Icon(kindIcon(turn.kind), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(turn.kind.uppercase(), style = JarvisTheme.dataMono, color = accent)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium.let { if (error) it.copy(fontStyle = FontStyle.Italic) else it },
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (blockLabel) {
                // Research: a collapsible list of source URLs.
                "sources" -> if (blockLines.isNotEmpty()) {
                    Text(
                        if (showSources) "▾ Sources (${blockLines.size})" else "▸ Sources (${blockLines.size})",
                        style = JarvisTheme.dataMono,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showSources = !showSources }.padding(top = JarvisSpacing.xs),
                    )
                    if (showSources) blockLines.filter { it.startsWith("http") }.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Calendar: an events list.
                "events" -> blockLines.forEach { ev ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                        Text("•", color = MaterialTheme.colorScheme.primary)
                        Text(ev, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                // Email: a To/Subject draft preview.
                "draft" -> if (blockLines.isNotEmpty()) {
                    Card(
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(Modifier.padding(JarvisSpacing.sm)) {
                            blockLines.forEach { Text(it, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
}

/** Split an agent answer into (spoken body, block label, block lines) on a Sources/EVENTS/DRAFT marker. */
private fun splitStructured(text: String): Triple<String, String?, List<String>> {
    val markers = listOf("Sources:", "EVENTS:", "DRAFT:")
    val hit = markers.mapNotNull { m -> text.indexOf(m, ignoreCase = true).takeIf { it >= 0 }?.let { m to it } }
        .minByOrNull { it.second } ?: return Triple(text, null, emptyList())
    val (marker, idx) = hit
    val body = text.substring(0, idx).trim().ifBlank { text }
    val lines = text.substring(idx + marker.length)
        .split("\n").map { it.trim().removePrefix("-").trim().removePrefix("•").trim() }
        .filter { it.isNotBlank() }
    return Triple(body, marker.removeSuffix(":").lowercase(), lines)
}

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "research" -> Icons.Outlined.Search
    "calendar" -> Icons.Outlined.CalendarMonth
    "email" -> Icons.Outlined.MailOutline
    "coding" -> Icons.Outlined.Code
    "vision" -> Icons.Outlined.Visibility
    "error" -> Icons.Outlined.ErrorOutline
    else -> Icons.Outlined.AutoAwesome
}
