package com.echo.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.LiveBars
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoCard
import com.echo.app.ui.components.NeoChip
import com.echo.app.ui.components.NeoTopBar
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.RoundStyle
import com.echo.app.ui.components.SettingsGroup
import com.echo.app.ui.components.SettingsRow
import com.echo.app.ui.components.SourceCard
import com.echo.app.ui.components.rememberLocalImage
import com.echo.app.ui.components.splitSources
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Research (redesign 2026-10): ASK — a big question box, "research what I'm looking at" (glasses photo),
 * quick starts and recent research — and ANSWER — headline figure, short spoken answer, sources,
 * follow-up. Both are one screen driven by [HomeViewModel.researchUi].
 */
@Composable
fun ResearchScreen(vm: HomeViewModel, onBack: () -> Unit, onVoice: () -> Unit) {
    val r = vm.researchUi
    if (r.running || r.hasAnswer || r.error.isNotBlank()) {
        BackHandler { vm.clearResearch() }
        ResearchAnswer(vm, onBack = { vm.clearResearch() })
    } else {
        BackHandler { onBack() }
        ResearchAsk(vm, onBack = onBack, onVoice = onVoice)
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResearchAsk(vm: HomeViewModel, onBack: () -> Unit, onVoice: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val recent = remember(vm.timeline) { vm.timeline.filter { "research" in it.tags }.take(5) }
    var opened by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar("Research", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("What do you want to know?", style = Neo.display.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Neo.Card).border(1.dp, Neo.AccentBorder, RoundedCornerShape(24.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, minLines = 2, maxLines = 5,
                    placeholder = { Text("e.g. Price of a 5 kVA inverter in Port Harcourt", style = Neo.body.copy(fontSize = 17.sp, color = Neo.Faint)) },
                    textStyle = Neo.body.copy(fontSize = 17.sp, lineHeight = 24.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.startResearch(q) }),
                    colors = neoFieldColors().let { androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Neo.Text, unfocusedTextColor = Neo.Text, cursorColor = Neo.Cyan) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoLabel("Searches the web · cites sources", color = Neo.Faint, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    RoundButton(Icons.Outlined.Mic, "Ask by voice", onClick = onVoice)
                    RoundButton(Icons.AutoMirrored.Outlined.ArrowForward, "Research it", onClick = { vm.startResearch(q) }, style = RoundStyle.Primary, enabled = q.isNotBlank())
                }
            }
            // Look & research.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(Neo.Amber.copy(alpha = 0.12f), Neo.Amber.copy(alpha = 0.03f))))
                    .border(1.dp, Neo.Amber.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                    .clickable { vm.researchWhatImLookingAt() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Neo.Amber.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Visibility, contentDescription = null, tint = Neo.Amber, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Research what I'm looking at", style = Neo.display.copy(fontSize = 16.sp))
                    Text("Glasses take a photo — price, where to buy, reviews", style = Neo.body.copy(fontSize = 13.sp, color = Color(0xFF9AA8AD)))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Price of ", "Where to buy ", "Compare ", "Latest on ").forEach { s -> NeoChip(s.trim() + "…", false, onClick = { q = s }) }
            }
            if (recent.isNotEmpty()) {
                SettingsGroup("Recent research") {
                    recent.forEachIndexed { i, m ->
                        val (body, _) = splitSources(m.text.orEmpty())
                        val title = body.substringAfter("Research — ").substringBefore(":\n").take(60)
                        val id = m.id ?: "$i"
                        SettingsRow(
                            icon = null, title = title,
                            sub = (m.createdAt?.let { DATE.format(it) } ?: "") + (if (m.mediaPath != null || m.metadata["localMediaPath"] != null) " · from a photo" else ""),
                            divider = i < recent.size - 1, onClick = { opened = if (opened == id) null else id },
                        )
                        if (opened == id) Text(body.substringAfter(":\n").trim(), style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Neo.TextSoft), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ResearchAnswer(vm: HomeViewModel, onBack: () -> Unit) {
    val r = vm.researchUi
    val ctx = LocalContext.current
    var follow by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Neo.Ground).navigationBarsPadding().imePadding()) {
        NeoTopBar(
            if (r.running) "Researching…" else "${r.searches} searches · ${r.seconds} s · saved".uppercase(),
            onBack = onBack,
            trailing = {
                if (r.hasAnswer) RoundButton(Icons.Outlined.Share, "Share", onClick = {
                    val text = buildString {
                        append(r.question).append("\n\n")
                        if (r.headline.isNotBlank()) append(r.headline).append("\n")
                        append(r.spoken)
                        if (r.sources.isNotEmpty()) append("\n\nSources:\n").append(r.sources.joinToString("\n") { it.second })
                    }
                    ctx.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Share research",
                    ))
                })
            },
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // What was asked (+ the photo for look-and-research).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val img = rememberLocalImage(r.photoPath, targetPx = 192)
                if (img != null) Image(BitmapPainter(img), contentDescription = "Your photo", contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    MonoLabel(if (r.photoPath != null) "You asked · from your glasses" else "You asked", color = if (r.photoPath != null) Neo.Amber else Neo.Muted)
                    Text(r.question + (if (r.item.isNotBlank()) " — ${r.item}" else ""), style = Neo.body.copy(fontSize = 15.sp))
                }
            }
            when {
                r.running -> NeoCard(hero = true, padding = 22.dp) {
                    MonoLabel(r.stage.ifBlank { "Working…" }, color = Neo.Cyan)
                    LiveBars(active = true, bars = 9, maxHeight = 30.dp)
                    Text("Usually 10–25 seconds. I'll read the answer when it's ready.", style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))
                }
                r.error.isNotBlank() -> NeoCard {
                    Text(r.error, style = Neo.body.copy(fontSize = 15.sp, color = Neo.Danger))
                    GhostButton("Try again", onClick = { if (r.photoPath != null) vm.researchWhatImLookingAt(r.question) else vm.startResearch(r.question) }, height = 44.dp)
                }
                else -> {
                    NeoCard(hero = true, padding = 22.dp) {
                        MonoLabel("The answer", color = Neo.Cyan)
                        if (r.headline.isNotBlank()) Text(r.headline, style = Neo.display.copy(fontSize = 32.sp, lineHeight = 38.sp, color = Color.White))
                        Text(r.spoken, style = Neo.body.copy(fontSize = 15.sp, lineHeight = 23.sp, color = Neo.TextSoft))
                        GhostButton("Listen again", onClick = vm::speakResearch, icon = Icons.AutoMirrored.Outlined.VolumeUp, height = 42.dp, color = Neo.CyanPale)
                    }
                    if (r.sources.isNotEmpty()) {
                        MonoLabel("Sources")
                        r.sources.forEachIndexed { i, (title, url) -> SourceCard(i + 1, title, url) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // Follow-up keeps the thread.
        if (r.hasAnswer) Row(
            Modifier.fillMaxWidth().background(Neo.Bar).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = follow, onValueChange = { follow = it }, singleLine = true,
                placeholder = { Text("Ask a follow-up…", style = Neo.body.copy(fontSize = 15.sp, color = Neo.Faint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.startResearch(follow, followUp = true); follow = "" }),
                shape = RoundedCornerShape(28.dp), colors = neoFieldColors(), modifier = Modifier.weight(1f),
            )
            RoundButton(Icons.AutoMirrored.Outlined.ArrowForward, "Ask follow-up", onClick = { vm.startResearch(follow, followUp = true); follow = "" },
                style = RoundStyle.Primary, size = 52.dp, enabled = follow.isNotBlank())
        }
    }
}
