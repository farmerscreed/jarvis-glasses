package com.echo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import com.echo.device.audio.BtAudioEngine
import com.echo.device.audio.TtsEngine
import com.echo.device.audio.WakeWordEngine
import com.echo.device.audio.WavUtil
import com.echo.device.ble.GlassesBleManager
import com.echo.device.ble.GlassesEvent
import com.echo.device.wifi.GlassesP2pManager
import com.echo.device.wifi.MediaTransferClient
import java.io.File
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
) : ViewModel() {

    var loggedIn by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Not signed in"); private set

    var memoryText by mutableStateOf("I parked on level 3 near the blue elevator.")
    var question by mutableStateOf("what did Sam say about the budget?")
    var answer by mutableStateOf(""); private set
    var recalled by mutableStateOf<List<Memory>>(emptyList()); private set

    var audioStatus by mutableStateOf("Tap to test the glasses mic + speaker"); private set
    var bleStatus by mutableStateOf("BLE idle"); private set
    var syncStatus by mutableStateOf("Pull captured media off the glasses"); private set
    var handsFree by mutableStateOf(false); private set

    /** Offline-first surface: live online state, tier, + count of memories not yet pushed. */
    var online by mutableStateOf(true); private set
    var tier by mutableStateOf("full"); private set
    var pendingSync by mutableStateOf(0); private set

    /** Set by the AI gesture (double-click BACK): the next synced photo gets full Look & Ask. */
    private var aiAskPending = false

    /** A capture event arrived while we were busy — sync as soon as the current job ends. */
    private var pendingAutoSync = false

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
        // Phase B: react to glasses button presses (firmware captures; we listen + enrich).
        viewModelScope.launch {
            ble.notifications.collect { n ->
                when (val e = GlassesEvent.parse(n.bytes)) {
                    is GlassesEvent.CaptureSaved -> onCaptureSaved(e.photos + e.videos + e.audio)
                    is GlassesEvent.AiGesture -> onAiGesture()
                    else -> {}
                }
            }
        }
    }

    /** Last known total file count on the glasses. They clear storage after a successful sync
     *  and emit a zero-inventory event — only an INCREASE means a new capture. */
    private var lastInventoryTotal = 0

    /** The glasses just saved a capture (photo / audio / video) — pull it and enrich it. */
    private fun onCaptureSaved(total: Int) {
        val prev = lastInventoryTotal
        lastInventoryTotal = total
        if (total == 0 || total <= prev) return // post-sync clear or duplicate echo, not a capture
        if (!loggedIn) { status = "Glasses captured — sign in to auto-sync"; return }
        if (busy) { pendingAutoSync = true; return }
        startAutoSync()
    }

    private fun startAutoSync() = run("Glasses captured — syncing…") {
        val files = doSync()
        if (files.isEmpty()) { status = "Nothing new on the glasses"; return@run }
        files.forEach { routeNewFile(it) }
        status = "Done — ${files.size} new capture(s) processed"
    }

    /** Double-click BACK: capture a frame ourselves, then Look & Ask on it when it syncs. */
    private fun onAiGesture() {
        if (busy || !loggedIn) return
        aiAskPending = true
        status = "AI button — capturing…"
        ble.capturePhoto() // its CaptureSaved event then drives the sync + Look & Ask
    }

    /** Apply the right AI/memory treatment to a freshly synced capture. */
    private suspend fun routeNewFile(f: File) {
        when (f.extension.lowercase()) {
            "jpg", "jpeg", "png" -> {
                val ask = aiAskPending; aiAskPending = false
                // Vision needs the cloud; if it fails (offline), still save the photo locally with a
                // placeholder so it's never lost — a later increment re-describes "needs_vision" rows.
                val visioned = runCatching {
                    status = "Claude is looking at ${f.name}…"
                    backend.describeImage(
                        f.readBytes(),
                        if (ask) "You are JARVIS. The wearer pressed the AI button on their glasses. Say what you see in one or two natural spoken sentences."
                        else "One short sentence: caption this photo for a personal memory index.",
                    )
                }.getOrNull()
                val desc = visioned ?: "(photo captured — description pending)"
                val tags = if (visioned == null) listOf("needs_vision") else emptyList()
                store.rememberLocal(Memory(type = MemoryType.PHOTO, text = desc, tags = tags), f, "media")
                if (ask && visioned != null) { answer = desc; status = "Speaking…"; tts.speak(desc) }
                else tts.speak("Saved")
            }
            "mp4" -> { // V2: preserve video — keep the file as a memory; uploads on drain
                status = "Saving video ${f.name}…"
                store.rememberLocal(Memory(type = MemoryType.PHOTO, text = "(video — ${f.name})", tags = listOf("video")), f, "media")
            }
            "wav", "mp3", "aac", "m4a", "amr", "ogg", "opus" -> { // glasses record .opus
                status = "Transcribing ${f.name}…"
                val transcript = runCatching {
                    backend.transcribe(f.readBytes(), com.echo.memory.EchoBackend.mimeFor(f.name))
                }.getOrNull()
                val text = transcript?.takeIf { it.isNotBlank() } ?: "(voice clip — transcript pending)"
                val tags = if (transcript.isNullOrBlank()) listOf("needs_transcribe") else emptyList()
                store.rememberLocal(Memory(type = MemoryType.VOICE_NOTE, text = text, tags = tags), f, "audio")
                tts.speak("Noted")
            }
        }
    }

    private fun onGlassesButton() {
        if (busy) return
        if (!loggedIn) { status = "Glasses button pressed — sign in first"; return }
        run("Glasses button — listening…") { doTalk() }
    }

    fun signIn() = run("Signing in…") {
        backend.signIn("tester@local.dev", "password123")
        loggedIn = true
        status = "Signed in as tester@local.dev"
    }

    fun remember() = run("Saving memory…") {
        store.remember(Memory(type = MemoryType.NOTE, text = memoryText))
        status = if (online) "Remembered" else "Saved on phone — will sync when back online"
    }

    fun ask() = run("Thinking…") {
        if (online) {
            // Adaptive timeout (§4.4): on a slow/LEAN link, fail fast to the on-device answer
            // rather than hanging on Claude.
            val result = withTimeoutOrNull(12_000) { backend.chat(question) }
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
        val rec = audio.record(5000)
        // Off-grid: cloud STT (Gemini) is unavailable and on-device dictation isn't wired yet, so
        // fall back to the text path (modality fallback §4.5) rather than failing silently.
        if (!online) {
            val msg = "I can't transcribe speech while we're off-grid yet. Type your question and " +
                "I'll answer from your memories."
            answer = msg; status = "Off-grid — type to ask"
            tts.speak("I can't hear you in detail off-grid yet. Type your question.")
            return
        }
        status = "Transcribing…"
        val heard = runCatching { backend.transcribe(WavUtil.pcm16ToWav(rec.pcm, rec.sampleRate)) }.getOrDefault("")
        question = heard.ifBlank { "(didn't catch that)" }
        if (heard.isNotBlank()) {
            status = "Thinking…"
            val result = backend.chat(heard)
            answer = result.answer
            recalled = result.memoriesUsed
            status = "Speaking…"
            tts.speak(result.answer)
            status = "Done"
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
        super.onCleared()
    }

    /** Phase 0D: discover the glasses' GATT (resolve the camera endpoint / handle 0x008E). */
    fun runBleDiagnostic() = ble.runDiagnostic()

    /** Phase 0D: write the reverse-engineered capture command to the resolved characteristic. */
    fun capturePhoto() = ble.capturePhoto()

    /** Phase 2: pull the glasses' captured media into the app (BLE start → Wi-Fi Direct → HTTP /files/). */
    fun syncGlasses() = run("Connecting to glasses…") {
        doSync()
        status = "Sync done"
    }

    /** The transfer ceremony: BLE start cmd → Wi-Fi Direct → HTTP pull. Returns the NEW files. */
    private suspend fun doSync(): List<File> {
        ble.connectGlasses()
        p2p.start()
        delay(1500) // let the BLE link come up
        p2p.discoverAndConnect()
        ble.startWifiTransfer() // glasses bring up Wi-Fi; IP arrives via BLE notify
        syncStatus = "Waiting for glasses Wi-Fi…"
        val ip = withTimeoutOrNull(30_000) { ble.glassesWifiIp.filterNotNull().first() }
        if (ip == null) {
            syncStatus = "Timed out waiting for glasses Wi-Fi IP"
            status = "Sync failed"
            p2p.stop(); return emptyList()
        }
        // ensure the P2P link is up before HTTP
        withTimeoutOrNull(15_000) { p2p.connected.first { it } }
        syncStatus = "Downloading from $ip…"
        val files = transfer.pull(ip) { i, n -> syncStatus = "Downloading $i/$n…" }
        syncStatus = "Synced ${files.size} new file(s) from the glasses"
        ble.resetP2p()
        p2p.stop()
        return files
    }

    /** Look & Ask: send the most recently synced photo to Claude vision, speak + remember it. */
    fun lookAndAsk() = run("Looking…") {
        if (!loggedIn) { status = "Sign in first"; return@run }
        val photo = transfer.latestPhoto()
        if (photo == null) { status = "No synced photo — Sync from glasses first"; return@run }
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
                // A glasses capture landed while we were busy — catch up now (dedup makes this safe).
                if (pendingAutoSync) { pendingAutoSync = false; if (loggedIn) startAutoSync() }
            }
        }
    }
}
