package com.echo.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.echo.app.ui.components.FeatureTile
import com.echo.app.ui.components.GhostButton
import com.echo.app.ui.components.GlowButton
import com.echo.app.ui.components.LiveBars
import com.echo.app.ui.components.LivePill
import com.echo.app.ui.components.MonoLabel
import com.echo.app.ui.components.Neo
import com.echo.app.ui.components.NeoCard
import com.echo.app.ui.components.NeoChip
import com.echo.app.ui.components.NeoTopBar
import com.echo.app.ui.components.RoundButton
import com.echo.app.ui.components.RoundStyle
import kotlinx.coroutines.delay

/** Their-language choices for a two-way chat and for "say it" (code → name). "" = work it out. */
private val TALK_LANGS = listOf("" to "Auto", "zh-Hans" to "Mandarin", "fr" to "French", "ar" to "Arabic", "es" to "Spanish", "pt" to "Portuguese", "de" to "German", "ha" to "Hausa")
private val SAY_LANGS = TALK_LANGS.drop(1)
private const val LIVE_WINDOW_MS = 1800L

/**
 * Translate (redesign 2026-10, docs/UI_REDESIGN.md):
 *  - **Home**: one huge glowing Listen button + three tiles (Talk, Read, Say). Nothing else to read.
 *  - **Listening**: cinema captions — the newest line huge, older ones fading — and a three-button dock.
 *  - **Face to face**: their half faces them (upside-down), a glowing turn bar says whose turn it is,
 *    your half below. Matches the take-turns behaviour of the translator.
 *  - **Say it for me**: a phrase in their language, big, with Play and Show them.
 * Everything can still be started from the glasses; the screen is for reading and steering.
 */
@Composable
fun TranslateScreen(vm: HomeViewModel) {
    val u = vm.translateUi
    when {
        u.active && u.mode == TranslateMode.CONVERSATION -> FaceToFace(vm)
        u.active -> Listening(vm)
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

// ── Home ────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TranslateHome(vm: HomeViewModel) {
    var page by rememberSaveable { mutableStateOf("home") } // home | say
    if (page == "say") {
        BackHandler { page = "home" }
        SayItForMe(vm, onBack = { page = "home" })
        return
    }
    val ctx = LocalContext.current
    var lang by rememberSaveable { mutableStateOf(vm.lastTranslateLang) }
    var pickLang by rememberSaveable { mutableStateOf(false) }
    var readChooser by rememberSaveable { mutableStateOf(false) }

    // Phone camera ("read a sign or menu"): the camera app writes into our cache.
    var pendingPhoto by remember { mutableStateOf<java.io.File?>(null) }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok -> val f = pendingPhoto; if (ok && f != null) vm.readPhonePhoto(f) }
    fun openCamera() {
        val dir = java.io.File(ctx.cacheDir, "camera").apply { mkdirs() }
        val f = java.io.File(dir, "read-${System.currentTimeMillis()}.jpg")
        pendingPhoto = f
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        runCatching { camera.launch(uri) }
    }
    LaunchedEffect(vm.phoneCameraRequest) { if (vm.phoneCameraRequest) { vm.phoneCameraRequest = false; openCamera() } }
    LaunchedEffect(Unit) { vm.prewarmTranslation() } // tokens ready before you tap Start

    val lastSession = remember(vm.timeline) { vm.timeline.firstOrNull { "translation" in it.tags } }
    var lastOpen by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar("Translate", onBack = null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Language pill — what the Talk tile will use. Listening always works out the language itself.
            LivePill(
                if (lang.isBlank()) "Any language → English" else "${TranslationController.langName(lang)} ⇄ English",
                onClick = { pickLang = !pickLang },
                trailing = { Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose their language", tint = Neo.CyanPale, modifier = Modifier.size(16.dp)) },
            )
            if (pickLang) {
                Spacer(Modifier.height(12.dp))
                LangChips(TALK_LANGS, lang) { lang = it; pickLang = false }
            }
            if (vm.translateUi.error.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(vm.translateUi.error, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Danger), textAlign = TextAlign.Center)
            }

            // Hero: Listen.
            Spacer(Modifier.height(30.dp))
            ListenHero(onClick = { vm.startTranslation(TranslateMode.LISTEN) })
            Spacer(Modifier.height(18.dp))
            Text("Hear any language in English", style = Neo.body.copy(fontSize = 16.sp))
            Spacer(Modifier.height(6.dp))
            Text("or double-tap your glasses", style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))

            // Three modes.
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureTile(Icons.Outlined.ChatBubbleOutline, "Talk", sub = "Two-way chat", tall = true, modifier = Modifier.weight(1f),
                    onClick = { vm.startTranslation(TranslateMode.CONVERSATION, lang.ifBlank { null }) })
                FeatureTile(Icons.Outlined.DocumentScanner, "Read", sub = "Signs & menus", tall = true, accent = Neo.Amber, modifier = Modifier.weight(1f),
                    onClick = { readChooser = true })
                FeatureTile(Icons.AutoMirrored.Outlined.VolumeUp, "Say", sub = "A phrase for them", tall = true, accent = Neo.CyanPale, modifier = Modifier.weight(1f),
                    onClick = { page = "say" })
            }

            // Read result (sign / menu).
            if (vm.reading || vm.readResult.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                NeoCard(hero = true) {
                    MonoLabel(if (vm.reading) "Reading…" else "It says", color = Neo.Amber)
                    if (vm.reading) LiveBars(active = true, maxHeight = 24.dp, bars = 7)
                    else {
                        Text(vm.readResult, style = Neo.body.copy(fontSize = 17.sp, lineHeight = 25.sp))
                        GhostButton("Read it again", onClick = vm::repeatRead, icon = Icons.AutoMirrored.Outlined.VolumeUp, height = 44.dp)
                    }
                }
            }

            // Last session.
            if (lastSession != null) {
                Spacer(Modifier.height(16.dp))
                NeoCard(padding = 16.dp, onClick = { lastOpen = !lastOpen }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val code = lastSession.tags.firstOrNull { it != "translation" }.orEmpty()
                        Box(Modifier.border(1.dp, Neo.AccentBorder, RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 4.dp)) {
                            Text(code.take(5).uppercase().ifBlank { "··" }, style = Neo.mono.copy(fontSize = 11.sp, color = Neo.Cyan, letterSpacing = 0.sp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Last conversation", style = Neo.body.copy(fontSize = 14.sp))
                            Text("Summary saved to your memory", style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted))
                        }
                    }
                    if (lastOpen) Text(lastSession.text.orEmpty(), style = Neo.body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Neo.TextSoft))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (readChooser) ReadChooser(
        onPhone = { readChooser = false; openCamera() },
        onGlasses = { readChooser = false; vm.readWithGlasses() },
        onDismiss = { readChooser = false },
    )
}

