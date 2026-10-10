package com.echo.app.translate

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.echo.memory.EchoBackend
import com.echo.memory.TranslatedLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class TranslateMode { LISTEN, CONVERSATION }

/** One on-screen line. [who] = "them" (their speech → English in your ear) or "me" (you → their language). */
data class LiveLine(
    val id: Long,
    val who: String,
    val lang: String,
    val text: String,
    val translation: String,
    val lagMs: Long? = null,
    /** Last time this line changed — the UI uses it to show who is speaking right now. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** You typed this one (rather than said it). */
    val typed: Boolean = false,
)

data class TranslateUi(
    val active: Boolean = false,
    val mode: TranslateMode = TranslateMode.CONVERSATION,
    val status: String = "",
    /** Their language (BCP-47) once detected or preset; "" = still listening for it. */
    val theirLang: String = "",
    val lines: List<LiveLine> = emptyList(),
    /** Delay from them starting to speak to the first translated word, last turn (ms). */
    val lagMs: Long? = null,
    /** Where your translation plays for the other person (diagnostic: "phone speaker" / …). */
    val speakerRoute: String = "",
    /** Where their translation plays for you ("glasses" / "phone"). */
    val earRoute: String = "",
    val summary: String = "",
    val saving: Boolean = false,
    val error: String = "",
    /** [theirLang] is the language you used last time — a guess that switches automatically. */
    val langGuessed: Boolean = false,
    /** Plain-language problem the user can fix (e.g. glasses not connected for sound). */
    val warning: String = "",
)

/**
 * Live speech translation (Gemini Live Translate), hands-free and low-latency.
 *
 * Audio plan (one microphone, two directions):
 *  - The PHONE mic (built-in, 16 kHz) captures both people — hold the phone between you or lay it
 *    on the table. The glasses mic is tuned for the wearer only, so it's the wrong mic for this.
 *  - Session **A** (target English) hears everything; with echo=false it stays silent while YOU
 *    speak English and translates THEIR speech → English, played on the media route = **your
 *    glasses** (in-ear, the other person never hears it).
 *  - Session **B** (target = their language, auto-detected from A or preset) also hears everything
 *    and translates YOUR English → their language, played on the **phone loudspeaker** for them.
 *  - Echo control (one mic hears everything, so without it the two directions translate each other
 *    forever — the "looping" bug): A is muted while the loudspeaker plays (+ room tail), B is muted
 *    while your ear audio plays, both are muted while JARVIS speaks a cue or a typed reply, and any
 *    line whose words match something just played is dropped as an echo.
 *  - Audio that arrives without translated words (echo=false still streams a little audio) is
 *    dropped — we only play audio that comes with an output transcript.
 *
 * LISTEN mode = session A only (TV, meetings, talks, someone speaking): English in your ear, nothing
 * out loud. This is what the glasses double-tap starts. Say "Jarvis, let me talk" (or tap "Talk to
 * them") to switch to CONVERSATION; "Jarvis, just listen" switches back. You can also TYPE what you
 * want to say — it's translated and spoken out loud for them (fixes misheard words).
 * Say "stop translating" (in English) or press Stop to end; the conversation is then summarized
 * and saved to memory by the `translate` Edge Function.
 */
