package com.echo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import com.echo.device.audio.BtAudioEngine
import com.echo.device.audio.EarconKind
import com.echo.device.audio.TtsEngine
import com.echo.device.audio.WakeWordEngine
import com.echo.device.audio.WavUtil
import com.echo.device.ble.GlassesBleManager
import com.echo.device.ble.GlassesEvent
import com.echo.device.wifi.GlassesP2pManager
import com.echo.device.wifi.MediaTransferClient
import java.io.File
import com.echo.memory.ChatResult
import com.echo.memory.ConnectivityGovernor
import com.echo.memory.EchoBackend
import com.echo.memory.MemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backend: EchoBackend,
    private val store: MemoryStore,
    private val governor: ConnectivityGovernor,
    private val audio: BtAudioEngine,
    private val ble: GlassesBleManager,
    private val tts: TtsEngine,
    private val wake: WakeWordEngine,
    private val buttons: GlassesButtonController,
    private val p2p: GlassesP2pManager,
    private val transfer: MediaTransferClient,
    private val reactor: GlassesCaptureReactor,
) : ViewModel() {

    var loggedIn by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Not signed in"); private set

    /** Email-OTP sign-in state. The dev password login only exists in the dev flavor. */
    val devLoginEnabled = BuildConfig.DEV_LOGIN
    var email by mutableStateOf("")
    var otpCode by mutableStateOf("")
    var otpSent by mutableStateOf(false); private set

    var memoryText by mutableStateOf("I parked on level 3 near the blue elevator.")
    var question by mutableStateOf("what did Sam say about the budget?")
    var answer by mutableStateOf(""); private set
    var recalled by mutableStateOf<List<Memory>>(emptyList()); private set

    var audioStatus by mutableStateOf("Tap to test the glasses mic + speaker"); private set
    var bleStatus by mutableStateOf("BLE idle"); private set

    /** Phase D: time-to-first-spoken-word of the last voice turn (ms), for the latency war. */
    var lastLatencyMs by mutableStateOf(0L); private set
    var handsFree by mutableStateOf(false); private set

    /** Offline-first surface: live online state, tier, + count of memories not yet pushed. */
    var online by mutableStateOf(true); private set
    var tier by mutableStateOf("full"); private set
    var pendingSync by mutableStateOf(0); private set

    /** True while the shared capture reactor is running a sync (mirrors reactor.working). */
    var syncing by mutableStateOf(false); private set

    init {
        // Restore sign-in from the persisted session token (survives app restarts).
        if (backend.isLoggedIn) { loggedIn = true; status = "Signed in (restored)" }
        // Offline-first: mirror connectivity + tier + outbox depth into Compose state.
        viewModelScope.launch { governor.online.collect { online = it } }
        viewModelScope.launch { governor.tier.collect { tier = it.name.lowercase() } }
        viewModelScope.launch { store.pendingCount().collect { pendingSync = it } }
        // Mirror the BLE manager's status into Compose state (declared after the state it sets).
        viewModelScope.launch { ble.status.collect { bleStatus = it } }
        // Glasses physical button -> start a voice turn (hands-free, no phone).
        buttons.onTrigger = { onGlassesButton() }
        buttons.activate()
        // Phase B + foreground service: the shared reactor owns capture reactions (auto-sync +
        // enrich). The VM is one "host" — it also runs when the app is foregrounded; the service
        // is the other. Mirror the reactor's progress into the UI while it's working and we're
        // not mid-foreground-action, and surface new memories as they land.
        reactor.start()
        viewModelScope.launch { reactor.working.collect { syncing = it; if (!it) refreshLibrary() } }
        viewModelScope.launch { reactor.status.collect { if (!busy) status = it } }
        // Glasses-triggered Look & Ask (double-click BACK) speaks; also show it on screen.
        viewModelScope.launch { reactor.lastAnswer.collect { if (it.isNotEmpty()) answer = it } }
    }

    /**
     * Backfill: any media file already in local storage that has no memory yet (e.g. downloaded by
     * an older build before manual sync created memories) gets imported silently via the reactor.
     * Runs once when the library screens load, so orphaned captures surface in Timeline/Gallery.
     */
    private var reconciled = false
    fun reconcileOrphanMedia() {
        if (reconciled || !loggedIn) return
        reconciled = true
        viewModelScope.launch {
            val known = runCatching { store.knownLocalPaths() }.getOrDefault(emptySet())
            val all = transfer.mediaFiles()
            val orphans = all.filter { it.absolutePath !in known }
            android.util.Log.i("EchoReconcile", "media=${all.size} known=${known.size} orphans=${orphans.size}")
            if (orphans.isEmpty()) return@launch
            status = "Importing ${orphans.size} earlier capture(s)…"
            reactor.routeFiles(orphans, speak = false)
            refreshLibrary()
            status = "Imported ${orphans.size} earlier capture(s)"
        }
    }

    private fun onGlassesButton() {
        if (busy) return
        if (!loggedIn) { status = "Glasses button pressed — sign in first"; return }
        run("Glasses button — listening…") { doTalk() }
    }

    /** Dev-flavor shortcut against the local stack; the button is absent in prod builds. */
    fun signIn() = run("Signing in…") {
        check(devLoginEnabled) { "dev login is disabled in this build" }
        backend.signIn("tester@local.dev", "password123")
        loggedIn = true
        status = "Signed in as tester@local.dev"
    }

    /** Whether to show "Continue with Google" (Google OAuth client configured at build time). */
    val googleSignInEnabled = GoogleSignInHelper.isConfigured

    /** Google One-Tap: get an ID token via Credential Manager, exchange it with Supabase. */
    fun signInWithGoogle(context: android.content.Context) = run("Signing in with Google…") {
        val idToken = GoogleSignInHelper.getIdToken(context)
        backend.signInWithGoogle(idToken)
        loggedIn = true
        status = "Signed in with Google"
    }

    fun sendOtp() = run("Sending code…") {
        val addr = email.trim()
        if (addr.isBlank() || '@' !in addr) { status = "Enter your email address"; return@run }
        backend.requestEmailOtp(addr)
        otpSent = true
        status = "Code sent — check $addr"
    }

    fun verifyOtp() = run("Verifying…") {
        val code = otpCode.trim()
        if (code.isBlank()) { status = "Enter the 6-digit code from the email"; return@run }
        backend.verifyEmailOtp(email.trim(), code)
        loggedIn = true
        otpSent = false
        otpCode = ""
        status = "Signed in as ${email.trim()}"
    }

    fun signOut() = run("Signing out…") {
        backend.signOut()
        loggedIn = false
        otpSent = false
        otpCode = ""
        status = "Not signed in"
    }

    fun remember() = run("Saving memory…") {
        store.remember(Memory(type = MemoryType.NOTE, text = memoryText))
        status = if (online) "Remembered" else "Saved on phone — will sync when back online"
    }

    /**
     * D2 streaming turn: speak each sentence as Claude generates it instead of waiting for the
     * full answer. Returns null when the stream fails (caller falls back to non-streaming [EchoBackend.chat]).
     */
    private suspend fun streamedChat(message: String, onFirstSentence: () -> Unit = {}): ChatResult? {
        if (!tts.beginStream()) return null
        var spoke = false
        val chunker = SentenceChunker { s ->
            if (!spoke) { spoke = true; onFirstSentence(); status = "Speaking…" }
            tts.enqueue(s)
        }
        val result = runCatching {
            backend.chatStream(message) { chunker.add(it) }.also { chunker.flush() }
        }.getOrNull() ?: return null
        if (spoke) tts.finishStream() // wait out the queued speech before the turn ends
        return result
    }

    fun ask() = run("Thinking…") {
        if (online) {
            // FULL tier: stream the answer, speaking sentence-by-sentence (D2). On a slow/LEAN
            // link — or if the stream fails — fall back to one-shot chat with the adaptive
            // timeout (§4.4) that fails fast to the on-device answer.
            val streamed = if (tier == "full") streamedChat(question) else null
            val result = streamed ?: withTimeoutOrNull(12_000) { backend.chat(question) }
            if (result != null) {
                answer = result.answer
                recalled = result.memoriesUsed
                status = "Answered"
            } else {
                val hits = store.recall(question, limit = 3)
                recalled = hits
                answer = "Your connection's too slow right now, so here's what I have on-device:\n\n" +
                    JarvisLite.answer(question, hits)
                status = "Answered from memory (slow connection)"
                tts.speak(answer)
            }
        } else {
            // Off-grid: no Claude, but Jarvis Lite phrases an answer from on-device semantic recall.
            status = "Off-grid — searching your memories…"
            val hits = store.recall(question, limit = 3)
            recalled = hits
            answer = JarvisLite.answer(question, hits)
            status = "Speaking…"
            tts.speak(answer)
            status = "Off-grid answer"
        }
    }

    /** Phase 0C audio loop: record from the glasses mic (SCO), then play it back (A2DP). */
    fun testAudio() = run("Recording 4s — speak into the glasses…") {
        val rec = audio.record(4000)
        audioStatus = "Recorded %.1fs · peak %d · rms %d".format(rec.seconds, rec.peak, rec.rms)
        status = "Playing back through the glasses…"
        delay(600)
        audio.play(rec.pcm, rec.sampleRate)
        audioStatus += " · played back"
        status = "Audio loop done"
    }

    /** The flagship voice loop: glasses mic -> STT -> Claude (RAG) -> TTS in your ear. */
    fun talk() = run("Listening — speak into the glasses…") { doTalk() }

    private suspend fun doTalk() {
        // Off-grid: cloud STT (Gemini) is unavailable and on-device dictation isn't wired yet, so
        // fall back to the text path (modality fallback §4.5) rather than recording pointlessly.
        if (!online) {
            answer = "I can't transcribe speech while we're off-grid yet. Type your question and " +
                "I'll answer from your memories."
            status = "Off-grid — type to ask"
            tts.speak("I can't hear you in detail off-grid yet. Type your question.")
            return
        }
        val t0 = System.currentTimeMillis()
        audio.earcon(EarconKind.LISTENING)
        // Phase D: stop recording when you stop talking, not after a fixed 5 s.
        val rec = audio.recordUntilSilence()
        val tRecord = System.currentTimeMillis() - t0
        status = "Transcribing…"
        val sttStart = System.currentTimeMillis()
        val heard = runCatching { backend.transcribe(WavUtil.pcm16ToWav(rec.pcm, rec.sampleRate)) }.getOrDefault("")
        val tStt = System.currentTimeMillis() - sttStart
        question = heard.ifBlank { "(didn't catch that)" }
        if (heard.isNotBlank()) {
            audio.earcon(EarconKind.THINKING)
            status = "Thinking…"
            val llmStart = System.currentTimeMillis()
            // D2: stream — first sentence is spoken while Claude writes the rest.
            var toSpeak = 0L
            val streamed = if (tier == "full") {
                streamedChat(heard) { toSpeak = System.currentTimeMillis() - t0 }
            } else null
            val result = streamed ?: backend.chat(heard)
            val tLlm = System.currentTimeMillis() - llmStart
            answer = result.answer
            recalled = result.memoriesUsed
            if (streamed == null) {
                toSpeak = System.currentTimeMillis() - t0
                lastLatencyMs = toSpeak
                status = "Speaking…"
                tts.speak(result.answer)
            } else {
                lastLatencyMs = toSpeak
            }
            android.util.Log.i(
                "EchoLatency",
                "record=${tRecord}ms (%.1fs audio) stt=${tStt}ms llm=${tLlm}ms streamed=${streamed != null} time-to-speak=${toSpeak}ms".format(rec.seconds),
            )
            status = "Done · ${toSpeak}ms to first word"
        } else {
            status = "Didn't catch that — try again"
        }
    }

    /** Toggle hands-free wake-word listening (say "Jarvis"). */
    fun toggleHandsFree(on: Boolean) {
        handsFree = on
        if (on) {
            if (startWake()) status = "Hands-free on — say “Jarvis”"
            else { handsFree = false; status = "Wake word error: ${wake.lastError}" }
        } else {
            wake.stop()
            status = "Hands-free off"
        }
    }

    private fun startWake(): Boolean = wake.start {
        // "Jarvis" detected. Release the mic, run one voice turn, then resume listening.
        wake.stop()
        run("Heard “Jarvis” — listening…") {
            doTalk()
            if (handsFree) startWake()
        }
    }

    override fun onCleared() {
        wake.stop()
        reactor.stop() // detach this host; the reactor keeps running if the service is also a host
        super.onCleared()
    }

    /** Phase 0D: discover the glasses' GATT (resolve the camera endpoint / handle 0x008E). */
    fun runBleDiagnostic() = ble.runDiagnostic()

    /** Phase 0D: write the reverse-engineered capture command to the resolved characteristic. */
    fun capturePhoto() = ble.capturePhoto()

    /**
     * Phase 2: pull the glasses' captured media into the app via the shared reactor (same ceremony
     * the autonomous capture path uses; serialized by the reactor's mutex). Progress + the result
     * count surface through reactor.status / reactor.working, mirrored into [status] / [syncing].
     */
    fun syncGlasses() {
        viewModelScope.launch {
            reactor.syncNow()
            refreshLibrary()
        }
    }

    /** Look & Ask: send the most recently synced photo to Claude vision, speak + remember it. */
    fun lookAndAsk() = run("Looking…") {
        if (!loggedIn) { status = "Sign in first"; return@run }
        // Pull anything new off the glasses first (the reliable ceremony — connects BLE fresh),
        // so "Look & Ask" describes the photo you just took, not a stale one.
        status = "Syncing the latest shot…"
        runCatching { reactor.syncNow() }
        refreshLibrary()
        val photo = transfer.latestPhoto()
        if (photo == null) { status = "No photo yet — take one with the glasses, then tap Look & Ask"; return@run }
        status = "Claude is looking at ${photo.name}…"
        val bytes = photo.readBytes()
        // Offline-resilient: if vision can't run (off-grid), save the photo with a placeholder +
        // needs_vision so it's never lost; the deferred re-run describes it once we're back online.
        val visioned = runCatching {
            backend.describeImage(bytes, "You are JARVIS. Say what's in this photo in one or two natural spoken sentences.")
        }.getOrNull()
        recalled = emptyList()
        status = "Saving photo + memory…"
        if (visioned != null) {
            answer = visioned
            store.rememberLocal(Memory(type = MemoryType.PHOTO, text = visioned), photo, "media")
            status = "Speaking…"; tts.speak(visioned); status = "Look & Ask done"
        } else {
            answer = "Off-grid — I saved the photo and I'll describe it when we're back online."
            store.rememberLocal(
                Memory(type = MemoryType.PHOTO, text = "(photo captured — description pending)", tags = listOf("needs_vision")),
                photo, "media",
            )
            status = "Off-grid — photo saved, description pending"
            tts.speak("Saved. I'll describe it when we're back online.")
        }
    }

    /** Read-only library views (Timeline river + Gallery), refreshed by the screens on entry. */
    var timeline by mutableStateOf<List<Memory>>(emptyList()); private set
    var gallery by mutableStateOf<List<Memory>>(emptyList()); private set

    /** Semantic search results for the Timeline (null = not searching, browse mode). */
    var searchHits by mutableStateOf<List<Memory>?>(null); private set
    var searching by mutableStateOf(false); private set

    fun refreshLibrary() {
        viewModelScope.launch {
            timeline = runCatching { store.recent() }.getOrDefault(emptyList())
            gallery = runCatching { store.mediaMemories() }.getOrDefault(emptyList())
        }
    }

    /** Read-only semantic search over the memory index — never speaks, never writes. */
    fun searchMemories(query: String) {
        if (query.isBlank()) { searchHits = null; return }
        viewModelScope.launch {
            searching = true
            searchHits = runCatching { store.recall(query, limit = 20) }.getOrDefault(emptyList())
            searching = false
        }
    }

    fun clearSearch() { searchHits = null }

    /** Read-only: a short-lived signed URL for a synced photo's stored media (null if not synced). */
    suspend fun signedUrlFor(memory: Memory): String? =
        memory.mediaPath?.let { runCatching { backend.signedMediaUrl(it) }.getOrNull() }

    /** Delete a memory everywhere (cloud row + storage + local file + Room), then refresh views. */
    fun deleteMemory(memory: Memory, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching { store.delete(memory) }
                .onFailure { status = "Delete failed: ${it.message}" }
            refreshLibrary()
            onDone()
        }
    }

    private fun run(busyMsg: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            status = busyMsg
            try {
                block()
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }
}