/** The big glowing Listen button inside two thin rings. */
@Composable
private fun ListenHero(onClick: () -> Unit) {
    Box(Modifier.size(244.dp).border(1.dp, Neo.Cyan.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(212.dp).border(1.dp, Neo.Cyan.copy(alpha = 0.32f), CircleShape), contentAlignment = Alignment.Center) {
            Column(
                Modifier.size(176.dp)
                    .shadow(40.dp, CircleShape, ambientColor = Neo.Cyan, spotColor = Neo.Cyan)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Neo.CyanPale, Neo.Cyan, Color(0xFF0B8FB0)), radius = 260f))
                    .clickable(onClick = onClick, onClickLabel = "Start listening"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = Neo.OnCyan, modifier = Modifier.size(44.dp))
                Text("Listen", style = Neo.display.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Neo.OnCyan))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LangChips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (code, name) -> NeoChip(name, code == selected, onClick = { onSelect(code) }) }
    }
}

/** "Read a sign or menu" — which camera? */
@Composable
private fun ReadChooser(onPhone: () -> Unit, onGlasses: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Neo.Card).border(1.dp, Neo.HeroBorder, RoundedCornerShape(28.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Read a sign or menu", style = Neo.display.copy(fontSize = 20.sp))
            Text("Point at the text — I'll read it to you in English.", style = Neo.body.copy(fontSize = 14.sp, color = Neo.Muted))
            GlowButton("Phone camera", onClick = onPhone, icon = Icons.Outlined.CameraAlt, modifier = Modifier.fillMaxWidth())
            GhostButton("Glasses", onClick = onGlasses, icon = Icons.Outlined.Visibility, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── Listening (captions) ───────────────────────────────────────────────────────────────────────

@Composable
private fun Listening(vm: HomeViewModel) {
    val u = vm.translateUi
    val now = rememberNow()
    val listState = rememberLazyListState()
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(u.lines.size, u.lines.lastOrNull()?.translation?.length) {
        if (u.lines.isNotEmpty()) listState.animateScrollToItem(u.lines.size - 1)
    }
    val last = u.lines.lastOrNull()
    val live = last != null && now - last.updatedAt < LIVE_WINDOW_MS

    Column(Modifier.fillMaxSize().background(Neo.Ground).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            val from = if (u.theirLang.isBlank()) "Listening" else TranslationController.langName(u.theirLang)
            LivePill("$from → English")
            Spacer(Modifier.weight(1f))
            u.lagMs?.let { Text("${"%.1f".format(it / 1000.0)}s", style = Neo.mono.copy(fontSize = 12.sp, color = Neo.Faint, letterSpacing = 0.sp)) }
            Spacer(Modifier.size(8.dp))
            RoundButton(Icons.Outlined.Close, "Stop and close", onClick = vm::stopTranslation)
        }
        // Captions: older lines fade upwards, the newest is big.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
        ) {
            item { Spacer(Modifier.height(16.dp)) }
            if (u.lines.isEmpty()) item {
                Text("Listening… speech in any language appears here in English.", style = Neo.display.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = Neo.Faint))
            }
            items(u.lines, key = { it.id }) { line ->
                val age = u.lines.size - 1 - u.lines.indexOf(line)
                val english = TranslationController.isEnglish(line.lang)
                val text = (if (english) line.text else line.translation).ifBlank { "…" }.trim()
                if (age == 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text, style = Neo.display.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium, color = if (live) Neo.Cyan else Color.White))
                        if (line.text.isNotBlank()) {
                            Text(
                                if (english) "ENGLISH" else "${TheirStrings.nativeName(line.lang).uppercase()} · ${line.text.trim()}",
                                style = Neo.mono.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp, color = Neo.Faint),
                            )
                        }
                    }
                } else {
                    val (size, color) = when (age) {
                        1 -> 19 to Color(0xFF9AA8AD)
                        2 -> 18 to Neo.Faint
                        else -> 17 to Neo.Fainter
                    }
                    Text(text, style = Neo.body.copy(fontSize = size.sp, lineHeight = (size + 8).sp, color = color))
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LiveBars(active = live) }
        Text(
            u.error.ifBlank { "In your glasses · nothing plays out loud" },
            style = Neo.body.copy(fontSize = 13.sp, color = if (u.error.isNotBlank()) Neo.Danger else Neo.Muted),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
        )
        if (u.warning.isNotBlank()) WarningStrip(u.warning)
        if (typing) TypeToThem(vm, typed, onChange = { typed = it })
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundButton(Icons.Outlined.Keyboard, "Type to them", onClick = { typing = !typing }, size = 56.dp, style = if (typing) RoundStyle.Accent else RoundStyle.Plain)
            GlowButton("Reply to them", onClick = { vm.switchTranslateMode(TranslateMode.CONVERSATION) }, icon = Icons.Outlined.Mic, modifier = Modifier.weight(1f))
            RoundButton(Icons.Outlined.Stop, "Stop translating", onClick = vm::stopTranslation, size = 56.dp, style = RoundStyle.Danger)
        }
    }
}

