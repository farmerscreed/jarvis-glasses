package com.echo.app.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.echo.app.ui.components.SectionLabel
import com.echo.app.ui.components.StatusChip
import com.echo.app.ui.theme.JarvisSpacing
import com.echo.app.ui.theme.JarvisTheme

private enum class HelpTab(val label: String, val icon: ImageVector) {
    Hub("Hub", Icons.AutoMirrored.Outlined.MenuBook),
    Hardware("Gestures", Icons.Outlined.Watch),
    Tutorials("Guides", Icons.AutoMirrored.Outlined.MenuBook),
    Commands("Say", Icons.Outlined.RecordVoiceOver),
}

/**
 * Help & Learn center (help_hub / help_gestures_buttons / help_feature_walkthroughs /
 * help_voice_commands). A self-contained overlay opened from the top-bar "?" — its own tab strip,
 * device-back returns to the app. Content reflects what the app actually does today (decoded
 * gestures from docs/recon/Glasses_Controls.md; the real voice/vision/memory loop).
 */
@Composable
fun HelpScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var tab by remember { mutableStateOf(HelpTab.Hub) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = JarvisSpacing.sm)
                    .height(56.dp),
            ) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "back", tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "Help Center",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    HelpTab.Hub -> HubPage(onPick = { tab = it })
                    HelpTab.Hardware -> GesturesPage()
                    HelpTab.Tutorials -> TutorialsPage()
                    HelpTab.Commands -> CommandsPage()
                }
            }

            // Help's own bottom tab strip
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Column(Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = JarvisSpacing.sm),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        HelpTab.entries.forEach { t ->
                            val active = t == tab
                            val tint = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs),
                                modifier = Modifier
                                    .clickable { tab = t }
                                    .padding(horizontal = JarvisSpacing.sm, vertical = JarvisSpacing.xs),
                            ) {
                                Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(22.dp))
                                Text(t.label, style = MaterialTheme.typography.labelSmall, color = tint)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubPage(onPick: (HelpTab) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        item {
            Spacer(Modifier.height(JarvisSpacing.sm))
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(JarvisSpacing.lg), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    SectionLabel("Ethereal Sentinel", icon = Icons.Outlined.Lightbulb)
                    Text("How JARVIS Works", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Your glasses are the eyes and ears; the phone is the brain. Capture a photo or " +
                            "speak, and JARVIS remembers it, then answers from your own memory — even offline. " +
                            "Look at something and ask “how much is this?” — it takes a photo and researches it. " +
                            "Double-tap the glasses to hear any language in English; say “let me talk” to reply. " +
                            "Ask JARVIS (the ✨ on the Live screen) to research, check email & calendar, and discuss your photos.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            HelpCategory("Gestures & Buttons", "The physical controls on your frames.", Icons.Outlined.Watch) { onPick(HelpTab.Hardware) }
        }
        item {
            HelpCategory("Voice Commands", "What you can say to JARVIS.", Icons.Outlined.RecordVoiceOver) { onPick(HelpTab.Commands) }
        }
        item {
            HelpCategory("Walkthroughs", "Step-by-step guides for each feature.", Icons.AutoMirrored.Outlined.MenuBook) { onPick(HelpTab.Tutorials) }
        }
        item {
            // Pro tip
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(JarvisSpacing.lg), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    SectionLabel("Pro Tip", icon = Icons.Outlined.Lightbulb)
                    Text(
                        "Say “Jarvis” (with hands-free on) to start a voice turn without touching anything. Double-tap the back of the glasses to translate whatever you're hearing.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}

@Composable
private fun HelpCategory(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(JarvisSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
        ) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primaryContainer) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Gestures & Buttons — the REAL decoded map (docs/recon/Glasses_Controls.md). */
@Composable
private fun GesturesPage() {
    val gestures = listOf(
        Triple("Single-click FRONT", "Takes a photo", "JARVIS auto-syncs it and captions it into your memory."),
        Triple("Double-click BACK", "Listen & translate (on / off)", "Everything you hear — a person, TV, a meeting — comes into your ear in English. JARVIS says “Translator on” / “Translator off”. It does NOT take a photo. To reply, say “Jarvis, let me talk”."),
        Triple("Hold BACK", "Starts audio recording", "Synced and transcribed into a voice note when you stop."),
        Triple("Double-click FRONT", "Starts / ends video", "Preserved on the glasses; synced to your gallery."),
        Triple("Double-click RIGHT", "Play / pause media", "Handled natively by the glasses."),
        Triple("Slide front ↔ back", "Volume up / down", "Handled natively by the glasses."),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        item {
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text("Master the controls", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(JarvisSpacing.xs))
            Text(
                "Buttons on the frames capture in the glasses themselves — JARVIS reacts the moment you press.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(gestures.size) { i ->
            val (gesture, native, ours) = gestures[i]
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(JarvisSpacing.md), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.xs)) {
                    Text(gesture, style = JarvisTheme.dataMono, color = MaterialTheme.colorScheme.primaryContainer)
                    Text(native, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(ours, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}

/** Walkthroughs — the features that actually work today, as numbered steps. */
@Composable
private fun TutorialsPage() {
    val tutorials = listOf(
        Triple("Ask your memory", listOf("Tap the mic or say “Jarvis”", "Ask a question", "Hear the answer from your memories"), "Works offline too — JARVIS searches on-device when there's no signal."),
        Triple("Look & Ask", listOf("Say “what am I looking at?”", "JARVIS captures the frame", "It describes what you're seeing, out loud"), "Great for “what is this?” moments — works mid-conversation."),
        Triple("Look & research", listOf("Look at the thing (a cot, a fan, a product)", "Say “Jarvis, how much is this?” or “research this”", "It says what it sees, then gives prices, where to buy and sources"), "Takes ~15–25 s. Prices are for Nigeria unless you name a place. Saved to your memory with the photo."),
        Triple("Capture a memory", listOf("Single-click the FRONT button", "The photo syncs automatically", "It's captioned into your timeline"), "Find it later in Timeline and Gallery."),
        Triple("Voice notes", listOf("Hold the BACK button to record", "Click BACK to stop", "It's transcribed into a note"), "Meeting capture, hands-free."),
        Triple("Listen & translate", listOf("Double-click BACK on the glasses (or tap “Start listening”)", "Hear “Translator on. Listening.”", "Whatever is said around you — any language — plays in English in your ear"), "For TV, videos, talks, or someone speaking to you — any language, and English speech is shown too. Keeps working with the screen off (a “JARVIS is translating” notification has a Stop button). If the phone is locked when you double-tap, unlock it first. Double-click BACK again (or say “stop translating”) to stop — it's summarized into your memory."),
        Triple("Reply in a conversation", listOf("While listening, say “Jarvis, let me talk” (or tap “Talk to them”)", "Hold the phone between you and speak English", "They hear you in their language from the phone speaker"), "Say “Jarvis, just listen” to go back to listening only. Or start straight away: Translate → “Start a conversation”, or say “Jarvis, translate to French”."),
        Triple("Type instead of speaking", listOf("During translation, type in the box at the bottom", "Tap send", "It's translated, shown on their side and said out loud"), "Use it when a word was misheard — tap “YOU SAID” to correct your last sentence — or when it's too noisy."),
        Triple("Translate a sign or menu", listOf("Look at it", "Say “Jarvis, translate this”", "Hear it in English"), "Also reads English text aloud: “read this to me”."),
        Triple("Ask JARVIS (get things done)", listOf("Tap ✨ on the Live screen (or type / speak)", "Ask it to research, check your calendar, or draft an email", "Read the answer card — research shows its sources"), "The deliberate lane: patient, tool-using, reviewable. Research works anywhere; email & calendar still need the PC agent."),
        Triple("Drill down on a photo", listOf("Open a photo in Gallery", "Tap “Ask about this photo”", "Ask “how many…?” or “read the text”, by text or mic"), "JARVIS re-examines that exact photo to answer follow-ups."),
        Triple("Check your glasses", listOf("Open Settings → Glasses & device", "See battery %, connection, and the buttons map", "Tap “Fix connection” if the link drops"), "Battery also shows on the Live screen."),
        Triple("Voice recognition", listOf("Online, your voice is understood in the cloud (tuned for your accent)", "Offline, a model on the phone takes over", "Settings → Voice recognition shows which is in use"), "If a word keeps coming out wrong, type it instead (Ask JARVIS, or the box during translation)."),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        item {
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text("Master your glasses", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        items(tutorials.size) { i ->
            val (title, steps, note) = tutorials[i]
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(JarvisSpacing.lg), verticalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primaryContainer)
                    steps.forEachIndexed { n, step ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(JarvisSpacing.sm)) {
                            Box(
                                Modifier.size(24.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("${n + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primaryContainer) }
                            Text(step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text(note, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}

/** Voice commands — example phrases grouped by what they exercise. */
@Composable
private fun CommandsPage() {
    val groups = listOf(
        "The basics" to listOf("“Jarvis, what's on my schedule?”", "“Where did I park?”", "“What did I note earlier?”"),
        "Memory" to listOf("“What did Sam say about the budget?”", "“When did I last see my keys?”", "“Find that restaurant we talked about.”", "“Remember that I parked on level 3.”"),
        "Vision (Look & Ask)" to listOf("“What am I looking at?”", "“Describe this for me.”", "“Read this for me.”"),
        "Look & research (just look at it)" to listOf(
            "“Jarvis, research this cot.”",
            "“How much is this?”   “Where can I buy this?”",
            "“What brand is this?”   “Is this any good?”",
            "“Tell me more about this.”  (photo + web search, with sources)",
        ),
        "Translate" to listOf(
            "Double-click BACK — listen: any language → English in your ear",
            "“Listen and translate.”  (same as the double-tap)",
            "While listening: “Jarvis, let me talk.”   Back: “Jarvis, just listen.”",
            "“Jarvis, translate to French.”  (a two-way conversation straight away)",
            "“How do I say ‘what is your best price’ in Mandarin?”   “Say it again.”",
            "“Translate this.”   “What does this sign say?”",
            "“Stop translating.”",
        ),
        "Get things done (Ask JARVIS)" to listOf(
            "“Jarvis, research the price of 30 kVA inverters in Port Harcourt.”",
            "“What's on my calendar this week?”",
            "“Add lunch to my calendar tomorrow at noon.”  (it confirms first)",
            "“Draft an email to Sam about Friday.”",
            "“Check my email.”",
        ),
        "Discuss a photo" to listOf(
            "Open a photo → “Ask about this photo”",
            "“How many are there?”   “Read the text.”   “What colour is it?”",
        ),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = JarvisSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(JarvisSpacing.md),
    ) {
        item {
            Spacer(Modifier.height(JarvisSpacing.sm))
            Text("Things you can say", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(JarvisSpacing.xs))
            Text(
                "Speak naturally. JARVIS answers from your own memories — and tells you when it doesn't know.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        groups.forEach { (label, phrases) ->
            item { SectionLabel(label) }
            items(phrases.size) { i ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        phrases[i],
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(JarvisSpacing.md),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(JarvisSpacing.lg)) }
    }
}