@Singleton
class TranslationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: EchoBackend,
    private val phrases: PhraseSpeaker,
    http: OkHttpClient,
) {
    private val wsHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _ui = MutableStateFlow(TranslateUi())
    val ui: StateFlow<TranslateUi> = _ui

    @Volatile private var active = false
    private var startedAt = 0L
    private val prefs = context.getSharedPreferences("translate", Context.MODE_PRIVATE)

    /** The language of the last person you translated with — tried first next time (instant start). */
    val lastLang: String get() = prefs.getString("last_lang", "").orEmpty()
    private fun rememberLang(l: String) { if (l.isNotBlank() && !isEnglish(l)) prefs.edit().putString("last_lang", l).apply() }

    // Pre-fetched single-use tokens (valid ~2 min to open a session) so starting is instant.
    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, com.echo.memory.TranslateToken>>()
    private var lastRestartAt = 0L
    private var sessionA: LiveTranslateSession? = null // anything → English (your ear)
    private var sessionB: LiveTranslateSession? = null // English → their language (loudspeaker)
    @Volatile private var openingB = false
    private var micJob: Job? = null
    private var record: AudioRecord? = null
    private var earPlayer: PcmPlayer? = null
    private var speakerPlayer: PcmPlayer? = null
    private val lines = mutableListOf<LiveLine>()
    private var nextId = 1L
    private val retries = mutableMapOf<String, Int>()

    // Per-direction line assembly (fragments arrive in pieces; a ≥1.6 s gap starts a new line).
    private class Cur(var id: Long = 0, var lastAt: Long = 0, var firstInAt: Long = 0, var firstOutAt: Long = 0, var hasOut: Boolean = false, var echo: Boolean = false)
    private val curThem = Cur()
    private val curMe = Cur()

    val isActive: Boolean get() = active

    /** Android refused the background microphone service (phone locked, app not open). */
    fun onBackgroundRefused() {
        if (!active) return
        Log.w(TAG, "background mic refused — stopping")
        _cues.tryEmit("Please unlock your phone, then double-tap again.")
        stop(save = false)
    }

    /** Short sentences for JARVIS to say (mode switched, etc.) — the ViewModel speaks them. */
    private val _cues = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val cues: SharedFlow<String> = _cues

    /** True while JARVIS itself is talking (a cue, a typed reply) — the mic sends silence. */
    @Volatile private var micHeld = false
    fun holdMic(on: Boolean) { micHeld = on; if (!on) heldUntil = System.currentTimeMillis() + HOLD_TAIL_MS }
    @Volatile private var heldUntil = 0L
    @Volatile private var phraseBusy = false

    // Recently PLAYED translations, per direction — used to recognise our own audio coming back in.
    private val playedA = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>() // English, into your ear
    private val playedB = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>() // their language, loudspeaker
    private var cmdBuf = ""
    private var cmdAt = 0L
    private var lastCmdAt = 0L

    /**
     * Warm up: fetch the tokens for both directions in the background (call when the Translate screen
     * opens or a "translate" command is heard), so the sessions open the moment you start.
     */
    fun prewarm(target: String? = null) {
        val langs = listOf("en", normalizeLang(target ?: lastLang)).filter { it.isNotBlank() }.distinct()
        scope.launch {
            for (l in langs) {
                val cached = tokenCache[l]
                if (cached != null && System.currentTimeMillis() - cached.first < TOKEN_FRESH_MS) continue
                runCatching { backend.translateToken(l) }.getOrNull()?.let { tokenCache[l] = System.currentTimeMillis() to it }
            }
        }
    }

    /** True if the glasses (or any Bluetooth headset) can play sound right now. */
    private fun glassesAudioConnected(): Boolean = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    /**
     * Start translating. [presetTarget] = their language if known ("zh-Hans", "fr", "ar"…). In a
     * conversation with no preset, the language you used LAST time is tried straight away (both
     * directions live from the first second); if they speak something else it switches by itself.
     */
    fun start(mode: TranslateMode, presetTarget: String? = null) {
        if (active) return
        active = true
        startedAt = System.currentTimeMillis()
        synchronized(lines) { lines.clear() }
        retries.clear()
        curThem.id = 0; curMe.id = 0; curThem.echo = false; curMe.echo = false
        playedA.clear(); playedB.clear(); cmdBuf = ""
        val preset = presetTarget?.let { normalizeLang(it) }.orEmpty()
        val guess = if (mode == TranslateMode.CONVERSATION && preset.isEmpty()) lastLang else ""
        val target = preset.ifEmpty { guess }
        val glasses = glassesAudioConnected()
        // Keep the mic alive with the screen off (Android cuts background mic access otherwise).
        val bg = TranslateService.start(context)
        if (!bg) { active = false; _cues.tryEmit("Please unlock your phone, then double-tap again."); return }
        _ui.value = TranslateUi(
            active = true, mode = mode, theirLang = target, langGuessed = guess.isNotEmpty(),
            status = "Connecting…",
            earRoute = if (glasses) "glasses" else "phone speaker",
            speakerRoute = if (mode == TranslateMode.CONVERSATION) "phone speaker" else "",
            warning = listOfNotNull(
                if (glasses) null else "Your glasses aren't connected for sound, so you'll hear the translation from the phone. Turn the glasses on to hear it privately.",
                if (bg) null else "Keep the phone unlocked — Android didn't allow translating in the background this time.",
            ).joinToString("\n"),
        )
        scope.launch {
            earPlayer = PcmPlayer(context, toSpeaker = false)
            if (mode == TranslateMode.CONVERSATION) speakerPlayer = PcmPlayer(context, toSpeaker = true)
            // Open both directions together (in parallel) — no waiting for them to speak first.
            val a = launch { openSession("en") }
            if (mode == TranslateMode.CONVERSATION && target.isNotEmpty() && !isEnglish(target)) launch { openSession(target) }
            a.join()
            startMic()
        }
    }

    private suspend fun openSession(target: String) {
        if (!active) return
        if (target != "en") openingB = true
        try {
            val cached = tokenCache.remove(target)
            val tok = if (cached != null && System.currentTimeMillis() - cached.first < TOKEN_FRESH_MS) cached.second
            else backend.translateToken(target)
            val s = LiveTranslateSession(wsHttp, target, tok.token, tok.model, tok.urls, listener)
            if (target == "en") sessionA = s else sessionB = s
            s.connect()
        } catch (e: Exception) {
            Log.w(TAG, "token/open failed for $target: ${e.message}")
            setStatus(error = "Couldn't start translation (${e.message?.take(80)})")
            if (target == "en") stop(save = false)
        } finally {
            if (target != "en") openingB = false
        }
    }

    private val listener = object : LiveTranslateSession.Listener {
        override fun onReady(session: LiveTranslateSession) {
            Log.i(TAG, "[${session.target}] ready")
            retries[session.target] = 0
            val u = _ui.value
            val st = when {
                u.mode == TranslateMode.LISTEN -> "Listening — translating into English"
                u.theirLang.isEmpty() -> "Ready — either of you can start talking"
                else -> "Ready — ${langName(u.theirLang)} and English. Either of you can start."
            }
            setStatus(status = st)
        }

        override fun onAudio(session: LiveTranslateSession, pcm24k: ByteArray) {
            val isA = session === sessionA
            val cur = if (isA) curThem else curMe
            if (cur.echo) return // our own audio came back in — never play its "translation"
            // Only play audio that belongs to a translated utterance (echo=false still streams some
            // audio when the input is already in the target language).
            if (!cur.hasOut) { val q = pending(isA); synchronized(q) { q.addLast(pcm24k) }; trimPending(isA); return }
            flushPending(isA)
            (if (isA) earPlayer else speakerPlayer)?.write(pcm24k)
        }

        override fun onInputText(session: LiveTranslateSession, text: String, lang: String) {
            val isA = session === sessionA
            // Trust the writing system over the reported code (Chinese characters are Chinese even if
            // the code says "fr"); for Latin-script text the reported code decides.
            val l = effectiveLang(lang, text, if (isA) currentLineLang(true) else "")
            if (isEnglish(l) && handleVoiceCommand(text)) return
            if (isA) {
                if (isEnglish(l) && _ui.value.mode == TranslateMode.LISTEN) {
                    // Listening: English speech (an English video, a person) is shown as captions
                    // too — nothing to translate, nothing played.
                    appendInput(curThem, "them", "en", text, isA = true)
                    return
                }
                if (isEnglish(l)) {
                    // That's you (session B handles your speech). End their current line and drop any
                    // audio A streams for your English, so it can never leak into your ear.
                    endLine(curThem, isA = true)
                    return
                }
                appendInput(curThem, "them", l, text, isA = true)
                if (l.isNotBlank() && _ui.value.mode == TranslateMode.CONVERSATION && !openingB) {
                    val b = sessionB
                    when {
                        // First time we hear their language → open your direction.
                        b == null && text.trim().length >= 2 -> {
                            rememberLang(l)
                            _ui.value = _ui.value.copy(theirLang = l, langGuessed = false, status = "Heard ${langName(l)} — you can reply now")
                            scope.launch { openSession(l) }
                        }
                        // We guessed (last time's language) but they speak something else → switch.
                        b != null && b.target != l && currentText(true).length >= 8 -> {
                            rememberLang(l)
                            _ui.value = _ui.value.copy(theirLang = l, langGuessed = false, status = "They're speaking ${langName(l)} — switched")
                            sessionB = null
                            b.close()
                            scope.launch { openSession(l) }
                        }
                        b != null && b.target == l && _ui.value.langGuessed -> {
                            rememberLang(l); _ui.value = _ui.value.copy(langGuessed = false)
                        }
                    }
                } else if (l.isNotBlank() && l != _ui.value.theirLang) {
                    // Listening: always show the language actually being spoken right now.
                    rememberLang(l); _ui.value = _ui.value.copy(theirLang = l, langGuessed = false)
                }
            } else {
                if (!isEnglish(l)) {
                    // Non-English reaching B is either the loudspeaker playing your own translation
                    // (ignore it — never cut your sentence off) or them talking (A handles that).
                    if (speakerPlayer?.isPlaying(ECHO_WINDOW_MS) != true) endLine(curMe, isA = false)
                    return
                }
                appendInput(curMe, "me", "en", text, isA = false)
            }
        }

        override fun onOutputText(session: LiveTranslateSession, text: String, lang: String) {
            val isA = session === sessionA
            val cur = if (isA) curThem else curMe
            if (cur.id == 0L || cur.echo) return
            remember(if (isA) playedA else playedB, text)
            if (!cur.hasOut && text.isNotBlank()) {
                cur.hasOut = true
                cur.firstOutAt = System.currentTimeMillis()
                val lag = cur.firstOutAt - cur.firstInAt
                updateLine(cur.id) { it.copy(lagMs = lag) }
                if (isA) setStatus(lagMs = lag)
                flushPending(isA)
            }
            updateLine(cur.id) { it.copy(translation = it.translation + text) }
            cur.lastAt = System.currentTimeMillis()
        }

        override fun onClosed(session: LiveTranslateSession, reason: String, error: Boolean) {
            Log.i(TAG, "[${session.target}] closed: $reason error=$error")
            if (session === sessionA) sessionA = null
            if (session === sessionB) sessionB = null
            if (!active || !error) return
            // Sessions are time-limited / can drop — reconnect transparently (a few tries).
            val n = (retries[session.target] ?: 0) + 1
            retries[session.target] = n
            if (n > 3) {
                setStatus(error = "Translation connection lost ($reason)")
                if (session.target == "en") stop(save = true)
                return
            }
            setStatus(status = "Reconnecting…")
            scope.launch { delay(400L * n); openSession(session.target) }
        }
    }

    // ── line assembly ──────────────────────────────────────────────────────────────────────────
    /** Close the current line for a direction and discard audio not yet tied to translated words. */
    private fun endLine(cur: Cur, isA: Boolean) {
        cur.id = 0
        cur.hasOut = false
        val q = pending(isA)
        synchronized(q) { q.clear() }
    }

    private fun appendInput(cur: Cur, who: String, lang: String, text: String, isA: Boolean) {
        val now = System.currentTimeMillis()
        if (cur.echo) {
            // Still inside an echo: swallow it until there's a real pause.
            if (now - cur.lastAt <= LINE_GAP_MS) { cur.lastAt = now; return }
            cur.echo = false; cur.id = 0
        }
        val langChanged = cur.id != 0L && synchronized(lines) { lines.lastOrNull { it.id == cur.id }?.lang }.let { it != null && it != lang }
        if (cur.id == 0L || now - cur.lastAt > LINE_GAP_MS || langChanged) {
            if (langChanged) { cur.hasOut = false; val q = pending(isA); synchronized(q) { q.clear() } }
            cur.id = nextId++
            cur.firstInAt = now
            cur.hasOut = false
            val q = pending(isA)
            synchronized(q) { q.clear() } // stale audio from before this utterance never plays
            synchronized(lines) { lines.add(LiveLine(cur.id, who, lang, text.trimStart(), "")) }
        } else {
            updateLine(cur.id) { it.copy(text = it.text + text) }
        }
        cur.lastAt = now
        // Is this line just our own translation coming back through the mic? ("them" lines are
        // compared with what the loudspeaker played; "me" lines with what your ear played.)
        val sofar = currentText(isA)
        if (isEcho(sofar, if (isA) playedB else playedA)) {
            Log.i(TAG, "echo dropped (${if (isA) "A" else "B"}): ${sofar.take(60)}")
            cur.echo = true
            val id = cur.id
            synchronized(lines) { lines.removeAll { it.id == id } }
            val q = pending(isA); synchronized(q) { q.clear() }
        }
        publishLines()
    }

    private fun remember(q: java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>, text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        q.addLast(now to text)
        while (q.size > 200 || (q.peekFirst()?.first ?: now) < now - ECHO_MEMORY_MS) q.pollFirst()
    }

    /** ≥3 words and ≥60 % of them appear in what was just played → it's an echo, not a person. */
    private fun isEcho(heard: String, played: java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>): Boolean {
        val words = wordsOf(heard)
        if (words.size < 3 || played.isEmpty()) return false
        val now = System.currentTimeMillis()
        val bag = played.filter { it.first > now - ECHO_MEMORY_MS }.joinToString(" ") { it.second }
        val playedWords = wordsOf(bag).toHashSet()
        if (playedWords.isEmpty()) return false
        val hit = words.count { it in playedWords }
        return hit >= 3 && hit.toDouble() / words.size >= 0.6
    }

    private fun wordsOf(t: String): List<String> {
        val clean = t.lowercase().replace(Regex("[\\p{Punct}¿¡。、，！？：；“”‘’«»]"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        // Chinese/Japanese have no spaces — compare characters instead of words.
        return if (clean.any { Character.UnicodeScript.of(it.code) in CJK }) clean.filter { !it.isWhitespace() }.map { it.toString() }
        else clean.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    // ── voice commands said in English while translating ───────────────────────────────────────
    /** "stop translating" · "Jarvis, let me talk" · "Jarvis, just listen". Returns true if handled. */
    private fun handleVoiceCommand(text: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - cmdAt > 5_000) cmdBuf = ""
        cmdBuf = (cmdBuf + " " + text).takeLast(160); cmdAt = now
        if (now - lastCmdAt < 3_000) return false // both directions hear the same words — act once
        val mode = _ui.value.mode
        return when {
            STOP_RE.containsMatchIn(cmdBuf) -> { lastCmdAt = now; cmdBuf = ""; stop(save = true); true }
            mode == TranslateMode.LISTEN && TALK_RE.containsMatchIn(cmdBuf) -> { lastCmdAt = now; cmdBuf = ""; switchMode(TranslateMode.CONVERSATION); true }
            mode == TranslateMode.CONVERSATION && LISTEN_RE.containsMatchIn(cmdBuf) -> { lastCmdAt = now; cmdBuf = ""; switchMode(TranslateMode.LISTEN); true }
            else -> false
        }
    }

    /**
     * Listen ⇄ conversation without stopping. Listen = their words → English in your ear only.
     * Conversation adds your direction: English → their language on the phone loudspeaker.
     */
    fun switchMode(mode: TranslateMode) {
        if (!active || _ui.value.mode == mode) return
        val lang = _ui.value.theirLang.ifBlank { lastLang }
        if (mode == TranslateMode.CONVERSATION) {
            if (speakerPlayer == null) speakerPlayer = PcmPlayer(context, toSpeaker = true)
            _ui.value = _ui.value.copy(
                mode = mode, speakerRoute = "phone speaker",
                theirLang = _ui.value.theirLang.ifBlank { lang }, langGuessed = _ui.value.theirLang.isBlank() && lang.isNotBlank(),
                status = if (lang.isBlank()) "Conversation — let them speak first" else "Conversation — ${langName(lang)} and English",
            )
            if (lang.isNotBlank() && !isEnglish(lang) && sessionB == null && !openingB) scope.launch { openSession(lang) }
            _cues.tryEmit(if (lang.isBlank()) "Conversation on. Let them speak first." else "Conversation on. Speak English, they'll hear ${langName(lang)}.")
        } else {
            sessionB?.close(); sessionB = null
            endLine(curMe, isA = false)
            val sp = speakerPlayer; speakerPlayer = null
            scope.launch { delay(600); sp?.release() }
            _ui.value = _ui.value.copy(mode = mode, speakerRoute = "", status = "Listening — translating into English")
            _cues.tryEmit("Just listening.")
        }
    }

    /**
     * You typed what you want to say (e.g. the speech recogniser got a word wrong): translate it into
     * their language, show it on their side of the screen and say it out loud on the phone speaker.
     */
    suspend fun sayTyped(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val lang = _ui.value.theirLang.ifBlank { lastLang }
        if (lang.isBlank() || isEnglish(lang)) {
            setStatus(error = "I don't know their language yet — let them speak first, or pick it on the Translate screen.")
            return null
        }
        setStatus(status = "Translating what you typed…", error = "")
        val r = runCatching {
            backend.translatePhrase("Translate exactly this sentence, which I am saying to the person in front of me: \"$t\"", lang)
        }.getOrElse { setStatus(error = "Couldn't translate that (${it.message?.take(60)})"); return null }
        val id = nextId++
        synchronized(lines) { lines.add(LiveLine(id, "me", "en", t, r.translation, typed = true)) }
        publishLines()
        remember(playedB, r.translation)
        // Speak it on the loudspeaker; the mic is held so it isn't heard back as "them".
        phraseBusy = true
        try { phrases.speakAloud(r.translation, lang) } finally { phraseBusy = false; heldUntil = System.currentTimeMillis() + HOLD_TAIL_MS }
        setStatus(status = if (_ui.value.mode == TranslateMode.LISTEN) "Said it — still listening" else "Said it — go ahead")
        return r.translation
    }

    private fun currentLineLang(isA: Boolean): String {
        val id = (if (isA) curThem else curMe).id
        return synchronized(lines) { lines.lastOrNull { it.id == id }?.lang.orEmpty() }
    }

    private fun currentText(isA: Boolean): String {
        val id = (if (isA) curThem else curMe).id
        return synchronized(lines) { lines.lastOrNull { it.id == id }?.text.orEmpty() }
    }

    private fun updateLine(id: Long, f: (LiveLine) -> LiveLine) {
        synchronized(lines) {
            val i = lines.indexOfFirst { it.id == id }
            if (i >= 0) {
                val n = f(lines[i])
                lines[i] = n.copy(text = collapseRepeats(n.text), translation = collapseRepeats(n.translation), updatedAt = System.currentTimeMillis())
                // The model occasionally gets stuck repeating a phrase — if so, restart that session.
                if (n.text.length != lines[i].text.length && n.who == "them") restartStuck()
            }
        }
        publishLines()
    }

    private fun publishLines() {
        val snapshot = synchronized(lines) { lines.toList() }
        _ui.value = _ui.value.copy(lines = snapshot)
    }

    private val pendingA = ArrayDeque<ByteArray>()
    private val pendingB = ArrayDeque<ByteArray>()
    private fun pending(isA: Boolean) = if (isA) pendingA else pendingB
    private fun trimPending(isA: Boolean) {
        val q = pending(isA)
        synchronized(q) { while (q.sumOf { it.size } > PENDING_MAX_BYTES) q.removeFirst() }
    }
    private fun flushPending(isA: Boolean) {
        val q = pending(isA)
        val player = if (isA) earPlayer else speakerPlayer
        synchronized(q) { while (q.isNotEmpty()) player?.write(q.removeFirst()) }
    }

    /** Reopen the "them → English" session if it starts looping (at most once every 20 s). */
    private fun restartStuck() {
        val now = System.currentTimeMillis()
        if (!active || now - lastRestartAt < 20_000) return
        lastRestartAt = now
        Log.w(TAG, "repetition detected — restarting session A")
        val a = sessionA ?: return
        sessionA = null
        a.close()
        endLine(curThem, isA = true)
        scope.launch { openSession("en") }
    }

    // ── microphone ─────────────────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startMic() {
        if (!active) return
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            setStatus(error = "Microphone unavailable"); stop(save = false); return
        }
        // Prefer the phone's own mic — it hears both people; the glasses mic only hears the wearer.
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { runCatching { rec.setPreferredDevice(it) } }
        record = rec
        rec.startRecording()
        Log.i(TAG, "mic started: routed=${rec.routedDevice?.type}")
        micJob = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            val silence = ByteArray(CHUNK_BYTES)
            while (active) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                // Echo control, per direction (see class doc). A loop needs BOTH legs open — the
                // loudspeaker heard by A AND your ear audio heard by B — so each leg is closed while
                // its audio is actually playing (measured from the player, plus a room/BT tail).
                val now = System.currentTimeMillis()
                val held = micHeld || phraseBusy || now < heldUntil
                val speakerBusy = speakerPlayer?.isPlaying(SPEAKER_TAIL_MS) == true
                val earBusy = earPlayer?.isPlaying(if (earPlayer?.isSpeakerRoute() == true) SPEAKER_TAIL_MS else EAR_TAIL_MS) == true
                val earOnSpeaker = earBusy && earPlayer?.isSpeakerRoute() == true
                sessionA?.sendPcm16k(if (held || speakerBusy || earOnSpeaker) silence else buf, n)
                sessionB?.sendPcm16k(if (held || earBusy) silence else buf, n)
            }
        }
    }

    /** Stop translating. If [save], summarize + store the conversation as a memory. */
    fun stop(save: Boolean = true) {
        if (!active) return
        active = false
        micJob?.cancel(); micJob = null
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        sessionA?.close(); sessionB?.close(); sessionA = null; sessionB = null
        TranslateService.stop(context)
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        val snapshot = synchronized(lines) { lines.filter { it.text.isNotBlank() || it.translation.isNotBlank() } }
        scope.launch {
            delay(600) // let the tail of the last translation finish playing
            earPlayer?.release(); speakerPlayer?.release(); earPlayer = null; speakerPlayer = null
        }
        val mode = _ui.value.mode
        _ui.value = _ui.value.copy(active = false, status = if (save && snapshot.isNotEmpty()) "Saving…" else "Stopped", saving = save && snapshot.isNotEmpty())
        if (!save || snapshot.isEmpty()) return
        scope.launch {
            val summary = runCatching {
                backend.translateSave(
                    snapshot.map { TranslatedLine(it.who, it.lang, it.text.trim(), it.translation.trim()) },
                    if (mode == TranslateMode.LISTEN) "listening session" else "conversation",
                    seconds,
                )
            }.getOrElse { "" }
            _ui.value = _ui.value.copy(saving = false, summary = summary, status = if (summary.isNotBlank()) "Saved to your memory" else "Stopped (couldn't save)")
        }
    }

    fun clearSummary() { _ui.value = _ui.value.copy(summary = "", lines = if (active) _ui.value.lines else emptyList(), error = "") }

    private fun setStatus(status: String? = null, theirLang: String? = null, lagMs: Long? = null, error: String? = null) {
        val u = _ui.value
        _ui.value = u.copy(
            status = status ?: u.status,
            theirLang = theirLang ?: u.theirLang,
            lagMs = lagMs ?: u.lagMs,
            error = error ?: u.error,
        )
    }

    companion object {
        private const val TAG = "EchoTranslate"
        private const val CHUNK_BYTES = 3200          // 100 ms @ 16 kHz mono PCM16
        private const val LINE_GAP_MS = 1600L
        private const val ECHO_WINDOW_MS = 1200L
        private const val SPEAKER_TAIL_MS = 700L   // room echo after the loudspeaker stops
        private const val EAR_TAIL_MS = 400L       // Bluetooth latency after your ear audio stops
        private const val HOLD_TAIL_MS = 600L
        private const val ECHO_MEMORY_MS = 25_000L
        private val CJK = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL)
        private const val TOKEN_FRESH_MS = 90_000L

        /** "after the match we go to a small after the match we go to a small …" → one copy. */
        fun collapseRepeats(t: String): String =
            if (t.length < 36) t else REPEAT_RE.replace(t) { it.groupValues[1] }
        private val REPEAT_RE = Regex("(.{12,120}?)(?:\\s*\\1){2,}")
        private const val PENDING_MAX_BYTES = 24_000 * 2 * 2 // ≤2 s of 24 kHz audio held before text arrives
        private val TALK_RE = Regex(
            "\\b(let me (talk|reply|respond|answer|speak)|jarvis,? let'?s talk|i want to (talk|reply|respond)|conversation mode|talk mode|reply mode|" +
                "talk to (him|her|them)|start (the )?conversation|jarvis,? (talk|reply|conversation))\\b",
            RegexOption.IGNORE_CASE,
        )
        private val LISTEN_RE = Regex(
            "\\b(just listen(ing)?|listen only|only listen|listen mode|listening mode|back to listening|jarvis,? listen)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val STOP_RE = Regex("\\b(stop|end|finish)\\s+(the\\s+)?(translating|translation|translator|interpreting)\\b|\\bjarvis,?\\s+stop\\b", RegexOption.IGNORE_CASE)

        fun isEnglish(code: String) = code.lowercase().startsWith("en")

        /**
         * The language of a transcript fragment. Gemini's per-fragment language code is sometimes
         * wrong or blank (on 2026-10-10 Mandarin was labelled French), so the script of the text
         * wins when it's unambiguous; Latin text keeps the reported code, falling back to [prev]
         * (the line's language so far) when the fragment has no code.
         */
        fun effectiveLang(reported: String, text: String, prev: String = ""): String {
            val code = normalizeLang(reported)
            val byScript = scriptLang(text)
            return when {
                byScript == null -> code.ifBlank { prev }
                byScript == "zh-Hans" && code.startsWith("zh") -> code // keep zh-Hant if reported
                else -> byScript
            }
        }

        /** Language implied by the writing system, or null for Latin/neutral text. */
        fun scriptLang(text: String): String? {
            var han = 0; var kana = 0; var hangul = 0; var arabic = 0; var cyr = 0; var deva = 0; var thai = 0; var letters = 0
            for (ch in text) {
                if (!Character.isLetter(ch)) continue
                letters++
                when (Character.UnicodeScript.of(ch.code)) {
                    Character.UnicodeScript.HAN -> han++
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                    Character.UnicodeScript.HANGUL -> hangul++
                    Character.UnicodeScript.ARABIC -> arabic++
                    Character.UnicodeScript.CYRILLIC -> cyr++
                    Character.UnicodeScript.DEVANAGARI -> deva++
                    Character.UnicodeScript.THAI -> thai++
                    else -> {}
                }
            }
            if (letters == 0) return null
            val half = letters / 2.0
            return when {
                kana > 0 && kana + han > half -> "ja"
                hangul > half -> "ko"
                han > half -> "zh-Hans"
                arabic > half -> "ar"
                cyr > half -> "ru"
                deva > half -> "hi"
                thai > half -> "th"
                else -> null
            }
        }

        /** Map whatever Gemini reports (zh, zh-CN, cmn, zh-Hant…) to a target code it accepts. */
        fun normalizeLang(code: String): String {
            val c = code.trim()
            if (c.isEmpty()) return ""
            val low = c.lowercase()
            return when {
                low == "zh-hant" || low == "zh-tw" || low == "zh-hk" -> "zh-Hant"
                low.startsWith("zh") || low == "cmn" -> "zh-Hans"
                else -> low.substringBefore('-').substringBefore('_')
            }
        }

        fun langName(code: String): String = when (normalizeLang(code)) {
            "zh-Hans", "zh-Hant" -> "Mandarin"
            "" -> "?"
            else -> Locale.forLanguageTag(normalizeLang(code)).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
        }

        /** "mandarin"/"chinese"/"french"/"arabic"… in a spoken command → target code. */
        fun langFromWords(s: String): String? {
            val t = s.lowercase()
            return LANG_WORDS.entries.firstOrNull { (w, _) -> Regex("\\b$w\\b").containsMatchIn(t) }?.value
        }

        val LANG_WORDS = linkedMapOf(
            "mandarin" to "zh-Hans", "chinese" to "zh-Hans", "cantonese" to "zh-Hant",
            "french" to "fr", "arabic" to "ar", "spanish" to "es", "portuguese" to "pt", "german" to "de",
            "italian" to "it", "japanese" to "ja", "korean" to "ko", "hindi" to "hi", "turkish" to "tr",
            "russian" to "ru", "hausa" to "ha", "swahili" to "sw", "dutch" to "nl",
        )
    }
}

