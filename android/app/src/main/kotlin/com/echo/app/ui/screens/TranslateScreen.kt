package com.echo.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.echo.app.HomeViewModel
import com.echo.app.translate.LiveLine
import com.echo.app.translate.TheirStrings
import com.echo.app.translate.TranslateMode
import com.echo.app.translate.TranslationController
import com.echo.app.ui.components.JarvisPrimaryButton
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme
import kotlinx.coroutines.delay

private val LANGS = listOf("" to "Auto", "zh-Hans" to "Mandarin", "fr" to "French", "ar" to "Arabic")
private const val LIVE_WINDOW_MS = 1800L

/**
 * Translate. Three readers, three layouts:
 *  - **Face-to-face** (conversation): the screen is SPLIT. The top half faces the other person
 *    (upside-down by default, so they can read it across a table; one tap turns it upright for
 *    side-by-side) and speaks to them in THEIR language — your words, translated, big, plus their own
 *    words so they can see they were understood. The bottom half is yours, in English.
 *  - **Captions** (listen mode): large rolling English subtitles for meetings/talks.
 *  - **Show them** (say-it): a phrase full-screen in huge type, to hold up to someone.
 * Everything can also be started hands-free from the glasses; the screen is for reading and stopping.
 */
@Composable
fun TranslateScreen(vm: HomeViewModel) {
    val u = vm.translateUi
    when {
        u.active && u.mode == TranslateMode.CONVERSATION -> FaceToFace(vm)
        u.active -> Captions(vm)
        u.saving || u.summary.isNotBlank() || (u.lines.isNotEmpty() && u.status.startsWith("Stopped")) -> Finished(vm)
        else -> TranslateHome(vm)
    }
}