@Composable
private fun WarningStrip(text: String) {
    Text(
        text,
        style = Neo.body.copy(fontSize = 13.sp, color = Neo.Amber),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).background(Neo.Amber.copy(alpha = 0.08f)).padding(12.dp),
    )
}

/** Type what you want to say — translated, shown on their side and said out loud. */
@Composable
private fun TypeToThem(vm: HomeViewModel, text: String, onChange: (String) -> Unit) {
    val lang = vm.translateUi.theirLang
    val ready = lang.isNotBlank() && !TranslationController.isEnglish(lang)
    fun send() { if (text.isNotBlank() && ready) { vm.sayTyped(text); onChange("") } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = text, onValueChange = onChange, singleLine = true, enabled = ready,
            placeholder = { Text(if (ready) "Type in English — I'll say it in ${TranslationController.langName(lang)}" else "Works once I know their language", style = Neo.body.copy(fontSize = 14.sp, color = Neo.Faint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            shape = RoundedCornerShape(28.dp),
            colors = neoFieldColors(),
            modifier = Modifier.weight(1f),
        )
        if (vm.typedSending) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Neo.Cyan)
        else RoundButton(Icons.AutoMirrored.Outlined.Send, "Say it to them", onClick = { send() }, style = RoundStyle.Primary, enabled = ready && text.isNotBlank())
    }
}

@Composable
internal fun neoFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Neo.Card, unfocusedContainerColor = Neo.Card, disabledContainerColor = Neo.Card,
    focusedBorderColor = Neo.Cyan, unfocusedBorderColor = Neo.AccentBorder, disabledBorderColor = Neo.Border,
    focusedTextColor = Neo.Text, unfocusedTextColor = Neo.Text, cursorColor = Neo.Cyan,
)