/**
 * Streams 24 kHz PCM16 mono to one output. [toSpeaker] pins it to the phone's loudspeaker (so the
 * other person hears your translation) even while the glasses are the active media device; else it
 * follows the normal media route (the glasses when connected).
 */
class PcmPlayer(context: Context, toSpeaker: Boolean) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private lateinit var track: AudioTrack
    @Volatile private var framesWritten = 0L
    @Volatile private var lastAudibleAt = 0L

    // Blocking AudioTrack writes happen on our own thread, never on the WebSocket reader thread.
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    private val writer = Thread({
        while (running) {
            val pcm = runCatching { queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            runCatching { val n = track.write(pcm, 0, pcm.size); if (n > 0) framesWritten += n / 2 } // blocking write paces playback
        }
    }, "pcm-player").apply { isDaemon = true }


    init {
        val rate = 24_000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(min, rate * 2)) // ~1 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (toSpeaker) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { runCatching { track.setPreferredDevice(it) } }
        }
        track.play()
        writer.start()
    }

    fun write(pcm: ByteArray) {
        lastAudibleAt = System.currentTimeMillis()
        queue.offer(pcm)
    }

    /**
     * True while sound is actually coming out, or stopped less than [tailMs] ago. Measured from the
     * AudioTrack's playback head — NOT from when we queued the audio: the track buffers ~1 s, so the
     * old write-time estimate said "finished" up to a second early and the mic heard the rest (that
     * is what let the two directions translate each other in a loop).
     */
    fun isPlaying(tailMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val head = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(framesWritten)
        if (queue.isNotEmpty() || framesWritten - head > 240) lastAudibleAt = now // >10 ms still to play
        return now < lastAudibleAt + tailMs
    }

    fun isSpeakerRoute(): Boolean = track.routedDevice?.type.let {
        it == null || it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
    }

    fun routeName(): String = when (track.routedDevice?.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "phone earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "glasses"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "headphones"
        null -> "default"
        else -> "device ${track.routedDevice?.type}"
    }

    fun release() {
        running = false
        queue.clear()
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}
