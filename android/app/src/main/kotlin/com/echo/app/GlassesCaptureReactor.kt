package com.echo.app

import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import com.echo.device.audio.TtsEngine
import com.echo.device.ble.GlassesBleManager
import com.echo.device.ble.GlassesEvent
import com.echo.device.wifi.GlassesP2pManager
import com.echo.device.wifi.MediaTransferClient
import com.echo.memory.EchoBackend
import com.echo.memory.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The autonomous glasses-capture pipeline, extracted from HomeViewModel so it can run with **no UI**
 * — driven by either the foreground service (app backgrounded/killed) or the ViewModel (foreground).
 * Listens for the glasses' button-press BLE notifications and, on a new capture, runs the
 * sync ceremony (BLE → Wi-Fi Direct → HTTP pull) then enriches each file into a memory
 * (caption / transcribe + save).
 *
 * Single shared collection: [start]/[stop] are refcounted, so the VM and the service can both be
 * "hosts" without ever double-processing an event. The sync ceremony is serialized by a mutex, so
 * an autonomous capture and a manual "Sync from glasses" can't collide.
 */
@Singleton
class GlassesCaptureReactor @Inject constructor(
    private val ble: GlassesBleManager,
    private val p2p: GlassesP2pManager,
    private val transfer: MediaTransferClient,
    private val store: MemoryStore,
    private val backend: EchoBackend,
    private val tts: TtsEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val syncMutex = Mutex()

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    /** The most recent spoken Look & Ask answer (AI gesture) — the UI mirrors this into its card. */
    private val _lastAnswer = MutableStateFlow("")
    val lastAnswer: StateFlow<String> = _lastAnswer

    private var collectJob: Job? = null
    private var hosts = 0

    /** Glasses clear storage after a sync and emit a zero-inventory event — only an INCREASE is new. */
    private var lastInventoryTotal = 0

    /** Set by the AI gesture (double-click BACK): the next synced photo gets full Look & Ask. */
    private var aiAskPending = false

    /** Begin listening for capture events (idempotent + refcounted). */
    @Synchronized
    fun start() {
        hosts++
        if (collectJob == null) {
            // Bring up the GATT link so button-press notifications actually reach us. Without this,
            // the glasses are only connected after a manual sync, so a fresh app never sees a
            // double-click-BACK (Look & Ask) or a capture press. Safe to call repeatedly.
            runCatching { ble.connectGlasses() }
            collectJob = scope.launch {
                ble.notifications.collect { n ->
                    when (val e = GlassesEvent.parse(n.bytes)) {
                        is GlassesEvent.CaptureSaved -> onCaptureSaved(e.photos + e.videos + e.audio)
                        is GlassesEvent.AiGesture -> onAiGesture()
                        else -> {}
                    }
                }
            }
        }
    }

    /** Stop listening when the last host detaches, and drop the BLE link to save battery. */
    @Synchronized
    fun stop() {
        if (--hosts <= 0) {
            hosts = 0
            collectJob?.cancel()
            collectJob = null
            runCatching { ble.disconnectGlasses() }
        }
    }

    private fun onCaptureSaved(total: Int) {
        val prev = lastInventoryTotal
        lastInventoryTotal = total
        if (total == 0 || total <= prev) return // post-sync clear or duplicate echo, not a capture
        if (!backend.isLoggedIn) { _status.value = "Glasses captured — sign in to auto-sync"; return }
        scope.launch {
            syncAndRoute(autoTriggered = true)
            // Glasses delete their files after a successful sync, so the inventory re-baselines to
            // 0; reset our counter to match, or a stale value silently swallows the next capture.
            lastInventoryTotal = 0
        }
    }

    private fun onAiGesture() {
        if (!backend.isLoggedIn) return
        aiAskPending = true
        _status.value = "AI button — capturing…"
        ble.capturePhoto() // its CaptureSaved event then drives the sync + Look & Ask
    }

    /** Manual "Sync from glasses": run the ceremony + enrich, returning the count of new files. */
    suspend fun syncNow(): Int = syncAndRoute(autoTriggered = false)

    /**
     * Voice-controlled "what am I looking at": capture a fresh photo, let the normal capture→sync→
     * vision pipeline run (it speaks the description via [routeNewFile]), and return that description
     * so the conversation can show it. Mirrors the AI-gesture (double-click BACK) but on demand.
     * Returns null on timeout / not-signed-in. Requires [start] to be active (the event collector).
     */
    suspend fun captureAndDescribe(timeoutMs: Long = 30_000): String? {
        if (!backend.isLoggedIn) { android.util.Log.w("EchoVision", "not logged in"); return null }
        val before = _lastAnswer.value
        aiAskPending = true
        _status.value = "Looking…"
        // Ensure the GATT link is up: capturePhoto() returns false when not connected (it only kicks
        // off an async connect), which silently drops the capture. Retry until the command sends.
        runCatching { ble.connectGlasses() }
        var sent = false
        val sendDeadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < sendDeadline) {
            if (ble.capturePhoto()) { sent = true; break }
            kotlinx.coroutines.delay(400)
        }
        android.util.Log.i("EchoVision", "capture command sent=$sent — awaiting CaptureSaved -> sync -> vision")
        if (!sent) { aiAskPending = false; _status.value = "Glasses not reachable"; return null }
        val result = withTimeoutOrNull(timeoutMs) {
            _lastAnswer.first { it != before && it.isNotBlank() }
        }
        android.util.Log.i("EchoVision", if (result == null) "TIMEOUT after ${timeoutMs}ms (capture sent but no sync/vision)" else "got description (${result.length} chars)")
        return result
    }

    /** Import already-downloaded files that lack a memory (orphan reconciliation), silently. */
    suspend fun routeFiles(files: List<File>, speak: Boolean) {
        files.forEach { runCatching { routeNewFile(it, speak) } }
    }

    private suspend fun syncAndRoute(autoTriggered: Boolean): Int = syncMutex.withLock {
        _working.value = true
        try {
            _status.value = if (autoTriggered) "Glasses captured — syncing…" else "Connecting to glasses…"
            val files = doSync()
            if (files.isEmpty()) { _status.value = "Nothing new on the glasses"; return 0 }
            _status.value = "Processing ${files.size} new capture(s)…"
            files.forEach { routeNewFile(it) }
            _status.value = "Done — ${files.size} new capture(s) processed"
            files.size
        } catch (e: Exception) {
            _status.value = "Sync error: ${e.message}"
            0
        } finally {
            _working.value = false
        }
    }

    /** The transfer ceremony: BLE start cmd → Wi-Fi Direct → HTTP pull. Returns the NEW files. */
    private suspend fun doSync(): List<File> {
        ble.connectGlasses()
        p2p.start()
        delay(1500) // let the BLE link come up
        p2p.discoverAndConnect()
        ble.startWifiTransfer() // glasses bring up Wi-Fi; IP arrives via BLE notify
        _status.value = "Waiting for glasses Wi-Fi…"
        val ip = withTimeoutOrNull(30_000) { ble.glassesWifiIp.filterNotNull().first() }
        if (ip == null) {
            _status.value = "Timed out waiting for glasses Wi-Fi"
            p2p.stop()
            return emptyList()
        }
        withTimeoutOrNull(15_000) { p2p.connected.first { it } } // P2P link up before HTTP
        _status.value = "Downloading from $ip…"
        // Bind the pull to the Wi-Fi Direct network, else the socket routes out home Wi-Fi.
        val net = p2p.boundNetwork()
        val files = transfer.pull(ip, net) { i, n -> _status.value = "Downloading $i/$n…" }
        ble.resetP2p()
        p2p.stop()
        return files
    }

    /**
     * Apply the right AI/memory treatment to a synced capture. [speak] is false for silent
     * backfill (reconciling files that were downloaded but never turned into memories).
     */
    private suspend fun routeNewFile(f: File, speak: Boolean = true) {
        when (f.extension.lowercase()) {
            "jpg", "jpeg", "png" -> {
                val ask = aiAskPending; aiAskPending = false
                val visioned = runCatching {
                    _status.value = "Claude is looking at ${f.name}…"
                    backend.describeImage(
                        f.readBytes(),
                        if (ask) "You are JARVIS. The wearer pressed the AI button on their glasses. Say what you see in one or two natural spoken sentences."
                        else "One short sentence: caption this photo for a personal memory index.",
                    )
                }.getOrNull()
                val desc = visioned ?: "(photo captured — description pending)"
                val tags = if (visioned == null) listOf("needs_vision") else emptyList()
                store.rememberLocal(Memory(type = MemoryType.PHOTO, text = desc, tags = tags), f, "media")
                if (ask && visioned != null) {
                    _lastAnswer.value = desc // surface the spoken Look & Ask answer on screen
                    if (speak) { _status.value = "Speaking…"; tts.speak(desc) }
                } else if (speak) tts.speak("Saved")
            }
            "mp4" -> {
                _status.value = "Saving video ${f.name}…"
                store.rememberLocal(Memory(type = MemoryType.PHOTO, text = "(video — ${f.name})", tags = listOf("video")), f, "media")
            }
            "wav", "mp3", "aac", "m4a", "amr", "ogg", "opus" -> {
                _status.value = "Transcribing ${f.name}…"
                val transcript = runCatching {
                    backend.transcribe(f.readBytes(), EchoBackend.mimeFor(f.name))
                }.getOrNull()
                val text = transcript?.takeIf { it.isNotBlank() } ?: "(voice clip — transcript pending)"
                val tags = if (transcript.isNullOrBlank()) listOf("needs_transcribe") else emptyList()
                store.rememberLocal(Memory(type = MemoryType.VOICE_NOTE, text = text, tags = tags), f, "audio")
                if (speak) tts.speak("Noted")
            }
        }
    }
}