// ── Face to face (conversation) ────────────────────────────────────────────────────────────────

@Composable
private fun FaceToFace(vm: HomeViewModel) {
    val u = vm.translateUi
    val now = rememberNow()
    var acrossTable by rememberSaveable { mutableStateOf(true) } // their half upside-down
    var history by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val lastMe = u.lines.lastOrNull { it.who == "me" }
    val lastThem = u.lines.lastOrNull { it.who == "them" }
    val themLive = lastThem != null && now - lastThem.updatedAt < LIVE_WINDOW_MS
    val meLive = lastMe != null && now - lastMe.updatedAt < LIVE_WINDOW_MS
    // Turn-taking (P0): after you pause, the phone speaks your translation; nobody is heard meanwhile.
    val speakingToThem = !meLive && lastMe != null && lastMe.translation.isNotBlank() && now - lastMe.updatedAt < 4_000
    val turn = when {
        themLive -> "Listening to them…"
        meLive -> "Hearing you…"
        speakingToThem -> "Speaking to them…"
        u.theirLang.isBlank() -> "Let them speak first"
        else -> "Your turn — speak English"
    }

    Column(Modifier.fillMaxSize().background(Neo.Ground).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (history) {
            Transcript(u.lines, Modifier.weight(1f))
        } else {
            TheirHalf(u.theirLang, lastMe, lastThem, meLive, themLive, Modifier.weight(1f).graphicsLayer { rotationZ = if (acrossTable) 180f else 0f })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            RoundButton(Icons.Outlined.ScreenRotation, if (acrossTable) "Turn their side upright" else "Turn their side to face across the table", onClick = { acrossTable = !acrossTable },
                style = if (acrossTable) RoundStyle.Accent else RoundStyle.Plain)
            TurnBar(turn, active = themLive || meLive || speakingToThem, modifier = Modifier.weight(1f))
            RoundButton(Icons.Outlined.FormatListBulleted, if (history) "Face-to-face view" else "Full transcript", onClick = { history = !history },
                style = if (history) RoundStyle.Accent else RoundStyle.Plain)
        }
        YourHalf(lastThem, lastMe, u.theirLang, onFixMine = { typed = it; typing = true }, modifier = Modifier.weight(1f))
        if (u.warning.isNotBlank()) Text(u.warning, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Amber))
        if (u.error.isNotBlank()) Text(u.error, style = Neo.body.copy(fontSize = 12.sp, color = Neo.Danger))
        if (typing) TypeToThem(vm, typed, onChange = { typed = it })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(Icons.Outlined.Keyboard, "Type to them", onClick = { typing = !typing }, size = 52.dp, style = if (typing) RoundStyle.Accent else RoundStyle.Plain)
            GhostButton("Just listen", onClick = { vm.switchTranslateMode(TranslateMode.LISTEN) }, icon = Icons.Outlined.GraphicEq, modifier = Modifier.weight(1f))
            RoundButton(Icons.Outlined.Stop, "Stop translating", onClick = vm::stopTranslation, size = 52.dp, style = RoundStyle.Danger)
        }
    }
}

