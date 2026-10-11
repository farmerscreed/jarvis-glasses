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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
    private var micEffects: List<android.media.audiofx.AudioEffect> = emptyList()
    private var earPlayer: PcmPlayer? = null
    private var speakerPlayer: PcmPlayer? = null
    private val lines = mutableListOf<LiveLine>()
    private var nextId = 1L
    private val retries = mutableMapOf<String, Int>()

    // Per-direction line assembly (fragments arrive in pieces; a ≥1.6 s gap starts a new line).
    // [identity] = the "translation" came back as the same words, so the input was already in the
    // target language (English mislabelled as e.g. Swahili) — nothing of this line is played.
    // [echoLang] = the language of the echo being swallowed (other-language speech ends the swallow).
    // [mutedOut] = the last input wasn't in this direction's source language, so whatever the model
    // outputs next is a copy of it, not a translation — dropped until real source speech returns.
    // [lastInAt] = when this direction last heard its own source speech (the turn-taking clock).
    private class Cur(var id: Long = 0, var lastAt: Long = 0, var firstInAt: Long = 0, var firstOutAt: Long = 0, var hasOut: Boolean = false, var echo: Boolean = false, var identity: Boolean = false,
                      var echoLang: String = "", @Volatile var mutedOut: Boolean = false, @Volatile var lastInAt: Long = 0,
                      @Volatile var lastOutAt: Long = 0)
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

    private val dbg = TranslateDebugLog(context)
    // What the mic loop sent last chunk, per session (for the debug log): true = real audio, false = silence.
    @Volatile private var gateA = true
    @Volatile private var gateB = true
    private var mutedSince = 0L
    private fun gates() = "gate[A=${if (gateA) "open" else "muted"} B=${if (gateB) "open" else "muted"}]"

    /**
     * Experiment (P0 step 5): the phone's hardware echo canceller on the translator mic
     * (VOICE_COMMUNICATION source + AcousticEchoCanceler + NoiseSuppressor). Off by default; flip
     * `translate/aec` in prefs to compare. Never changes the audio mode (that would move the
     * glasses to the call/SCO route).
     */
    private val echoCancel: Boolean get() = prefs.getBoolean("aec", false)

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
        for (c in listOf(curThem, curMe)) { c.id = 0; c.echo = false; c.identity = false; c.mutedOut = false; c.lastInAt = 0 }
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
            // Our own audio came back in, or the input was already in the target language — never play it.
            if (cur.echo || cur.identity || cur.mutedOut) return
            // The model keeps streaming (near-)silent audio after a translation. Playing it made the
            // speaker look busy forever, which kept both mics muted — the 10-11 11:23 dead translator.
            // Only sound that comes with recent translated words is played.
            // (Quiet gaps between words inside a translation are kept, so speech isn't clipped.)
            val sinceWords = System.currentTimeMillis() - cur.lastOutAt
            if (sinceWords > QUIET_GAP_MS && TranslateText.peakLevel(pcm24k) < SILENT_PEAK) return
            if (cur.hasOut && sinceWords > AUDIO_AFTER_TEXT_MS) return
            // Only play audio that belongs to a translated utterance we've checked (echo=false still
            // streams some audio when the input is already in the target language).
            if (!cur.hasOut) {
                val q = pending(isA)
                val held = synchronized(q) { q.addLast(pcm24k); q.sumOf { it.size } }
                trimPending(isA)
                // A one-word translation never reaches the 2-word check — decide with what we have.
                if (held >= FORCE_DECIDE_BYTES) tryStartPlayback(cur, isA, final = true)
                return
            }
            if (!isA) {
                // Your words → loudspeaker: held until you pause, then played by the mic loop
                // (turn-taking — see speakWhenYouPause).
                val q = pending(false); synchronized(q) { q.addLast(pcm24k) }; trimPending(false)
                return
            }
            flushPending(true)
            earPlayer?.write(pcm24k)
        }

        override fun onInputText(session: LiveTranslateSession, text: String, lang: String) {
            val isA = session === sessionA
            val dir = if (isA) "A" else "B"
            // Commands are English words whatever language Gemini thinks it heard (it labelled
            // Law's "Stop. Stop." as Swahili) — but never act on our own playback heard again.
            val own = TranslateText.allWordsPlayed(text, recentPlayed(CMD_ECHO_MS))
            if (!own && handleVoiceCommand(text)) { dbg.log("$dir in COMMAND [$lang] $text"); return }
            if (own && isA) {
                // Our own ear audio leaking from the open-ear glasses back into the phone mic: never
                // part of their speech (it was being glued onto their line and played again).
                dbg.log("A in OWN-PLAYBACK dropped [$lang] $text")
                endLine(curThem, isA = true)
                return
            }
            // Trust the writing system over the reported code (Chinese characters are Chinese even if
            // the code says "fr"); Latin text that reads as English is English (accents get
            // mislabelled); otherwise the reported code decides.
            val l = effectiveLang(lang, text, if (isA) currentLineLang(true) else "", openLineText(isA))
            dbg.log("$dir in [$lang→$l] ${gates()}${if (own) " own-playback" else ""} $text")
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
                    // or them talking (A handles that). Either way B's next output is just a copy of
                    // it in their language — drop it (that copy, played on the loudspeaker and heard
                    // again, was the 10-11 conversation loop). Already-queued audio for your
                    // sentence is kept and still played.
                    curMe.mutedOut = true
                    if (speakerPlayer?.isPlaying(ECHO_WINDOW_MS) != true && synchronized(pendingB) { pendingB.isEmpty() }) endLine(curMe, isA = false)
                    return
                }
                appendInput(curMe, "me", "en", text, isA = false)
            }
        }

        override fun onOutputText(session: LiveTranslateSession, text: String, lang: String) {
            val isA = session === sessionA
            val cur = if (isA) curThem else curMe
            if (cur.id == 0L || cur.echo || cur.identity) return
            if (cur.mutedOut) { dbg.log("${if (isA) "A" else "B"} out MUTED (copy of other-language input) $text"); return }
            updateLine(cur.id) { it.copy(translation = it.translation + text) }
            cur.lastAt = System.currentTimeMillis()
            if (text.isNotBlank()) cur.lastOutAt = cur.lastAt
            if (isA && !cur.hasOut) {
                // Gemini sometimes writes Law's English down in another language ("Jarvis, let me
                // talk" → "Laat me praten") — its English translation still carries the command.
                val tr = synchronized(lines) { lines.lastOrNull { it.id == cur.id }?.translation }.orEmpty()
                val cmd = commandOf(tr, _ui.value.mode)
                if (cmd != null) {
                    dbg.log("A out COMMAND ($cmd) from translation: $tr")
                    val id = cur.id
                    synchronized(lines) { lines.removeAll { it.id == id } }
                    endLine(cur, isA = true)
                    publishLines()
                    actOnCommand(cmd)
                    return
                }
            }
            dbg.log("${if (isA) "A" else "B"} out [$lang] ${if (cur.hasOut) "" else "(checking) "}$text")
            if (cur.hasOut) remember(if (isA) playedA else playedB, text)
            else tryStartPlayback(cur, isA, final = false)
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
        cur.identity = false
        val q = pending(isA)
        synchronized(q) { q.clear() }
    }

    /**
     * The first translated words of a line have arrived: decide whether it should be heard at all.
     * If the "translation" is the same words as the input, the input was already in the target
     * language (Law's English labelled Swahili) — that line is shown as English and never played.
     * Otherwise start playback. [final] = decide now even on a single word.
     */
    private fun tryStartPlayback(cur: Cur, isA: Boolean, final: Boolean) {
        if (cur.id == 0L || cur.hasOut || cur.echo || cur.identity) return
        val line = synchronized(lines) { lines.lastOrNull { it.id == cur.id } } ?: return
        if (line.translation.isBlank()) return
        val same = TranslateText.isIdentity(line.text, line.translation, final) ?: return
        val dir = if (isA) "A" else "B"
        if (same) {
            dbg.log("$dir IDENTITY dropped: \"${line.text.take(80)}\" → \"${line.translation.take(80)}\"")
            Log.i(TAG, "identity translation dropped ($dir): ${line.text.take(60)}")
            cur.identity = true
            val q = pending(isA); synchronized(q) { q.clear() }
            if (isA && _ui.value.mode == TranslateMode.LISTEN) {
                // Listening: it's English speech — show it as an English caption, play nothing.
                updateLine(cur.id) { it.copy(lang = "en", translation = "") }
            } else {
                // Conversation: English reaching A is you (B translates it); on B it's their own
                // language. Drop the line; the next fragment starts fresh (and is checked again), so
                // their speech straight after is never swallowed.
                val id = cur.id
                synchronized(lines) { lines.removeAll { it.id == id } }
                endLine(cur, isA)
                publishLines()
            }
            return
        }
        cur.hasOut = true
        cur.firstOutAt = System.currentTimeMillis()
        val lag = cur.firstOutAt - cur.firstInAt
        updateLine(cur.id) { it.copy(lagMs = lag) }
        if (isA) setStatus(lagMs = lag)
        remember(if (isA) playedA else playedB, line.translation)
        dbg.log("$dir PLAY lag=${lag}ms \"${line.text.take(60)}\" → \"${line.translation.take(60)}\"")
        if (isA) flushPending(true) // B (loudspeaker) waits for you to pause — speakWhenYouPause()
    }

    /**
     * Turn-taking for the loudspeaker (conversation mode). Your translation is held while you are
     * still talking and played once you pause; while it plays, BOTH directions stop listening (mic
     * loop). Without this the phone mic heard the loudspeaker mid-sentence and translated it again,
     * forever (10-11 T6). Called from the mic loop every 100 ms.
     */
    private fun speakWhenYouPause(now: Long) {
        if (!curMe.hasOut || curMe.identity || curMe.echo) return
        if (now - curMe.lastInAt < SPEAK_AFTER_PAUSE_MS) return
        val q = pendingB
        val bytes = synchronized(q) { q.sumOf { it.size } }
        if (bytes == 0) return
        dbg.log("B speaking ${bytes * 1000L / (24_000 * 2)} ms of audio (you paused ${now - curMe.lastInAt} ms)")
        flushPending(false)
    }

    private fun appendInput(cur: Cur, who: String, lang: String, text: String, isA: Boolean) {
        val now = System.currentTimeMillis()
        if (cur.echo) {
            // Still inside an echo: swallow the rest of it until a real pause — but speech in
            // another language is a person, not the echo, so it ends the swallow at once.
            if (now - cur.lastAt <= LINE_GAP_MS && lang == cur.echoLang) { cur.lastAt = now; dbg.log("${if (isA) "A" else "B"} swallowed (echo tail): $text"); return }
            cur.echo = false; cur.id = 0
        }
        cur.lastInAt = now
        cur.mutedOut = false
        val langChanged = cur.id != 0L && synchronized(lines) { lines.lastOrNull { it.id == cur.id }?.lang }.let { it != null && it != lang }
        if (cur.id == 0L || now - cur.lastAt > LINE_GAP_MS || langChanged) {
            if (langChanged) { cur.hasOut = false; val q = pending(isA); synchronized(q) { q.clear() } }
            cur.id = nextId++
            cur.firstInAt = now
            cur.hasOut = false
            cur.identity = false
            val q = pending(isA)
            synchronized(q) { q.clear() } // stale audio from before this utterance never plays
            synchronized(lines) { lines.add(LiveLine(cur.id, who, lang, text.trimStart(), "")) }
        } else {
            updateLine(cur.id) { it.copy(text = it.text + text) }
        }
        cur.lastAt = now
        // Is this line just our own translation coming back through the mic? Compared with BOTH
        // outputs: the loudspeaker, and your ear — open-ear glasses (or the phone speaker when the
        // glasses aren't the sound route) are heard by the phone mic too, whatever Gemini labels it.
        val sofar = currentText(isA)
        if (TranslateText.isEcho(sofar, recentPlayed(ECHO_MEMORY_MS))) {
            Log.i(TAG, "echo dropped (${if (isA) "A" else "B"}): ${sofar.take(60)}")
            dbg.log("${if (isA) "A" else "B"} ECHO dropped: $sofar")
            cur.echo = true
            cur.echoLang = lang
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

    /** Everything played (ear + loudspeaker) in the last [windowMs]. */
    private fun recentPlayed(windowMs: Long): List<String> {
        val since = System.currentTimeMillis() - windowMs
        return (playedA + playedB).filter { it.first > since }.map { it.second }
    }

    /** The line this direction is still building (if a fragment now would extend it), else "". */
    private fun openLineText(isA: Boolean): String {
        val cur = if (isA) curThem else curMe
        if (cur.id == 0L || System.currentTimeMillis() - cur.lastAt > LINE_GAP_MS) return ""
        return currentText(isA)
    }

    // ── voice commands said in English while translating ───────────────────────────────────────
    /** "stop translating" · "Jarvis, let me talk" · "Jarvis, just listen". Returns true if handled. */
    private fun handleVoiceCommand(text: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - cmdAt > 5_000) cmdBuf = ""
        cmdBuf = (cmdBuf + " " + text).takeLast(160); cmdAt = now
        val cmd = commandOf(cmdBuf, _ui.value.mode) ?: return false
        cmdBuf = ""
        actOnCommand(cmd)
        return true // a command is never translated, even when it was already acted on
    }

    /** Run a recognised command once (both directions — and the translation — carry the same words). */
    private fun actOnCommand(cmd: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCmdAt < 3_000) return false
        lastCmdAt = now
        when (cmd) {
            "stop" -> stop(save = true)
            "talk" -> switchMode(TranslateMode.CONVERSATION)
            "listen" -> switchMode(TranslateMode.LISTEN)
        }
        return true
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
        // Your translation (B), once confirmed, is held for the whole sentence until you pause;
        // anything not yet tied to translated words only briefly.
        val max = if (!isA && curMe.hasOut) SPEAKER_HOLD_MAX_BYTES else PENDING_MAX_BYTES
        synchronized(q) { while (q.sumOf { it.size } > max) q.removeFirst() }
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
        val aec = echoCancel
        val source = if (aec) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_BYTES * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            setStatus(error = "Microphone unavailable"); stop(save = false); return
        }
        if (aec) {
            val ec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            micEffects = listOfNotNull(ec, ns)
            dbg.log("mic AEC experiment: source=VOICE_COMMUNICATION aec=${ec?.enabled} ns=${ns?.enabled}")
        }
        // Prefer the phone's own mic — it hears both people; the glasses mic only hears the wearer.
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { runCatching { rec.setPreferredDevice(it) } }
        record = rec
        rec.startRecording()
        Log.i(TAG, "mic started: routed=${rec.routedDevice?.type}")
        dbg.log("=== start mode=${_ui.value.mode} their=${_ui.value.theirLang} ear=${_ui.value.earRoute} mic=${rec.routedDevice?.type} aec=$aec")
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
                // Turn-taking: your translation only plays once you've paused, so the loudspeaker
                // closes BOTH directions (it can't cut your sentence off any more).
                var openA = !(held || speakerBusy || earOnSpeaker)
                var openB = !(held || earBusy || speakerBusy)
                // Safety net: playback must never keep a direction deaf for long. If it does,
                // something is streaming that shouldn't be — cut it and listen again.
                val wordsAgo = now - maxOf(curThem.lastOutAt, curMe.lastOutAt)
                if (!held && (!openA || (!openB && sessionB != null))) {
                    if (mutedSince == 0L) mutedSince = now
                    // Only when the playback has no translated words behind it (a long real
                    // translation keeps producing words and is never cut).
                    if (now - mutedSince > MAX_MUTE_MS && wordsAgo > MAX_MUTE_MS) {
                        dbg.log("SAFETY: mic muted ${now - mutedSince} ms by playback — cutting playback")
                        Log.w(TAG, "playback kept the mic muted too long — cut")
                        synchronized(pendingA) { pendingA.clear() }; synchronized(pendingB) { pendingB.clear() }
                        earPlayer?.clear(); speakerPlayer?.clear()
                        openA = true; openB = true; mutedSince = 0L
                    }
                } else mutedSince = 0L
                if (sessionB != null) speakWhenYouPause(now)
                if (openA != gateA || openB != gateB) dbg.log("gates A=${if (openA) "open" else "muted"} B=${if (openB) "open" else "muted"} (held=$held speaker=$speakerBusy ear=$earBusy earOnSpeaker=$earOnSpeaker)")
                gateA = openA; gateB = openB
                sessionA?.sendPcm16k(if (openA) buf else silence, n)
                sessionB?.sendPcm16k(if (openB) buf else silence, n)
            }
        }
    }

    /** Stop translating. If [save], summarize + store the conversation as a memory. */
    fun stop(save: Boolean = true) {
        if (!active) return
        active = false
        micJob?.cancel(); micJob = null
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        micEffects.forEach { runCatching { it.release() } }; micEffects = emptyList()
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
        private const val TOKEN_FRESH_MS = 90_000L
        private const val CMD_ECHO_MS = 8_000L               // a command whose words were all just played is ours
        private const val FORCE_DECIDE_BYTES = 24_000 * 2 * 8 / 10 // 0.8 s of held audio → decide on one word
        private const val SPEAK_AFTER_PAUSE_MS = 900L               // you've stopped talking → speak your translation
        private const val SPEAKER_HOLD_MAX_BYTES = 24_000 * 2 * 30  // ≤30 s of your translation held
        private const val SILENT_PEAK = 400                         // ≈ -38 dBFS: model filler, not speech
        private const val AUDIO_AFTER_TEXT_MS = 4_000L              // audio this long after the last words is filler
        private const val QUIET_GAP_MS = 1_500L                     // quiet audio this long after words = filler
        private const val MAX_MUTE_MS = 10_000L                     // safety: mic shut this long by wordless playback → cut it

        /** "after the match we go to a small after the match we go to a small …" → one copy. */
        fun collapseRepeats(t: String): String =
            if (t.length < 36) t else REPEAT_RE.replace(t) { it.groupValues[1] }
        private val REPEAT_RE = Regex("(.{12,120}?)(?:\\s*\\1){2,}")
        private const val PENDING_MAX_BYTES = 24_000 * 2 * 2 // ≤2 s of 24 kHz audio held before text arrives
        // Gemini hears "Jarvis" as Jarpis / Jervis / Javis / Travis on Law's voice (10-11).
        private const val JARVIS = "(?:jarvis|jarpis|jervis|javis|jarvi|travis)"
        private val TALK_RE = Regex(
            """\b(let me (talk|reply|respond|answer|speak)|$JARVIS,? let'?s talk|i want to (talk|reply|respond)|conversation mode|talk mode|reply mode|""" +
                """talk to (him|her|them)|start (the )?conversation|$JARVIS,? (talk|reply|conversation))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val LISTEN_RE = Regex(
            """\b(just listen(ing)?|listen only|only listen|listen mode|listening mode|back to listening|$JARVIS,? listen)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val STOP_RE = Regex("""\b(stop|end|finish)\s+(the\s+)?(translating|translation|translator|interpreting)\b|\b$JARVIS,?\s+stop\b""", RegexOption.IGNORE_CASE)

        /** "stop" / "talk" / "listen" if [text] holds a translator command that applies in [mode], else null. */
        fun commandOf(text: String, mode: TranslateMode): String? = when {
            STOP_RE.containsMatchIn(text) -> "stop"
            mode == TranslateMode.LISTEN && TALK_RE.containsMatchIn(text) -> "talk"
            mode == TranslateMode.CONVERSATION && LISTEN_RE.containsMatchIn(text) -> "listen"
            else -> null
        }

        fun isEnglish(code: String) = code.lowercase().startsWith("en")

        /**
         * The language of a transcript fragment. Gemini's per-fragment language code is sometimes
         * wrong or blank (on 2026-10-10 Mandarin was labelled French; on 10-11 Law's English was
         * labelled Swahili), so the script of the text wins when it's unambiguous, and Latin text
         * that reads as English is English. Short fragments are judged together with [lineSoFar]
         * (the open line they extend). Otherwise the reported code, falling back to [prev] (the
         * line's language so far) when the fragment has no code.
         */
        fun effectiveLang(reported: String, text: String, prev: String = "", lineSoFar: String = ""): String {
            val code = normalizeLang(reported)
            val byScript = scriptLang(text)
            // A 1–2 word fragment continuing a line is judged with that line ("nutrition." ends an
            // English sentence; "Okay." inside a French one stays French).
            val shortContinuation = lineSoFar.isNotBlank() && TranslateText.latinWords(text).size < 3
            val english = byScript == null &&
                TranslateText.looksEnglish(if (shortContinuation) "$lineSoFar $text" else text)
            return when {
                english -> "en"
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
    @Volatile private var needsTailPad = false
    private val tailPad = ByteArray(24_000 * 2 / 4) // 250 ms of silence
    private val writer = Thread({
        while (running) {
            val pcm = runCatching { queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
            if (pcm == null) {
                // Nothing new: AudioTrack keeps the last small piece back until more arrives, so the
                // end of a translation never "finished" and the mic stayed muted (10-11). Push it
                // out with a little silence (not counted as speech).
                if (needsTailPad) { needsTailPad = false; runCatching { track.write(tailPad, 0, tailPad.size) } }
                continue
            }
            runCatching { val n = track.write(pcm, 0, pcm.size); if (n > 0) framesWritten += n / 2 } // blocking write paces playback
            needsTailPad = true
        }
    }, "pcm-player").apply { isDaemon = true }
    @Volatile private var lastHead = -1L
    @Volatile private var lastHeadMoveAt = 0L


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
        if (head != lastHead) { lastHead = head; lastHeadMoveAt = now }
        // A head that hasn't moved for 600 ms with nothing queued is stalled, not playing.
        val stalled = queue.isEmpty() && now - lastHeadMoveAt > 600
        if (!stalled && (queue.isNotEmpty() || framesWritten - head > 240)) lastAudibleAt = now // >10 ms still to play
        return now < lastAudibleAt + tailMs
    }

    /** Drop everything queued or buffered and go quiet now (playback stays ready for new audio). */
    fun clear() {
        queue.clear()
        needsTailPad = false
        runCatching { track.pause(); track.flush(); track.play() }
        framesWritten = runCatching { track.playbackHeadPosition.toLong() and 0xffffffffL }.getOrDefault(framesWritten)
        lastAudibleAt = 0L
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