/** A clock that ticks every 300 ms, so "who's speaking now" highlights fade on time. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(300); now = System.currentTimeMillis() } }
    return now
}

// ── Face-to-face (conversation) ─────────────────────────────────────────────────────────────────

@Composable
private fun FaceToFace(vm: HomeViewModel) {
    val u = vm.translateUi
    val now = rememberNow()
    var acrossTable by rememberSaveable { mutableStateOf(true) } // their half upside-down
    var history by rememberSaveable { mutableStateOf(false) }
    val lastMe = u.lines.lastOrNull { it.who == "me" }
    val lastThem = u.lines.lastOrNull { it.who == "them" }
    val themLive = lastThem != null && now - lastThem.updatedAt < LIVE_WINDOW_MS
    val meLive = lastMe != null && now - lastMe.updatedAt < LIVE_WINDOW_MS

    Column(Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm)) {
        if (history) {
            TranscriptList(u.lines, Modifier.weight(1f))
        } else {
            TheirPanel(
                lang = u.theirLang, lastMe = lastMe, lastThem = lastThem, meLive = meLive, themLive = themLive,
                modifier = Modifier.weight(1f).graphicsLayer { rotationZ = if (acrossTable) 180f else 0f },
            )
        }
        Spacer(Modifier.height(JarvisSpacing.sm))
        ControlBar(
            vm = vm,
            acrossTable = acrossTable, onRotate = { acrossTable = !acrossTable },
            history = history, onHistory = { history = !history },
        )
        Spacer(Modifier.height(JarvisSpacing.sm))
        YourPanel(lastThem = lastThem, lastMe = lastMe, themLive = themLive, theirLang = u.theirLang, modifier = Modifier.weight(1f))
    }
}

/** The half that faces the other person — everything in THEIR language. */
@Composable
private fun TheirPanel(lang: String, lastMe: LiveLine?, lastThem: LiveLine?, meLive: Boolean, themLive: Boolean, modifier: Modifier) {
    val cyan = MaterialTheme.colorScheme.primaryContainer
    val border by animateColorAsState(if (meLive || themLive) cyan else MaterialTheme.colorScheme.outlineVariant, label = "their-border")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(if (meLive || themLive) 2.dp else 1.dp, border, RoundedCornerShape(24.dp))
            .padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
            Box(Modifier.size(8.dp).background(if (themLive) cyan else MaterialTheme.colorScheme.outline, CircleShape))
            Text(TheirStrings.nativeName(lang).uppercase(), style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (themLive) Icon(Icons.Outlined.Mic, contentDescription = null, tint = cyan, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md)) {
            if (lastMe == null || lastMe.translation.isBlank()) {
                // Nothing to show them yet — invite them to talk, in their language (or several).
                Text(
                    if (lang.isBlank()) TheirStrings.MULTI_GREETING else TheirStrings.greeting(lang),
                    fontSize = 26.sp, lineHeight = 34.sp, color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                // What you said, in their language — the main thing they read.
                Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = cyan, modifier = Modifier.size(22.dp).padding(top = 6.dp))
                    Text(
                        lastMe.translation.trim(),
                        fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium,
                        color = if (meLive) cyan else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // Their own words, so they can see they were heard correctly.
            if (lastThem != null && lastThem.text.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp).padding(top = 3.dp))
                    Text(lastThem.text.trim(), fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Your half — English. Their latest words big, your last line small for context. */
@Composable
private fun YourPanel(lastThem: LiveLine?, lastMe: LiveLine?, themLive: Boolean, theirLang: String, modifier: Modifier) {
    val cyan = MaterialTheme.colorScheme.primaryContainer
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, if (themLive) cyan.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        Text("ENGLISH · YOU", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
            if (lastThem == null) {
                Text(
                    if (theirLang.isBlank()) "Listening… let them speak first — I'll detect their language. Hold the phone between you."
                    else "Speak in English — they'll hear you in ${TranslationController.langName(theirLang)} from the phone.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    lastThem.translation.ifBlank { "…" }.trim(),
                    fontSize = 26.sp, lineHeight = 34.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (lastThem.text.isNotBlank()) {
                    Text(lastThem.text.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (lastMe != null && lastMe.text.isNotBlank()) {
                Spacer(Modifier.height(JarvisSpacing.xs))
                Text("YOU SAID", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lastMe.text.trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ControlBar(vm: HomeViewModel, acrossTable: Boolean, onRotate: () -> Unit, history: Boolean, onHistory: () -> Unit) {
    val u = vm.translateUi
    val cyan = MaterialTheme.colorScheme.primaryContainer
    Column(verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            val pair = if (u.theirLang.isBlank()) "DETECTING ⇄ EN" else "${TranslationController.langName(u.theirLang).uppercase()} ⇄ EN"
            StatusChip("LIVE · $pair", accent = cyan)
            Spacer(Modifier.width(JarvisSpacing.sm))
            u.lagMs?.let { Text("≈${"%.1f".format(it / 1000.0)}s", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRotate) {
                Icon(Icons.Outlined.ScreenRotation, contentDescription = if (acrossTable) "Turn their side upright" else "Turn their side to face across the table",
                    tint = if (acrossTable) cyan else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onHistory) {
                Icon(if (history) Icons.Outlined.ViewAgenda else Icons.Outlined.Forum, contentDescription = if (history) "Face-to-face view" else "Full transcript",
                    tint = if (history) cyan else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
            Text(
                listOfNotNull(
                    u.error.ifBlank { null } ?: u.status.ifBlank { null },
                    u.speakerRoute.takeIf { it.isNotBlank() }?.let { "they hear: $it" },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = if (u.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            JarvisPrimaryButton("Stop", onClick = vm::stopTranslation)
        }
    }
}

@Composable
private fun TranscriptList(lines: List<LiveLine>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.translation?.length) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
        if (lines.isEmpty()) item {
            Text("Nothing said yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(lines, key = { it.id }) { LineBubble(it) }
    }
}

// ── Captions (listen mode) ──────────────────────────────────────────────────────────────────────

@Composable
private fun Captions(vm: HomeViewModel) {
    val u = vm.translateUi
    val now = rememberNow()
    val listState = rememberLazyListState()
    LaunchedEffect(u.lines.size, u.lines.lastOrNull()?.translation?.length) {
        if (u.lines.isNotEmpty()) listState.animateScrollToItem(u.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.lg, vertical = JarvisSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
            val from = if (u.theirLang.isBlank()) "ANY LANGUAGE" else TranslationController.langName(u.theirLang).uppercase()
            StatusChip("LISTENING · $from → EN", accent = MaterialTheme.colorScheme.primaryContainer)
            u.lagMs?.let { Text("≈${"%.1f".format(it / 1000.0)}s", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(
            listOfNotNull(u.error.ifBlank { null } ?: u.status, u.earRoute.takeIf { it.isNotBlank() }?.let { "audio: $it" }).joinToString("  ·  "),
            style = MaterialTheme.typography.labelSmall,
            color = if (u.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = JarvisSpacing.xs),
        )
        Spacer(Modifier.height(JarvisSpacing.md))
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md)) {
            if (u.lines.isEmpty()) item {
                Text("Listening… speech in any language appears here as English captions.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(u.lines, key = { it.id }) { line ->
                val latest = line.id == u.lines.last().id
                val live = latest && now - line.updatedAt < LIVE_WINDOW_MS
                Column {
                    Text(
                        line.translation.ifBlank { "…" }.trim(),
                        fontSize = if (latest) 28.sp else 18.sp,
                        lineHeight = if (latest) 36.sp else 26.sp,
                        color = when {
                            live -> MaterialTheme.colorScheme.primary
                            latest -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (latest && line.text.isNotBlank()) {
                        Text(line.text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(JarvisSpacing.md))
        JarvisPrimaryButton("Stop listening", onClick = vm::stopTranslation, modifier = Modifier.fillMaxWidth())
        Text("Or say “stop translating”, or press the glasses button.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = JarvisSpacing.xs))
        Spacer(Modifier.height(JarvisSpacing.sm))
    }
}

// ── Home (start + say-it) ───────────────────────────────────────────────────────────────────────

@Composable
private fun TranslateHome(vm: HomeViewModel) {
    var lang by rememberSaveable { mutableStateOf("") }
    var phrase by rememberSaveable { mutableStateOf("") }
    var sayLang by rememberSaveable { mutableStateOf("zh-Hans") }
    var showThem by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        if (vm.translateUi.error.isNotBlank()) {
            Text(vm.translateUi.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            CardTitle("Conversation", "FACE TO FACE")
            Body("Two-way. The screen splits: the top half faces them in their language, the bottom half is yours in English. You also hear them in your glasses; they hear you from the phone speaker.")
            LangPicker(selected = lang, options = LANGS, onSelect = { lang = it })
            JarvisPrimaryButton("Start conversation", onClick = { vm.startTranslation(TranslateMode.CONVERSATION, lang.ifBlank { null }) }, modifier = Modifier.fillMaxWidth())
            Hint("Or say: “Jarvis, translate” · “Jarvis, translate to French”")
        }
        SectionCard {
            CardTitle("Listen", "CAPTIONS")
            Body("One-way. A meeting, talk or announcement in any language → English in your ear and as big captions here. Nothing plays out loud.")
            JarvisSecondaryButton("Start listening", onClick = { vm.startTranslation(TranslateMode.LISTEN) }, modifier = Modifier.fillMaxWidth())
            Hint("Or say: “Jarvis, listen and translate”")
        }
        SectionCard {
            CardTitle("Say it", "OUT LOUD")
            OutlinedTextField(value = phrase, onValueChange = { phrase = it }, placeholder = { Text("e.g. What is your best price?") }, modifier = Modifier.fillMaxWidth())
            LangPicker(selected = sayLang, options = LANGS.drop(1), onSelect = { sayLang = it })
            JarvisSecondaryButton("Say it out loud", enabled = phrase.isNotBlank() && !vm.busy, onClick = { vm.sayPhrase(phrase, sayLang) }, modifier = Modifier.fillMaxWidth())
            vm.lastPhrase?.let { p ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(JarvisSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs),
                ) {
                    Text("${p.langName.uppercase()} · ${p.phrase}", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(p.translation, fontSize = 28.sp, lineHeight = 36.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (p.romanization.isNotBlank()) Text(p.romanization, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm), modifier = Modifier.padding(top = JarvisSpacing.xs)) {
                        JarvisSecondaryButton("Play again", onClick = { vm.repeatPhrase() })
                        JarvisPrimaryButton("Show them", onClick = { showThem = true })
                    }
                }
            }
            Hint("Or say: “Jarvis, how do I say ‘where is the taxi’ in Arabic?”")
        }
        SectionCard {
            CardTitle("Read & translate", "CAMERA")
            Body("Look at a sign, menu or label and say “Jarvis, translate this”. The glasses take a photo and read it to you in English; it also appears on the Live screen.")
        }
    }
    val p = vm.lastPhrase
    if (showThem && p != null) ShowThemDialog(text = p.translation, sub = p.romanization, onClose = { showThem = false })
}

/** Full-screen phrase in huge type, to hold up to the other person. Tap the rotate icon to flip it. */
@Composable
private fun ShowThemDialog(text: String, sub: String, onClose: () -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(JarvisSpacing.lg)) {
            Column(
                Modifier.align(Alignment.Center).graphicsLayer { rotationZ = if (flipped) 180f else 0f },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
            ) {
                Text(text, fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
                if (sub.isNotBlank()) Text(sub, fontSize = 20.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.lg)) {
                IconButton(onClick = { flipped = !flipped }) { Icon(Icons.Outlined.ScreenRotation, contentDescription = "Flip", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// ── Finished ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Finished(vm: HomeViewModel) {
    val u = vm.translateUi
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        StatusChip(if (u.saving) "SAVING TO MEMORY…" else u.status.uppercase(), accent = if (u.saving) JarvisTheme.colors.presenceAmber else MaterialTheme.colorScheme.primaryContainer)
        if (u.summary.isNotBlank()) SectionCard {
            CardTitle("Summary", "SAVED TO MEMORY")
            Text(u.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Hint("Ask later: “Jarvis, what price did the supplier quote?”")
        }
        u.lines.forEach { LineBubble(it) }
        JarvisSecondaryButton("Done", onClick = vm::clearTranslation, modifier = Modifier.fillMaxWidth())
    }
}

// ── shared bits ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LineBubble(line: LiveLine) {
    val them = line.who == "them"
    val cyan = MaterialTheme.colorScheme.primaryContainer
    val shape = if (them) RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp) else RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (them) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxWidth(0.9f).clip(shape)
                .background(if (them) cyan.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (them) Modifier.border(1.dp, cyan.copy(alpha = 0.30f), shape) else Modifier)
                .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm + JarvisSpacing.xs),
        ) {
            Text(if (them) "THEM · ${TranslationController.langName(line.lang).uppercase()}" else "YOU",
                style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Them: the English you heard leads; you: the translation they heard leads.
            Text(
                line.translation.ifBlank { "…" }.trim(),
                style = if (them) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (them) FontWeight.Normal else FontWeight.Medium,
                color = if (them) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (line.text.isNotBlank()) Text(line.text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LangPicker(selected: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
        options.forEach { (code, label) ->
            val on = code == selected
            val accent = MaterialTheme.colorScheme.primaryContainer
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) accent else Color.Transparent)
                    .border(1.dp, if (on) accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .clickable { onSelect(code) }
                    .padding(horizontal = JarvisSpacing.md, vertical = JarvisSpacing.sm),
            )
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(JarvisSpacing.md),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) { content() }
}

@Composable
private fun CardTitle(title: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(tag, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.primaryContainer)
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
