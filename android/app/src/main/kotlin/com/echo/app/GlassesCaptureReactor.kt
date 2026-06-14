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
        // An on-demand voice-vision capture owns the pipeline end-to-end; ignore the CaptureSaved it
        // triggers so we don't double-sync / race it.
        if (voiceVisionInFlight) { android.util.Log.i("EchoVision", "CaptureSaved ignored — voice-vision in flight"); return }
        val prev = lastInventoryTotal
        lastInventoryTotal = total
        android.util.Log.i("EchoVision", "CaptureSaved event total=$total prev=$prev (new=${total > 0 && total > prev})")
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

    /** True while an on-demand voice-vision capture owns the pipeline, so the autonomous collector
     *  ignores the CaptureSaved that OUR OWN capture triggers (no double-processing / no race). */
    @Volatile private var voiceVisionInFlight = false

    /**
     * Voice-controlled "what am I looking at": take a photo NOW and describe THAT photo —
     * deterministically. Owns the whole flow under [syncMutex] (so the autonomous collector can't
     * race it) and:
     *   1. sends the capture (retry until the glasses accept it),
     *   2. waits for the glasses to report a NEW photo saved (skipping the post-sync `photos=0` clear),
     *   3. pulls from the glasses, and
     *   4. describes the SINGLE NEWEST photo (the one just taken, by filename) — never a stale
     *      backlog photo, which was the recurring "it described an old photo" bug.
     * Any other pulled files are drained into memories silently in the background. Returns the spoken
     * description, or null if no fresh photo could be captured (e.g. camera still gated). Bounded by
     * [timeoutMs]. The whole ceremony is time-boxed, so it can never wedge later captures.
     */
    suspend fun captureAndDescribe(timeoutMs: Long = 45_000): String? {
        if (!backend.isLoggedIn) { android.util.Log.w("EchoVision", "not logged in"); return null }
        return syncMutex.withLock {
            voiceVisionInFlight = true
            _working.value = true
            try {
                withTimeoutOrNull(timeoutMs) {
                    val knownBefore = transfer.mediaFiles().map { it.name }.toSet()
                    _status.value = "Looking…"
                    // 1. Send the capture command (capturePhoto only kicks off an async connect when
                    //    the GATT link is down, so retry until the glasses actually accept it).
                    runCatching { ble.connectGlasses() }
                    var sent = false
                    val deadline = System.currentTimeMillis() + 6_000
                    while (System.currentTimeMillis() < deadline) {
                        if (ble.capturePhoto()) { sent = true; break }
                        delay(400)
                    }
                    if (!sent) { android.util.Log.w("EchoVision", "capture not accepted"); _status.value = "Glasses not reachable"; return@withTimeoutOrNull null }
                    // 2. Wait for a NEW photo saved (photos>0 skips the post-clear photos=0 echo), then
                    //    a beat for the file to finish writing. Proceed regardless — the pull is truth.
                    android.util.Log.i("EchoVision", "capture sent — waiting for photo-saved")
                    withTimeoutOrNull(12_000) {
                        ble.notifications.first {
                            (GlassesEvent.parse(it.bytes) as? GlassesEvent.CaptureSaved)?.photos?.let { p -> p > 0 } == true
                        }
                    }
                    delay(800)
                    // 3. Pull (doSync is itself bounded + cleans up Wi-Fi Direct).
                    val files = doSync()
                    // 4. The NEWEST photo by filename is the one we just took (timestamps sort).
                    val newest = files.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
                        .maxByOrNull { it.name }
                    android.util.Log.i("EchoVision", "pulled=${files.size} newest=${newest?.name}")
                    if (newest == null || newest.name in knownBefore) {
                        _status.value = "Couldn't get a fresh photo"
                        drainBacklog(files) // keep anything we did pull
                        return@withTimeoutOrNull null
                    }
                    val desc = describePhoto(newest)
                    drainBacklog(files.filter { it != newest }) // backlog -> memories, off the hot path
                    desc
                }
            } finally {
                voiceVisionInFlight = false
                _working.value = false
                lastInventoryTotal = 0 // glasses delete after a sync; re-baseline the counter
            }
        }
    }

    /** Vision-describe one photo and save it as a memory. Returns the spoken text, or null on failure. */
    private suspend fun describePhoto(f: File): String? {
        _status.value = "Claude is looking…"
        val visioned = runCatching {
            backend.describeImage(
                f.readBytes(),
                "You are JARVIS. The wearer asked what they are looking at. Say what you see in one or two natural spoken sentences.",
            )
        }.getOrNull()
        android.util.Log.i("EchoVision", "describePhoto ${f.name} vision=${if (visioned != null) "ok(${visioned.length})" else "FAILED"}")
        if (visioned == null) {
            store.rememberLocal(Memory(type = MemoryType.PHOTO, text = "(photo captured — description pending)", tags = listOf("needs_vision")), f, "media")
            return null
        }
        store.rememberLocal(Memory(type = MemoryType.PHOTO, text = visioned), f, "media")
        _lastAnswer.value = visioned
        return visioned
    }

    /** Turn already-pulled files into memories silently, off the critical path (never blocks the answer). */
    private fun drainBacklog(files: List<File>) {
        if (files.isEmpty()) return
        scope.launch { files.forEach { f -> withTimeoutOrNull(30_000) { runCatching { routeNewFile(f, speak = false) } } } }
    }

    /** Import already-downloaded files that lack a memory (orphan reconciliation), silently. */
    suspend fun routeFiles(files: List<File>, speak: Boolean) {
        files.forEach { runCatching { routeNewFile(it, speak) } }
    }

    private suspend fun syncAndRoute(autoTriggered: Boolean): Int {
        // Diagnostic: surfaces lock contention — a hung sync used to hold this mutex forever, so
        // every later capture's syncAndRoute blocked here and captureAndDescribe just timed out.
        android.util.Log.i("EchoVision", "syncAndRoute: requesting lock (auto=$autoTriggered, locked=${syncMutex.isLocked})")
        return syncMutex.withLock {
        android.util.Log.i("EchoVision", "syncAndRoute: lock acquired")
        _working.value = true
        try {
            _status.value = if (autoTriggered) "Glasses captured — syncing…" else "Connecting to glasses…"
            // Bound the whole ceremony so a flaky Wi-Fi-Direct step can NEVER hold the mutex forever
            // (that's what wedged every subsequent capture). On timeout it returns empty + releases.
            val files = withTimeoutOrNull(75_000) { doSync() } ?: run {
                android.util.Log.w("EchoVision", "syncAndRoute: doSync timed out — releasing lock")
                emptyList()
            }
            if (files.isEmpty()) { _status.value = "Nothing new on the glasses"; return@withLock 0 }
            _status.value = "Processing ${files.size} new capture(s)…"
            // Bound EACH file: a single stuck vision/upload call (common on a stale backlog file) used
            // to hang the whole loop while holding syncMutex, wedging every later capture. Skip a slow
            // one instead — the lock is then always released promptly.
            files.forEach { f ->
                withTimeoutOrNull(45_000) { routeNewFile(f) }
                    ?: android.util.Log.w("EchoVision", "routeNewFile timed out, skipping ${f.name}")
            }
            _status.value = "Done — ${files.size} new capture(s) processed"
            files.size
        } catch (e: Exception) {
            _status.value = "Sync error: ${e.message}"
            0
        } finally {
            _working.value = false
        }
        } // syncMutex.withLock
    }

    /** The transfer ceremony: BLE start cmd → Wi-Fi Direct → HTTP pull. Returns the NEW files. */
    private suspend fun doSync(): List<File> = try {
        android.util.Log.i("EchoVision", "doSync: connecting BLE + starting Wi-Fi Direct")
        ble.connectGlasses()
        p2p.start()
        delay(1500) // let the BLE link come up
        p2p.discoverAndConnect()
        ble.startWifiTransfer() // glasses bring up Wi-Fi; IP arrives via BLE notify
        _status.value = "Waiting for glasses Wi-Fi…"
        val ip = withTimeoutOrNull(20_000) { ble.glassesWifiIp.filterNotNull().first() }
        android.util.Log.i("EchoVision", "doSync: glasses Wi-Fi ip=$ip")
        if (ip == null) {
            _status.value = "Timed out waiting for glasses Wi-Fi"
            emptyList()
        } else {
            val p2pUp = withTimeoutOrNull(15_000) { p2p.connected.first { it } } // P2P link up before HTTP
            _status.value = "Downloading from $ip…"
            // Bind the pull to the Wi-Fi Direct network, else the socket routes out home Wi-Fi.
            val net = p2p.boundNetwork()
            val files = transfer.pull(ip, net) { i, n -> _status.value = "Downloading $i/$n…" }
            android.util.Log.i("EchoVision", "doSync: p2pConnected=$p2pUp pulled=${files.size} files: ${files.map { it.name }}")
            files
        }
    } finally {
        // Always tear down Wi-Fi Direct — even if cancelled by the outer timeout — so a flaky sync
        // never leaves p2p engaged (which would wedge the next capture).
        runCatching { ble.resetP2p() }
        runCatching { p2p.stop() }
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
                android.util.Log.i("EchoVision", "routeNewFile photo=${f.name} ask=$ask vision=${if (visioned != null) "ok(${visioned.length})" else "FAILED"}")
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