/** The glowing middle bar: whose turn it is. */
@Composable
private fun TurnBar(text: String, active: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val border by animateColorAsState(if (active) Neo.Cyan.copy(alpha = 0.6f) else Neo.Cyan.copy(alpha = 0.35f), label = "turn")
    Row(
        modifier.height(44.dp)
            .then(if (active) Modifier.shadow(14.dp, shape, ambientColor = Neo.Cyan, spotColor = Neo.Cyan) else Modifier)
            .clip(shape).background(Neo.Cyan.copy(alpha = 0.10f)).border(1.dp, border, shape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        LiveBars(active = active, bars = 5, maxHeight = 18.dp, barWidth = 3.dp)
        Text(text, style = Neo.display.copy(fontSize = 15.sp, color = Neo.CyanPale), maxLines = 1)
    }
}

/** The half that faces the other person — everything in THEIR language. */
@Composable
private fun TheirHalf(lang: String, lastMe: LiveLine?, lastThem: LiveLine?, meLive: Boolean, themLive: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    val border by animateColorAsState(if (meLive || themLive) Neo.Cyan.copy(alpha = 0.7f) else Neo.HeroBorder, label = "their-border")
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF11181D), Color(0xFF0F1418))))
            .border(1.dp, border, shape).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(TheirStrings.nativeName(lang).ifBlank { "···" }, size = 12)
            Spacer(Modifier.weight(1f))
            if (themLive) Icon(Icons.Outlined.Mic, contentDescription = null, tint = Neo.Cyan, modifier = Modifier.size(16.dp))
            else if (lastMe != null) Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = Neo.Cyan, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                if (lastMe == null || lastMe.translation.isBlank()) (if (lang.isBlank()) TheirStrings.MULTI_GREETING else TheirStrings.greeting(lang)) else lastMe.translation.trim(),
                style = Neo.display.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium, color = if (meLive) Neo.Cyan else Color.White),
            )
            if (lastThem != null && lastThem.text.isNotBlank()) {
                Text("« ${lastThem.text.trim()} »", style = Neo.body.copy(fontSize = 15.sp, lineHeight = 22.sp, color = Neo.Faint))
            }
        }
    }
}

/** Your half — English. Their latest words big, your last line small (tap to fix it by typing). */
@Composable
private fun YourHalf(lastThem: LiveLine?, lastMe: LiveLine?, theirLang: String, onFixMine: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Neo.Card).border(1.dp, Neo.Border, shape).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonoLabel("English · you", size = 12)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (lastThem == null) {
                Text(
                    if (theirLang.isBlank()) "Let them speak first — I'll work out their language. Hold the phone between you."
                    else "Speak English, then pause — they'll hear you in ${TranslationController.langName(theirLang)}.",
                    style = Neo.body.copy(fontSize = 17.sp, lineHeight = 25.sp, color = Neo.Muted),
                )
            } else {
                Text(lastThem.translation.ifBlank { "…" }.trim(), style = Neo.display.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = Neo.Cyan))
                if (lastThem.text.isNotBlank()) Text("THEY SAID · ${lastThem.text.trim()}", style = Neo.mono.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp, color = Neo.Faint))
            }
        }
        if (lastMe != null && lastMe.text.isNotBlank()) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { onFixMine(lastMe.text.trim()) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Edit, contentDescription = null, tint = Neo.Muted, modifier = Modifier.size(16.dp))
                Text(
                    if (lastMe.typed) "You typed “${lastMe.text.trim()}”" else "You said “${lastMe.text.trim()}” · tap to fix",
                    style = Neo.body.copy(fontSize = 14.sp, color = Color(0xFF9AA8AD)), maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun Transcript(lines: List<LiveLine>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.translation?.length) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (lines.isEmpty()) item { Text("Nothing said yet.", style = Neo.body.copy(color = Neo.Muted)) }
        items(lines, key = { it.id }) { LineBubble(it) }
    }
}

// ── Say it for me ───────────────────────────────────────────────────────────────────────────────

