package com.echo.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.app.HomeViewModel
import com.echo.app.translate.LiveLine
import com.echo.app.translate.TranslateMode
import com.echo.app.translate.TranslationController
import com.echo.app.ui.components.JarvisPrimaryButton
import com.echo.app.ui.components.JarvisSecondaryButton
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

private val LANGS = listOf("" to "Auto", "zh-Hans" to "Mandarin", "fr" to "French", "ar" to "Arabic")

/**
 * Translate — live conversation / listen modes (Gemini Live Translate) and "say it". Voice-first:
 * everything here can also be started from the glasses ("Jarvis, translate", "Jarvis, listen and
 * translate", "how do I say … in Mandarin", "translate this"); the screen is for showing the other
 * person, reading along, and stopping.
 */
@Composable
fun TranslateScreen(vm: HomeViewModel) {
    val u = vm.translateUi
    when {
        u.active -> LiveTranslation(vm)
        u.saving || u.summary.isNotBlank() || (u.lines.isNotEmpty() && u.status.startsWith("Stopped")) -> Finished(vm)
        else -> TranslateHome(vm)
    }
}

@Composable
private fun TranslateHome(vm: HomeViewModel) {
    var lang by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var sayLang by remember { mutableStateOf("zh-Hans") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        if (vm.translateUi.error.isNotBlank()) {
            Text(vm.translateUi.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            Text("Conversation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Two-way. You hear them in English in your glasses; they hear you in their language from the phone speaker. " +
                    "Hold the phone between you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LangPicker(selected = lang, options = LANGS, onSelect = { lang = it })
            JarvisPrimaryButton(
                "Start conversation",
                onClick = { vm.startTranslation(TranslateMode.CONVERSATION, lang.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Or say: “Jarvis, translate” · “Jarvis, translate to French”")
        }
        SectionCard {
            Text("Listen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "One-way. A meeting, talk, or announcement in any language → English in your ear. Nothing plays out loud.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            JarvisSecondaryButton("Start listening", onClick = { vm.startTranslation(TranslateMode.LISTEN) }, modifier = Modifier.fillMaxWidth())
            Hint("Or say: “Jarvis, listen and translate”")
        }
        SectionCard {
            Text("Say it", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(
                value = phrase, onValueChange = { phrase = it },
                placeholder = { Text("e.g. What is your best price?") },
                modifier = Modifier.fillMaxWidth(),
            )
            LangPicker(selected = sayLang, options = LANGS.drop(1), onSelect = { sayLang = it })
            JarvisSecondaryButton(
                "Say it out loud", enabled = phrase.isNotBlank() && !vm.busy,
                onClick = { vm.sayPhrase(phrase, sayLang) }, modifier = Modifier.fillMaxWidth(),
            )
            vm.lastPhrase?.let { p ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { vm.repeatPhrase() }.padding(JarvisSpacing.md),
                ) {
                    Text(p.translation, fontSize = 28.sp, lineHeight = 36.sp, color = MaterialTheme.colorScheme.onSurface)
                    if (p.romanization.isNotBlank()) Text(p.romanization, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text("${p.langName} · tap to say it again", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Hint("Or say: “Jarvis, how do I say ‘where is the taxi’ in Arabic?”")
        }
        SectionCard {
            Text("Read & translate", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Look at a sign, menu or label and say “Jarvis, translate this” — the glasses take a photo and read it to you in English.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveTranslation(vm: HomeViewModel) {
    val u = vm.translateUi
    val cyan = MaterialTheme.colorScheme.primaryContainer
    val listState = rememberLazyListState()
    LaunchedEffect(u.lines.size, u.lines.lastOrNull()?.translation?.length) {
        if (u.lines.isNotEmpty()) listState.animateScrollToItem(u.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.lg)) {
        Spacer(Modifier.height(JarvisSpacing.md))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
            val pair = if (u.mode == TranslateMode.LISTEN) "LISTEN → ENGLISH"
            else if (u.theirLang.isBlank()) "DETECTING… ⇄ ENGLISH"
            else "${TranslationController.langName(u.theirLang).uppercase()} ⇄ ENGLISH"
            StatusChip("LIVE · $pair", accent = cyan)
            u.lagMs?.let { Text("≈${"%.1f".format(it / 1000.0)} s", style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.height(JarvisSpacing.sm))
        Text(u.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val routes = buildList {
            if (u.earRoute.isNotBlank()) add("Them → ${u.earRoute}")
            if (u.mode == TranslateMode.CONVERSATION && u.speakerRoute.isNotBlank()) add("You → ${u.speakerRoute}")
        }.joinToString("  ·  ")
        if (routes.isNotBlank()) Text(routes, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (u.error.isNotBlank()) Text(u.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(JarvisSpacing.md))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
        ) {
            if (u.lines.isEmpty()) item {
                Text(
                    if (u.mode == TranslateMode.LISTEN) "Listening… speech in any language will appear here in English."
                    else "Listening… let them speak first — I'll detect their language.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(u.lines, key = { it.id }) { line -> LineBubble(line) }
        }
        Spacer(Modifier.height(JarvisSpacing.md))
        JarvisPrimaryButton("Stop translating", onClick = vm::stopTranslation, modifier = Modifier.fillMaxWidth())
        Hint("Or say “stop translating”, or press the glasses button.")
        Spacer(Modifier.height(JarvisSpacing.md))
    }
}

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
            Text(
                if (them) "THEM · ${TranslationController.langName(line.lang)}" else "YOU",
                style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Them: the English you heard is the headline; you: the translation they heard is the headline
            // (big, so you can also turn the phone around and show it).
            val headline = line.translation.ifBlank { "…" }
            Text(
                headline,
                style = if (them) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (them) FontWeight.Normal else FontWeight.Medium,
                color = if (them) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (line.text.isNotBlank()) {
                Text(line.text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Finished(vm: HomeViewModel) {
    val u = vm.translateUi
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        StatusChip(if (u.saving) "SAVING TO MEMORY…" else u.status.uppercase(), accent = if (u.saving) JarvisTheme.colors.presenceAmber else MaterialTheme.colorScheme.primaryContainer)
        if (u.summary.isNotBlank()) SectionCard {
            Text("Summary", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(u.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Hint("Ask later: “Jarvis, what did the supplier say about the price?”")
        }
        u.lines.forEach { LineBubble(it) }
        JarvisSecondaryButton("Done", onClick = vm::clearTranslation, modifier = Modifier.fillMaxWidth())
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(JarvisSpacing.md),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm),
    ) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
