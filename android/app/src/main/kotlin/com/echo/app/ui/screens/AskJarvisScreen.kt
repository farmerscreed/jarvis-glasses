package com.echo.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip

/**
 * UI-2: the "Ask JARVIS" deliberate-lane surface (from the Stitch `ask_jarvis_multimodal_conversation`
 * design). A reviewable thread of agent results — research / calendar / email / coding — submitted by
 * TEXT, with the latest captured photo pinned as context (voice-vision discussion), and confirm-before-
 * act for outward actions. The fast voice loop stays in the Live console; this is the patient lane.
 */
@Composable
fun AskJarvisScreen(vm: HomeViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Pin the most recent photo as "context" to discuss (mirrors the design's pinned-context card).
    LaunchedEffect(Unit) { vm.refreshLibrary() }
    val pinned = vm.gallery.firstOrNull()
    val pinnedImage = rememberLocalImage(pinned?.metadata?.get("localMediaPath"), targetPx = 128)

    // Auto-scroll to the newest turn.
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
                        modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.medium),
                    )
                    Column {
                        Text("PINNED CONTEXT", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.primary)
                        Text(
                            pinned?.text?.take(60) ?: "Your latest capture",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                        Text("Ask JARVIS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(JarvisSpacing.sm))
                        Text(
                            "The deliberate lane. Try: “research the best noise-cancelling headphones”, " +
                                "“what's on my calendar this week”, “draft an email to Sam about Friday”, " +
                                "or “fix the typo in the README”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(vm.askThread.size) { i -> AskBubble(vm.askThread[i]) }
        }

        // Input row.
        Row(
            Modifier.fillMaxWidth().padding(vertical = JarvisSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask JARVIS…") },
                singleLine = false,
            )
            IconButton(
                onClick = { if (input.isNotBlank() && !vm.busy) { vm.askJarvis(input); input = "" } },
                enabled = input.isNotBlank() && !vm.busy,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "send", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (vm.busy) {
            Text(vm.status, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = JarvisSpacing.sm))
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
    } else {
        val error = turn.kind == "error"
        Card(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(JarvisSpacing.md), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                Text(
                    turn.kind.uppercase(),
                    style = JarvisTheme.dataMono,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyMedium.let { if (error) it.copy(fontStyle = FontStyle.Italic) else it },
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