@Composable
private fun SayItForMe(vm: HomeViewModel, onBack: () -> Unit) {
    var phrase by rememberSaveable { mutableStateOf("") }
    var lang by rememberSaveable { mutableStateOf(vm.lastTranslateLang.ifBlank { "zh-Hans" }) }
    var showThem by remember { mutableStateOf(false) }
    val p = vm.lastPhrase
    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar("Say it for me", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LangChips(SAY_LANGS, lang) { lang = it }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel("In English")
                OutlinedTextField(
                    value = phrase, onValueChange = { phrase = it }, singleLine = true,
                    placeholder = { Text("e.g. How much is this?", style = Neo.body.copy(fontSize = 17.sp, color = Neo.Faint)) },
                    textStyle = Neo.body.copy(fontSize = 18.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (phrase.isNotBlank()) vm.sayPhrase(phrase, lang) }),
                    shape = RoundedCornerShape(30.dp), colors = neoFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
            GlowButton("Translate & say it", onClick = { vm.sayPhrase(phrase, lang) }, enabled = phrase.isNotBlank() && !vm.busy,
                icon = Icons.AutoMirrored.Outlined.VolumeUp, modifier = Modifier.fillMaxWidth())
            if (p != null) {
                NeoCard(hero = true, padding = 24.dp) {
                    MonoLabel(p.langName.ifBlank { TranslationController.langName(lang) }, color = Neo.Cyan)
                    Text(p.translation, style = Neo.display.copy(fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium, color = Color.White))
                    if (p.romanization.isNotBlank()) Text(p.romanization, style = Neo.body.copy(fontSize = 18.sp, color = Neo.CyanPale))
                    Text(p.phrase, style = Neo.body.copy(fontSize = 13.sp, color = Neo.Faint))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlowButton("Play out loud", onClick = { vm.repeatPhrase() }, icon = Icons.AutoMirrored.Outlined.VolumeUp, modifier = Modifier.weight(1f))
                    GhostButton("Show them", onClick = { showThem = true }, icon = Icons.Outlined.Fullscreen, modifier = Modifier.weight(1f), height = 56.dp)
                }
            }
            Text("Or say: “Jarvis, how do I say ‘where is the taxi’ in Arabic?”", style = Neo.body.copy(fontSize = 13.sp, color = Neo.Muted))
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showThem && p != null) ShowThemDialog(text = p.translation, sub = p.romanization, onClose = { showThem = false })
}

/** Full-screen phrase in huge type, to hold up to the other person. Tap the rotate icon to flip it. */
@Composable
private fun ShowThemDialog(text: String, sub: String, onClose: () -> Unit) {
    var flipped by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Neo.Ground).padding(24.dp)) {
            Column(
                Modifier.align(Alignment.Center).graphicsLayer { rotationZ = if (flipped) 180f else 0f },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text, style = Neo.display.copy(fontSize = 48.sp, lineHeight = 60.sp, fontWeight = FontWeight.Medium, color = Color.White), textAlign = TextAlign.Center)
                if (sub.isNotBlank()) Text(sub, style = Neo.body.copy(fontSize = 20.sp, color = Neo.CyanPale), textAlign = TextAlign.Center)
            }
            Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RoundButton(Icons.Outlined.ScreenRotation, "Flip", onClick = { flipped = !flipped }, size = 56.dp)
                RoundButton(Icons.Outlined.Close, "Close", onClick = onClose, size = 56.dp)
            }
        }
    }
}

// ── Finished ────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Finished(vm: HomeViewModel) {
    val u = vm.translateUi
    Column(Modifier.fillMaxSize().background(Neo.Ground)) {
        NeoTopBar(if (u.saving) "Saving…" else "Conversation saved", onBack = null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (u.saving) NeoCard { MonoLabel("Writing a summary to your memory…", color = Neo.Amber); LiveBars(active = true, maxHeight = 22.dp, bars = 7) }
            if (u.summary.isNotBlank()) NeoCard(hero = true) {
                MonoLabel("Summary · saved to memory", color = Neo.Cyan)
                Text(u.summary, style = Neo.body.copy(fontSize = 15.sp, lineHeight = 23.sp))
                Text("Ask later: “Jarvis, what price did the supplier quote?”", style = Neo.body.copy(fontSize = 12.sp, color = Neo.Muted))
            }
            u.lines.forEach { LineBubble(it) }
            GlowButton("Done", onClick = vm::clearTranslation, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LineBubble(line: LiveLine) {
    val them = line.who == "them"
    val shape = if (them) RoundedCornerShape(6.dp, 20.dp, 20.dp, 20.dp) else RoundedCornerShape(20.dp, 6.dp, 20.dp, 20.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (them) Alignment.CenterStart else Alignment.CenterEnd) {
        Column(
            Modifier.fillMaxWidth(0.9f).clip(shape)
                .background(if (them) Neo.Cyan.copy(alpha = 0.08f) else Neo.Card)
                .border(1.dp, if (them) Neo.Cyan.copy(alpha = 0.25f) else Neo.Border, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MonoLabel(if (them) "Them · ${TranslationController.langName(line.lang)}" else "You")
            Text(line.translation.ifBlank { "…" }.trim(), style = Neo.body.copy(fontSize = 16.sp, color = if (them) Neo.CyanPale else Neo.Text))
            if (line.text.isNotBlank()) Text(line.text.trim(), style = Neo.body.copy(fontSize = 12.sp, color = Neo.Faint))
        }
    }
}
