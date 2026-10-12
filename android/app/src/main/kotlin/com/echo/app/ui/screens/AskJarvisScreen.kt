package com.echo.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.LiveBars
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoChip
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.RoundStyle
import com.echo.app.ui.components.SourcesRow
import com.echo.app.ui.components.rememberLocalImage

/**
 * Ask JARVIS — the patient, reviewable lane (research / calendar / email / coding / photo questions),
 * restyled 2026-10 to the Neo look. A thread of answer cards by kind (research sources, calendar events,
 * email draft), a pinned photo when discussing one, and a text + mic composer at the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskJarvisScreen(vm: HomeViewModel) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val pinned = vm.askPhoto
    val pinnedImage = rememberLocalImage(pinned?.metadata?.get("localMediaPath"), targetPx = 128)
    val photoMode = pinned != null

    LaunchedEffect(vm.askThread.size) { if (vm.askThread.isNotEmpty()) listState.animateScrollToItem(vm.askThread.size - 1) }

    vm.askConfirmPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = vm::dismissAskConfirm,
            containerColor = Neo.Card,
            title = { Text("Confirm", style = Neo.display.copy(fontSize = 20.sp)) },
            text = { Text(prompt, style = Neo.body.copy(fontSize = 16.sp, color = Neo.TextSoft)) },
            confirmButton = { TextButton(onClick = vm::confirmAsk) { Text("Go ahead", color = Neo.Cyan) } },
            dismissButton = { TextButton(onClick = vm::dismissAskConfirm) { Text("Cancel", color = Neo.Text) } },
        )
    }

    Column(Modifier.fillMaxSize().background(Neo.Ground).navigationBarsPadding().imePadding()) {
        if (pinnedImage != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp))
                    .background(Neo.Card).border(1.dp, Neo.HeroBorder, RoundedCornerShape(18.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(BitmapPainter(pinnedImage), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f)) {
                    MonoLabel("Discussing this photo", color = Neo.Cyan)
                    Text(pinned?.text?.take(80).orEmpty(), style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted), maxLines = 2)
                }
                RoundButton(Icons.Outlined.Close, "Stop discussing this photo", onClick = vm::clearAskPhoto, size = 40.dp)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.askThread.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (photoMode) "Ask about this photo" else "What can I do for you?", style = Neo.display.copy(fontSize = 26.sp, lineHeight = 32.sp))
                    Text(
                        if (photoMode) "Drill down — I look at the photo again to answer." else "I take my time here: research, your calendar, email drafts, code.",
                        style = Neo.body.copy(fontSize = 15.sp, color = Neo.Muted),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val ideas = if (photoMode) listOf("How many are there?", "Read the text", "What colour is it?")
                        else listOf("What's on my calendar this week?", "Draft an email to Sam about Friday", "Research the best inverter batteries")
                        ideas.forEach { s -> NeoChip(s, false, onClick = { input = s }) }
                    }
                }
            }
            items(vm.askThread.size) { i -> AskBubble(vm.askThread[i]) }
            if (vm.busy) item {
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Neo.Card).border(1.dp, Neo.Border, RoundedCornerShape(18.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LiveBars(active = true, bars = 5, maxHeight = 16.dp, barWidth = 3.dp)
                    Text(vm.status, style = Neo.mono.copy(fontSize = 12.sp, letterSpacing = 0.sp, color = Neo.Muted))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Row(
            Modifier.fillMaxWidth().background(Neo.Bar).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, maxLines = 4,
                placeholder = { Text(if (photoMode) "Ask about this photo…" else "Ask JARVIS…", style = Neo.body.copy(fontSize = 15.sp, color = Neo.Faint)) },
                shape = RoundedCornerShape(26.dp), colors = neoFieldColors(), modifier = Modifier.weight(1f),
            )
            RoundButton(Icons.Outlined.Mic, "Ask by voice", onClick = vm::askByVoice, enabled = !vm.busy, style = if (vm.micActive) RoundStyle.Danger else RoundStyle.Plain, size = 48.dp)
            RoundButton(Icons.AutoMirrored.Outlined.Send, "Send", onClick = { if (input.isNotBlank() && !vm.busy) { vm.askJarvis(input); input = "" } },
                enabled = input.isNotBlank() && !vm.busy, style = RoundStyle.Primary, size = 48.dp)
        }
    }
}

@Composable
private fun AskBubble(turn: HomeViewModel.AskTurn) {
    if (turn.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                turn.text, style = Neo.body.copy(fontSize = 15.sp, color = Neo.OnCyan),
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)).background(Neo.Cyan).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        return
    }
    val error = turn.kind == "error"
    val accent = if (error) Neo.Danger else if (turn.kind == "research") Neo.Amber else Neo.Cyan
    val (body, blockLabel, blockLines) = remember(turn.text) { splitStructured(turn.text) }
    val shape = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, if (error) Neo.Danger.copy(alpha = 0.4f) else Neo.Border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(kindIcon(turn.kind), contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
            }
            MonoLabel(turn.kind, color = accent)
        }
        Text(body, style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, fontStyle = if (error) FontStyle.Italic else FontStyle.Normal))
        when (blockLabel) {
            "sources" -> SourcesRow(blockLines.filter { it.startsWith("http") })
            "events" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blockLines.forEach { ev ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Neo.CardLow).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Neo.Cyan))
                        Text(ev, style = Neo.body.copy(fontSize = 14.sp))
                    }
                }
            }
            "draft" -> if (blockLines.isNotEmpty()) Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Neo.CardLow).border(1.dp, Neo.AccentBorder, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                MonoLabel("Draft in your Gmail", color = Neo.Cyan)
                blockLines.forEach { Text(it, style = Neo.body.copy(fontSize = 13.sp, color = Neo.TextSoft)) }
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

