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
import kotlinx.coroutines.flow.MutableStateFlow
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
 *  - While the loudspeaker is talking, the mic feeds silence so A doesn't re-translate it back.
 *  - Audio that arrives without translated words (echo=false still streams a little audio) is
 *    dropped — we only play audio that comes with an output transcript.
 *
 * LISTEN mode = session A only (meetings, talks, announcements): English in your ear, nothing out loud.
 * Say "stop translating" (in English) or press Stop to end; the conversation is then summarized
 * and saved to memory by the `translate` Edge Function.
 */
@Singleton
class TranslationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: EchoBackend,
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
    private class Cur(var id: Long = 0, var lastAt: Long = 0, var firstInAt: Long = 0, var firstOutAt: Long = 0, var hasOut: Boolean = false)
    private val curThem = Cur()
    private val curMe = Cur()

    val isActive: Boolean get() = active

    /** Start translating. [presetTarget] = their language if known ("zh-Hans", "fr", "ar"…). */
    fun start(mode: TranslateMode, presetTarget: String? = null) {
        if (active) return
        active = true
        startedAt = System.currentTimeMillis()
        synchronized(lines) { lines.clear() }
        retries.clear()
        curThem.id = 0; curMe.id = 0
        val target = presetTarget?.let { normalizeLang(it) }.orEmpty()
        _ui.value = TranslateUi(
            active = true, mode = mode, theirLang = target,
            status = if (mode == TranslateMode.LISTEN) "Connecting…" else "Connecting…",
        )
        scope.launch {
            earPlayer = PcmPlayer(context, toSpeaker = false)
            if (mode == TranslateMode.CONVERSATION) speakerPlayer = PcmPlayer(context, toSpeaker = true)
            _ui.value = _ui.value.copy(earRoute = earPlayer?.routeName().orEmpty(), speakerRoute = speakerPlayer?.routeName().orEmpty())
            openSession("en")
            if (mode == TranslateMode.CONVERSATION && target.isNotEmpty() && !target.startsWith("en")) openSession(target)
            startMic()
        }
    }

    private suspend fun openSession(target: String) {
        if (!active) return
        if (target != "en") openingB = true
        try {
            val tok = backend.translateToken(target)
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
                u.mode == TranslateMode.LISTEN -> "Listening — translating to English"
                u.theirLang.isEmpty() -> "Ready — let them speak first"
                else -> "Ready — ${langName(u.theirLang)} ⇄ English"
            }
            setStatus(status = st)
        }

        override fun onAudio(session: LiveTranslateSession, pcm24k: ByteArray) {
            val isA = session === sessionA
            val cur = if (isA) curThem else curMe
            // Only play audio that belongs to a translated utterance (echo=false still streams some
            // audio when the input is already in the target language).
            if (!cur.hasOut) { val q = pending(isA); synchronized(q) { q.addLast(pcm24k) }; trimPending(isA); return }
            flushPending(isA)
            (if (isA) earPlayer else speakerPlayer)?.write(pcm24k)
        }

        override fun onInputText(session: LiveTranslateSession, text: String, lang: String) {
            val isA = session === sessionA
            val l = normalizeLang(lang)
            if (isEnglish(l) && STOP_RE.containsMatchIn(currentText(isA) + text)) { stop(save = true); return }
            if (isA) {
                if (isEnglish(l)) {
                    // That's you (session B handles your speech). End their current line and drop any
                    // audio A streams for your English, so it can never leak into your ear.
                    endLine(curThem, isA = true)
                    return
                }
                appendInput(curThem, "them", l, text, isA = true)
                // First time we hear their language in a conversation → open the reverse direction.
                if (l.isNotBlank() && _ui.value.mode == TranslateMode.CONVERSATION && sessionB == null && !openingB && text.trim().length >= 2) {
                    setStatus(theirLang = l, status = "Heard ${langName(l)} — connecting your side…")
                    scope.launch { openSession(l) }
                } else if (_ui.value.theirLang.isEmpty() && l.isNotBlank()) setStatus(theirLang = l)
            } else {
                if (!isEnglish(l)) { endLine(curMe, isA = false); return } // their speech reaching B — A handles it
                appendInput(curMe, "me", "en", text, isA = false)
            }
        }

        override fun onOutputText(session: LiveTranslateSession, text: String, lang: String) {
            val isA = session === sessionA
            val cur = if (isA) curThem else curMe
            if (cur.id == 0L) return
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
        if (cur.id == 0L || now - cur.lastAt > LINE_GAP_MS) {
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
        publishLines()
    }

    private fun currentText(isA: Boolean): String {
        val id = (if (isA) curThem else curMe).id
        return synchronized(lines) { lines.lastOrNull { it.id == id }?.text.orEmpty() }
    }

    private fun updateLine(id: Long, f: (LiveLine) -> LiveLine) {
        synchronized(lines) {
            val i = lines.indexOfFirst { it.id == id }
            if (i >= 0) lines[i] = f(lines[i]).copy(updatedAt = System.currentTimeMillis())
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
                // Don't let the loudspeaker's output (or a phone-speaker ear route) loop back in.
                val gate = speakerPlayer?.recentlyPlayed(GATE_MS) == true ||
                    (earPlayer?.isSpeakerRoute() == true && earPlayer?.recentlyPlayed(GATE_MS) == true)
                val out = if (gate) silence else buf
                sessionA?.sendPcm16k(out, n)
                sessionB?.sendPcm16k(out, n)
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
        private const val GATE_MS = 350L
        private const val PENDING_MAX_BYTES = 24_000 * 2 * 2 // ≤2 s of 24 kHz audio held before text arrives
        private val STOP_RE = Regex("\\b(stop|end|finish)\\s+(the\\s+)?(translating|translation|translator|interpreting)\\b|\\bjarvis,?\\s+stop\\b", RegexOption.IGNORE_CASE)

        fun isEnglish(code: String) = code.lowercase().startsWith("en")

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
    @Volatile private var lastWriteEndsAt = 0L

    // Blocking AudioTrack writes happen on our own thread, never on the WebSocket reader thread.
    private val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    private val writer = Thread({
        while (running) {
            val pcm = runCatching { queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            runCatching { track.write(pcm, 0, pcm.size) } // blocking write paces playback
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
        val now = System.currentTimeMillis()
        val durMs = pcm.size / 48L // 24 kHz × 2 bytes = 48 bytes per ms
        lastWriteEndsAt = maxOf(lastWriteEndsAt, now) + durMs
        queue.offer(pcm)
    }

    /** True if audio is playing now or finished within [ms]. */
    fun recentlyPlayed(ms: Long): Boolean = System.currentTimeMillis() < lastWriteEndsAt + ms

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
