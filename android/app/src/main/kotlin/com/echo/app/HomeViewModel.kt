package com.echo.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import com.echo.device.audio.BtAudioEngine
import com.echo.device.audio.Recording
import com.echo.device.audio.SherpaStt
import com.echo.device.audio.TtsEngine
import com.echo.device.audio.WakeWordEngine
import com.echo.device.audio.WavUtil
import com.echo.device.ble.GlassesBleManager
import com.echo.device.ble.GlassesEvent
import com.echo.device.wifi.GlassesP2pManager
import com.echo.device.wifi.MediaTransferClient
import java.io.File
import com.echo.memory.AgentBridge
import com.echo.memory.ChatMsg
import com.echo.memory.ChatResult
import com.echo.memory.ConnectivityGovernor
import com.echo.memory.EchoBackend
import com.echo.memory.MemoryStore
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** One line in the on-screen conversation transcript. [fromUser] = you, else JARVIS. */
data class TurnLine(val fromUser: Boolean, val text: String)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backend: EchoBackend,
    private val store: MemoryStore,
    private val governor: ConnectivityGovernor,
    private val audio: BtAudioEngine,
    private val ble: GlassesBleManager,
    private val tts: TtsEngine,
    private val sherpa: SherpaStt,
    private val wake: WakeWordEngine,
    private val buttons: GlassesButtonController,
    private val p2p: GlassesP2pManager,
    private val transfer: MediaTransferClient,
    private val reactor: GlassesCaptureReactor,
    private val agent: AgentBridge,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    var loggedIn by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Not signed in"); private set

    /** True while a continuous conversation is in progress (so the orb tap can toggle it off). */
    var inConversation by mutableStateOf(false); private set
    @Volatile private var stopConversation = false

    // Barge-in holds SCO open so the answer plays over the SCO link while the mic monitors for the
    // user cutting in. Re-enabled now that STT is ON-DEVICE (the earlier "couldn't hear" was really
    // the Gemini cloud quota, not the SCO echo). awaitBargeIn() thresholds are echo-aware and tuned
    // to the user's ~1700 voice RMS; tune further from the EchoBarge logs after testing.
    private val bargeInEnabled = true

    /** Running transcript of the current conversation for the scrollable on-screen chat. */
    val transcript = mutableStateListOf<TurnLine>()

    /** On-device STT model state for the UI: "" when ready/absent-but-idle, or a download/loading note. */
    var sttModelStatus by mutableStateOf(""); private set
    @Volatile private var sttModelTriggered = false

    // v1.5 editable profile (Settings → "JARVIS's memory"): SOUL (character) + curated facts.
    var profileSoul by mutableStateOf(""); private set
    var profileFacts by mutableStateOf(""); private set
    var profileBusy by mutableStateOf(false); private set

    fun loadProfile() = viewModelScope.launch {
        profileBusy = true
        runCatching { backend.getProfile() }.getOrNull()?.let { profileSoul = it.soul; profileFacts = it.user_facts }
        profileBusy = false
    }

    fun saveProfile(soul: String, facts: String) = viewModelScope.launch {
        profileBusy = true
        runCatching { backend.setProfile(soul = soul, userFacts = facts) }
            .getOrNull()?.let { profileSoul = it.soul; profileFacts = it.user_facts; status = "Memory saved" }
        profileBusy = false
    }

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

    /** Privacy: true whenever the mic is actively capturing (a voice turn or wake-word listening). */
    var micActive by mutableStateOf(false); private set

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
        // On-device STT: download the Whisper model on first run (needs network once), then it's the
        // primary, offline, no-quota transcriber. Fires once when we're online.
        viewModelScope.launch { governor.online.collect { if (it) ensureSttModel() } }
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
        withRecordingConsent { run("Glasses button — listening…") { doTalk() } }
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

    /** GDPR export: write all memories to a JSON file and open the system share sheet. */
    fun exportData(context: Context) = run("Preparing your data…") {
        val jsonText = store.exportJson()
        val dir = java.io.File(context.cacheDir, "export").apply { mkdirs() }
        val file = java.io.File(dir, "jarvis-memories.json").apply { writeText(jsonText) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(share, "Export your JARVIS data").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        status = "Exported your memories"
    }

    /** GDPR "delete everything": wipe all memories locally + in the cloud, then sign out. */
    fun deleteEverything(onDone: () -> Unit = {}) = run("Deleting everything…") {
        store.deleteAll()
        backend.signOut()
        loggedIn = false
        timeline = emptyList(); gallery = emptyList(); recalled = emptyList(); answer = ""
        status = "All your data was deleted"
        onDone()
    }

    /**
     * D2 streaming turn: speak each sentence as Claude generates it instead of waiting for the
     * full answer. Returns null when the stream fails (caller falls back to non-streaming [EchoBackend.chat]).
     */
    private suspend fun streamedChat(
        message: String,
        history: List<ChatMsg> = emptyList(),
        onFirstSentence: () -> Unit = {},
    ): ChatResult? {
        if (!tts.beginStream()) return null
        var spoke = false
        val chunker = SentenceChunker { s ->
            if (!spoke) { spoke = true; onFirstSentence(); status = "Speaking…" }
            tts.enqueue(s)
        }
        val result = runCatching {
            backend.chatStream(message, history) { chunker.add(it) }.also { chunker.flush() }
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
        micActive = true
        val rec = try { audio.record(4000) } finally { micActive = false }
        audioStatus = "Recorded %.1fs · peak %d · rms %d".format(rec.seconds, rec.peak, rec.rms)
        status = "Playing back through the glasses…"
        delay(600)
        audio.play(rec.pcm, rec.sampleRate)
        audioStatus += " · played back"
        status = "Audio loop done"
    }

    /** Privacy: gate the first mic capture behind one-time consent (audio → cloud transcription). */
    var showRecordingConsent by mutableStateOf(false); private set
    private var pendingVoiceAction: (() -> Unit)? = null

    private fun withRecordingConsent(action: () -> Unit) {
        if (ConsentPrefs.isGranted(appContext)) action()
        else { pendingVoiceAction = action; showRecordingConsent = true }
    }

    fun grantRecordingConsent() {
        ConsentPrefs.grant(appContext)
        showRecordingConsent = false
        pendingVoiceAction?.invoke(); pendingVoiceAction = null
    }

    fun dismissRecordingConsent() {
        showRecordingConsent = false
        pendingVoiceAction = null
        if (handsFree) { handsFree = false; micActive = false; wake.stop() } // user declined; don't listen
    }

    /**
     * The flagship voice loop: glasses mic -> STT -> Claude (RAG) -> TTS in your ear.
     * The orb starts a **conversation** (keeps listening for follow-ups until you're done).
     */
    fun talk() {
        if (inConversation) { endConversation(); return } // tap again ends it
        withRecordingConsent { run("Listening — speak into the glasses…") { converse(continuous = true) } }
    }

    /**
     * Ensure the on-device STT model is downloaded (first run, ~103 MB) and loaded. Idempotent and
     * safe to call repeatedly; reports progress via [sttModelStatus] for the UI. Falls back silently
     * to cloud STT until ready.
     */
    fun ensureSttModel() {
        if (sherpa.isReady || sttModelTriggered) {
            if (sherpa.isDownloaded && !sherpa.isReady) viewModelScope.launch { sherpa.ensureLoaded() }
            return
        }
        sttModelTriggered = true
        viewModelScope.launch {
            if (sherpa.isDownloaded) {
                sttModelStatus = "Loading offline voice…"
                sttModelStatus = if (sherpa.ensureLoaded()) "" else "Offline voice failed to load"
                return@launch
            }
            if (!online) { sttModelTriggered = false; return@launch } // retry when back online
            sttModelStatus = "Downloading offline voice model…"
            val ok = sherpa.download { p -> sttModelStatus = "Downloading offline voice model… ${(p * 100).toInt()}%" }
            sttModelStatus = if (ok && sherpa.ensureLoaded()) "" else { sttModelTriggered = false; "Offline voice download failed" }
        }
    }

    /** Explicit End (orb re-tap or the End button): stop the conversation now — cut any current
     *  speech and abort the open mic within a frame, rather than waiting out the turn. */
    fun endConversation() {
        if (!inConversation) return
        stopConversation = true // recordUntilSilence(shouldAbort) bails; the loop breaks
        tts.stop()              // cut any answer that's mid-sentence
        status = "Ending…"
    }

    /** Single message (e.g. the glasses button): one turn, no follow-up listening. */
    private suspend fun doTalk() = converse(continuous = false)

    /**
     * One spoken turn, optionally continued. When [continuous], after answering it re-opens the mic
     * for a follow-up — threading the last few turns as context — and keeps going until the user goes
     * quiet (one "still there?" reprompt, then a soft close) or says a closing phrase. The backend
     * already persists every Q&A into the memory index, so conversations are remembered.
     */
    private suspend fun converse(continuous: Boolean) {
        // Off-grid: cloud STT (Gemini) is unavailable and on-device dictation isn't wired yet, so
        // fall back to the text path (modality fallback §4.5) rather than recording pointlessly.
        if (!online) {
            answer = "I can't transcribe speech while we're off-grid yet. Type your question and " +
                "I'll answer from your memories."
            status = "Off-grid — type to ask"
            tts.speak("I can't hear you in detail off-grid yet. Type your question.")
            return
        }
        if (continuous) { inConversation = true; stopConversation = false }
        transcript.clear() // fresh on-screen transcript for this conversation
        try {
        if (continuous && bargeInEnabled) {
            // Hold SCO open for the whole conversation: the answer plays over the SCO output and the
            // mic stays available for barge-in (A2DP is suspended while SCO is up). Narrowband but
            // intelligible — the price of being able to interrupt. (Disabled — see bargeInEnabled.)
            audio.beginScoSession()
            tts.useCommunicationRoute(true)
        }
        val history = ArrayDeque<ChatMsg>() // last few turns, threaded for context
        var repromptUsed = false
        var bargedIn = false // set when the user interrupted the previous answer (skip the next cue)
        while (true) {
            if (stopConversation) { status = "Conversation ended"; break } // orb tapped again
            val t0 = System.currentTimeMillis()
            // The audible "listening" cue plays inside recordUntilSilence the moment the mic is hot —
            // but skip it right after a barge-in, when the user is already mid-sentence.
            micActive = true
            val rec = try {
                audio.recordUntilSilence(playCue = !bargedIn, shouldAbort = { stopConversation })
            } finally { micActive = handsFree }
            bargedIn = false
            if (stopConversation) { status = "Conversation ended"; break } // End pressed during capture
            val tRecord = System.currentTimeMillis() - t0
            // Skip the cloud STT call entirely when the (reliable) VAD heard no speech or the level is
            // near-silent. This is what lets the conversation END on silence, AND it stops wasting STT
            // calls on empty turns (those silent calls are what exhaust the Gemini free-tier quota).
            val noSpeech = !rec.speechStarted || rec.peak < 900 || rec.rms < 70
            status = "Transcribing…"
            val sttStart = System.currentTimeMillis()
            val wav = WavUtil.pcm16ToWav(rec.pcm, rec.sampleRate)
            // On-device STT first (offline, fast, no quota); cloud only as a fallback when the model
            // isn't downloaded/loaded yet or comes back empty on real speech.
            val onDevice = if (!noSpeech && sherpa.isReady) sherpa.transcribe(rec.pcm, rec.sampleRate) else ""
            val sttResult = when {
                noSpeech -> Result.success("")
                onDevice.isNotBlank() -> Result.success(onDevice)
                else -> runCatching { backend.transcribe(wav) }
            }
            val sttSource = if (onDevice.isNotBlank()) "device" else if (noSpeech) "skip" else "cloud"
            val tStt = System.currentTimeMillis() - sttStart
            android.util.Log.i("EchoLatency", "stt-source=$sttSource stt=${tStt}ms")
            dumpVoiceDebug(wav, rec, sttResult.getOrElse { "(stt error: ${it.message})" }, tRecord, tStt)
            // Surface a real STT failure (e.g. Gemini quota / 429 / service down) instead of silently
            // showing "didn't catch that" — otherwise an outage looks like the mic going deaf.
            val sttError = sttResult.exceptionOrNull()
            if (sttError != null) {
                val m = sttError.message ?: ""
                val overQuota = "429" in m || "quota" in m.lowercase() || "RESOURCE_EXHAUSTED" in m
                status = if (overQuota) "Speech service is over its limit — try again later"
                else "Speech service unavailable — try again"
                tts.speak(
                    if (overQuota) "The speech service has reached its daily limit. Please try again later."
                    else "The speech service is unavailable right now. Please try again.",
                )
                break // don't loop forever against an outage
            }
            val heard = sttResult.getOrDefault("")

            if (heard.isBlank()) {
                question = "(didn't catch that)"
                if (continuous && !repromptUsed) {
                    repromptUsed = true
                    status = "Still there?"
                    tts.speak("Still there?")
                    continue // one more listen before ending the conversation
                }
                status = if (continuous) "Conversation ended" else "Didn't catch that — try again"
                tts.speak(if (continuous) "Okay, I'm here if you need me." else "Sorry, I didn't catch that.")
                break
            }
            repromptUsed = false
            question = heard
            transcript.add(TurnLine(fromUser = true, text = heard))

            // v1.3 explicit memory: "remember that…" pins a fact immediately (no LLM round-trip).
            if (isRememberCommand(heard)) {
                backend.remember(heard)
                answer = "Noted."
                transcript.add(TurnLine(fromUser = false, text = "Noted."))
                tts.speak("Noted.")
                if (!continuous) { status = "Noted"; break }
                status = "Listening…"
                continue
            }

            // Voice-controlled glasses: "what am I looking at" / "take a photo of this" → capture a
            // photo through the glasses and describe it aloud (the reactor speaks the description).
            if (isVisionCommand(heard)) {
                status = "Looking…"
                // The glasses gate the camera while their BT audio (SCO/A2DP) is ACTIVE (recon: a
                // capture during a call/stream is ACK'd but takes no photo). So: cue the user FIRST
                // over the current route, kill TTS so it releases the audio session, then FULLY quiet
                // the BT audio and let the A2DP stream suspend before capturing. Restore the
                // conversation audio afterwards.
                val hadHeldSco = continuous && bargeInEnabled
                tts.speak("Let me look.")
                tts.stop() // release the TTS output stream so A2DP can go idle
                if (hadHeldSco) tts.useCommunicationRoute(false)
                audio.releaseForCamera(settleMs = 3500L) // tears down SCO + settles for A2DP suspend
                val desc = reactor.captureAndDescribe()
                if (hadHeldSco) { tts.useCommunicationRoute(true); audio.beginScoSession() }
                val ans = desc
                    ?: "I couldn't get a photo just now — the camera can't run while we're on a voice call. " +
                    "Try the glasses button to snap one, then ask me about it."
                answer = ans
                transcript.add(TurnLine(fromUser = false, text = ans))
                tts.speak(ans) // captureAndDescribe returns the text now; speak it here (success or not)
                if (!continuous) { status = "Done"; break }
                status = "Listening…"
                continue
            }

            // M2 — commit (gated): confirm by voice, then one local commit, never a push.
            if (agent.isConfigured && isCommitCommand(heard)) {
                if (awaitConfirmation("I'll commit the current changes locally, without pushing. Go ahead?")) {
                    status = "Committing…"
                    val res = agent.commitChanges()
                    answer = res.text; transcript.add(TurnLine(false, res.text)); tts.speak(spokenPart(res.text))
                    status = if (res.ok) "Committed" else "Commit failed"
                } else { tts.speak("Okay, I won't commit."); status = "Cancelled" }
                if (!continuous) break
                status = "Listening…"; continue
            }

            // M3 — add to calendar (gated): confirm by voice, then create the event (never edits/deletes).
            if (agent.isConfigured && isCalendarAdd(heard)) {
                if (awaitConfirmation("I'll add that to your calendar. Shall I go ahead?")) {
                    status = "Adding to your calendar…"
                    val res = agent.calendarAdd(heard)
                    answer = res.text; transcript.add(TurnLine(false, res.text)); tts.speak(spokenPart(res.text))
                    status = if (res.ok) "Added to your calendar" else "Couldn't add it"
                } else { tts.speak("Okay, I won't add it."); status = "Cancelled" }
                if (!continuous) break
                status = "Listening…"; continue
            }

            // M3 — read the calendar (no side effects, no confirm).
            if (agent.isConfigured && isCalendarQuery(heard)) {
                status = "Checking your calendar…"
                val res = agent.calendarQuery(heard)
                answer = res.text; transcript.add(TurnLine(false, res.text)); tts.speak(spokenPart(res.text))
                status = if (res.ok) "Checked your calendar" else "Couldn't check your calendar"
                if (!continuous) break
                status = "Listening…"; continue
            }

            // M3 — draft an email (DRAFT ONLY; the Gmail MCP has no send tool, so this is safe).
            if (agent.isConfigured && isEmailCommand(heard)) {
                status = "Drafting your email…"
                tts.speak("I'll draft that for you.")
                val res = agent.emailDraft(heard)
                answer = res.text; transcript.add(TurnLine(false, res.text)); tts.speak(spokenPart(res.text))
                status = if (res.ok) "Draft saved to Gmail" else "Couldn't draft it"
                if (!continuous) break
                status = "Listening…"; continue
            }

            // M1 — research (checked after calendar/email so "look up my calendar" routes to calendar):
            // delegate to Claude Code, speak a sourced summary, and save the full text to the memory index.
            if (agent.isConfigured && isResearchCommand(heard)) {
                val topic = researchTopic(heard)
                status = "Researching…"
                tts.speak("On it — researching that now. This might take a moment.")
                val res = agent.research(topic)
                // The preset answers in a spoken style then lists "Sources:" — speak the spoken part,
                // but keep the full text (with sources) on screen and in memory.
                answer = res.text
                transcript.add(TurnLine(fromUser = false, text = res.text))
                status = if (res.ok) "Researched · ${res.durationMs / 1000}s" else "Research failed"
                tts.speak(spokenPart(res.text))
                // Persist successful research into the memory index (tag "research") for later viewing.
                if (res.ok) runCatching {
                    store.remember(
                        Memory(type = MemoryType.NOTE, text = "Research — $topic:\n${res.text}", tags = listOf("research")),
                    )
                }
                if (!continuous) { status = "Done"; break }
                status = "Listening…"
                continue
            }

            // M2 — coding (broadest agent intent → checked last). Edits the repo for review; no commit.
            if (agent.isConfigured && isCodingCommand(heard)) {
                status = "Working on the code…"
                tts.speak("Okay, I'll work on that in the code now. This may take a little while; I'll leave the changes for you to review and won't commit anything.")
                val res = agent.coding(heard)
                answer = res.text; transcript.add(TurnLine(false, res.text)); tts.speak(spokenPart(res.text))
                status = if (res.ok) "Code changes ready to review" else "Coding didn't complete"
                if (!continuous) break
                status = "Listening…"; continue
            }

            if (continuous && isClosing(heard)) {
                status = "Conversation ended"
                tts.speak("Talk soon.")
                break
            }

            status = "Thinking…"
            val llmStart = System.currentTimeMillis()
            val histList = history.toList()
            // D2: stream — first sentence is spoken while Claude writes the rest.
            var toSpeak = 0L
            var barged = false
            var speaking = true
            // Speak the answer; in a conversation, concurrently watch for the user to barge in and,
            // if they do, cut the answer short and go capture what they're saying.
            coroutineScope {
                val monitor = if (continuous && bargeInEnabled) launch {
                    if (runCatching { audio.awaitBargeIn(active = { speaking }) }.getOrDefault(false)) {
                        barged = true
                        tts.stop() // cut the answer; unblocks the speak below
                    }
                } else null
                val streamed = if (tier == "full") {
                    streamedChat(heard, histList) { toSpeak = System.currentTimeMillis() - t0 }
                } else null
                val result = streamed ?: backend.chat(heard, histList)
                if (streamed == null) {
                    toSpeak = System.currentTimeMillis() - t0
                    status = "Speaking…"
                    tts.speak(result.answer)
                }
                speaking = false
                monitor?.cancel()
                answer = result.answer
                recalled = result.memoriesUsed
                transcript.add(TurnLine(fromUser = false, text = result.answer))
                // Thread the last 3–6 turns (cap 12 messages = 6 turns) for follow-up context.
                history.addLast(ChatMsg("user", heard))
                history.addLast(ChatMsg("assistant", result.answer))
                while (history.size > 12) history.removeFirst()
            }
            val tLlm = System.currentTimeMillis() - llmStart
            bargedIn = barged
            lastLatencyMs = if (toSpeak > 0) toSpeak else System.currentTimeMillis() - t0
            android.util.Log.i(
                "EchoLatency",
                "record=${tRecord}ms (%.1fs audio) stt=${tStt}ms llm=${tLlm}ms barged=$barged convo=$continuous time-to-speak=${toSpeak}ms".format(rec.seconds),
            )
            if (barged) { status = "Listening…"; continue } // user cut in — capture their interruption

            if (!continuous) { status = "Done · ${toSpeak}ms to first word"; break }
            status = "Listening…" // loop back and re-open the mic for the follow-up
        }
        } finally {
            if (continuous) {
                inConversation = false
                if (bargeInEnabled) {
                    audio.endScoSession()       // release the held SCO link
                    tts.useCommunicationRoute(false) // back to the hi-fi media route
                }
            }
            // Distillation (assistant memory v1.2): after a real conversation, extract durable facts
            // into the profile. Fire-and-forget; snapshot the transcript so a new convo can't race it.
            if (continuous && online && transcript.size >= 2) {
                val convo = transcript.map { ChatMsg(if (it.fromUser) "user" else "assistant", it.text) }
                viewModelScope.launch { backend.distill(convo) }
            }
        }
    }

    /**
     * Short closing phrases that end a conversation. Deliberately conservative — only brief
     * utterances match, so a real question that happens to contain "stop"/"bye" doesn't hang up.
     */
    // Explicit "remember that/to/this…" memory command (requires that/to/this so a question like
    // "remember when we…" doesn't trip it). The backend strips the prefix and pins the fact.
    private val rememberRe = Regex(
        "^\\s*(please\\s+)?(jarvis[,!.]?\\s+)?(remember|note|don'?t\\s+forget|make\\s+a\\s+note)\\s+(that|to|this|:)",
        RegexOption.IGNORE_CASE,
    )
    private fun isRememberCommand(s: String) = rememberRe.containsMatchIn(s)

    // Voice-controlled glasses (v2.1 first skill): commands that mean "use the camera + tell me".
    private val visionRe = Regex(
        "(what am i looking at|what'?s (this|that|in front)|what do you see|look at (this|that)|" +
            "take a (photo|picture|pic|snap|shot)|describe (this|that)|can you see (this|that)|" +
            "what is (this|that)|read (this|that)|what am i (holding|seeing))",
        RegexOption.IGNORE_CASE,
    )
    private fun isVisionCommand(s: String) = visionRe.containsMatchIn(s)

    // M1 research — any "research / look into / find out / look up / search for …" anywhere (natural
    // speech: "I needed to research on X", "can you find out the price of Y"). Checked AFTER calendar/
    // email (below) so "look up my calendar" routes to calendar, not a web search.
    private val researchRe = Regex(
        "\\bresearch\\b|\\b(look into|look up|find out|find me|search (for|up)|dig (up|into)|investigate|google)\\b",
        RegexOption.IGNORE_CASE,
    )
    private fun isResearchCommand(s: String) = researchRe.containsMatchIn(s)
    /** Topic to research: the whole (lightly cleaned) utterance — the research agent extracts the gist. */
    private fun researchTopic(s: String): String =
        s.trim().replace(Regex("^\\s*(hey\\s+)?jarvis[,!.]?\\s+", RegexOption.IGNORE_CASE), "").trim().ifBlank { s.trim() }

    // M2 coding — broad verb + an explicit code-y noun (kept specific so normal chat doesn't edit files).
    private val codingRe = Regex(
        "^\\s*(please\\s+)?(jarvis[,!.]?\\s+)?" +
            "(write|fix|implement|refactor|debug|add|create|update|change|rename|remove|delete|build|make)\\b.*" +
            "\\b(code|coding|bug|function|feature|test|tests|file|files|class|method|script|module|component|" +
            "endpoint|api|typo|compile|build|repo|repository|variable|import)\\b",
        RegexOption.IGNORE_CASE,
    )
    private fun isCodingCommand(s: String) = codingRe.containsMatchIn(s)

    // M2 commit — explicit "commit…" (the actual commit is gated behind a spoken confirm).
    private val commitRe = Regex("^\\s*(please\\s+)?(jarvis[,!.]?\\s+)?commit\\b", RegexOption.IGNORE_CASE)
    private fun isCommitCommand(s: String) = commitRe.containsMatchIn(s)

    // M3 calendar add — an add-verb near a calendar noun, or "remind me to / schedule a / book a …".
    private val calAddRe = Regex(
        "\\b(add|schedule|put|create|set\\s*up|book|make|new)\\b.*\\b(calendar|meeting|appointment|event|reminder)\\b|" +
            "\\b(remind me to|schedule a|book a|set up a|put .* on my calendar)\\b",
        RegexOption.IGNORE_CASE,
    )
    private fun isCalendarAdd(s: String) = calAddRe.containsMatchIn(s)

    // M3 calendar query — about the schedule. "calendar"/"agenda" rarely appear except about the
    // calendar, so match them directly; plus "my schedule"/"scheduled" and scheduling questions about
    // meetings/appointments. (A bare "meeting" in passing still won't trip it.)
    private val calQueryRe = Regex(
        "\\b(calendar|agenda)\\b|\\bmy schedule\\b|\\bscheduled\\b|" +
            "\\b(what'?s|what is|what do i have|do i have|when'?s|when is|anything|any)\\b" +
            ".{0,40}\\b(meetings?|appointments?)\\b",
        RegexOption.IGNORE_CASE,
    )
    private fun isCalendarQuery(s: String) = calQueryRe.containsMatchIn(s)

    // M3 email — an email keyword ("email/gmail/inbox/my mail"), or a mail verb near a message noun
    // ("look at my mail", "check my inbox", "draft/send/reply … message/mail").
    private val emailRe = Regex(
        "\\b(e-?mail|gmail|inbox)\\b|\\bmy mail\\b|" +
            "\\b(draft|write|send|compose|reply|respond|check|read|look at)\\b.{0,30}\\b(mail|message|note)\\b",
        RegexOption.IGNORE_CASE,
    )
    private fun isEmailCommand(s: String) = emailRe.containsMatchIn(s)

    private fun isClosing(s: String): Boolean {
        // Strip punctuation/case (Gemini returns "Thanks, Jarvis." / "Alright, thank you. Bye.").
        val t = s.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removeSuffix(" jarvis").trim()
        if (t.isEmpty()) return false
        val words = t.split(" ")
        if (words.size > 7) return false // a long sentence isn't a sign-off
        // Goodbye words almost never appear except to end — match anywhere in a short utterance, so
        // "thank you bye", "alright thank you bye", "don't talk again bye", "okay bye" all close.
        // ("by" is Gemini's frequent misspelling of "bye".)
        if (words.any { it == "bye" || it == "goodbye" || it == "byebye" } || t.contains("good bye")) return true
        if (t == "by" || t.endsWith(" by")) return true // misheard "bye"
        // Clear closing phrases, matched anywhere in the utterance.
        val phrases = listOf(
            "that's all", "thats all", "that'll be all", "thatll be all", "that is all", "that's it",
            "thats it", "that's enough", "thats enough", "nothing else", "we're done", "were done",
            "i'm done", "im done", "all done", "go to sleep", "never mind", "nevermind", "stop talking",
            "don't talk", "dont talk", "talk later", "speak later", "that will be all", "shut down",
        )
        if (phrases.any { t.contains(it) }) return true
        // thanks / thank you only when it's the whole sign-off (so a mid-chat "thanks, now…" doesn't end).
        if (t == "thanks" || t == "thank you" || t == "ok thanks" || t == "okay thanks" ||
            t.endsWith(" thanks") || t.endsWith(" thank you")
        ) return true
        // single-word commands.
        return words.size == 1 && words[0] in setOf("stop", "cancel", "quit", "exit", "done")
    }

    /** The part of an agent answer meant to be spoken aloud (drop a trailing "Sources:" block). */
    private fun spokenPart(text: String): String =
        text.substringBefore("\nSources:").substringBefore("Sources:").trim().ifBlank { text }

    /**
     * Record + transcribe one short utterance (for confirmations). Returns "" when nothing
     * intelligible was heard. Lighter than the main [converse] STT path — on-device first, cloud
     * fallback — and deliberately separate so it never disturbs the verified main voice loop.
     */
    private suspend fun captureUtterance(playCue: Boolean): String {
        micActive = true
        val rec = try {
            audio.recordUntilSilence(playCue = playCue, shouldAbort = { stopConversation })
        } finally { micActive = handsFree }
        if (stopConversation) return ""
        if (!rec.speechStarted || rec.peak < 900 || rec.rms < 70) return ""
        val onDevice = if (sherpa.isReady) sherpa.transcribe(rec.pcm, rec.sampleRate) else ""
        return if (onDevice.isNotBlank()) onDevice
        else runCatching { backend.transcribe(WavUtil.pcm16ToWav(rec.pcm, rec.sampleRate)) }.getOrDefault("")
    }

    /**
     * Trust gate (Agent Delegation §6): speak a yes/no question and listen for the answer. Returns
     * true ONLY on a clear affirmative — silence or anything ambiguous is treated as "no", so a
     * misheard reply never triggers an outward/irreversible action.
     */
    private suspend fun awaitConfirmation(prompt: String): Boolean {
        tts.speak(prompt)
        val heard = captureUtterance(playCue = true)
        if (heard.isNotBlank()) { question = heard; transcript.add(TurnLine(fromUser = true, text = heard)) }
        return isAffirmative(heard)
    }

    private fun isAffirmative(s: String): Boolean {
        val t = s.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isBlank()) return false
        // Any negation present ⇒ not a confirmation (fail safe).
        if (Regex("\\b(no|nope|don'?t|do not|cancel|stop|never\\s*mind|negative|forget it|wait)\\b").containsMatchIn(t)) return false
        return Regex("\\b(yes|yeah|yep|yup|sure|ok|okay|okey|go ahead|do it|please do|send it|confirm|affirmative|sounds good|go for it|correct|right|absolutely)\\b").containsMatchIn(t)
    }

    /**
     * VOICE-QUALITY INVESTIGATION (temporary, 2026-06-13) — debug builds only.
     * Persists each turn's exact mic WAV + the VAD/STT diagnostics so we can compare three artifacts
     * per turn: the audio actually captured (listen + spectral-analyse), the transcript Gemini
     * returned, and the spoken ground truth. Files land in the app's external files dir, pullable via
     *   adb pull /sdcard/Android/data/com.echo.companion/files/voicedbg ./voicedbg
     * Remove this (and the diagnostics on Recording) once the dominant failure mode is named.
     */
    private fun dumpVoiceDebug(wav: ByteArray, rec: Recording, heard: String, tRecord: Long, tStt: Long) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = java.io.File(appContext.getExternalFilesDir(null), "voicedbg").apply { mkdirs() }
            val ts = System.currentTimeMillis()
            java.io.File(dir, "turn_$ts.wav").writeBytes(wav)
            val line = listOf(
                ts.toString(),
                "%.2f".format(rec.seconds),
                rec.sampleRate.toString(),
                rec.peak.toString(),
                rec.rms.toString(),
                rec.noiseFloor.toString(),
                rec.threshold.toString(),
                rec.stopReason,
                "speech=${rec.speechStarted}",
                "rec=${tRecord}ms",
                "stt=${tStt}ms",
                heard.ifBlank { "(blank)" }.replace("\t", " ").replace("\n", " "),
            ).joinToString("\t")
            java.io.File(dir, "index.tsv").appendText(line + "\n")
            android.util.Log.i(
                "EchoVoice",
                "turn=$ts ${"%.2f".format(rec.seconds)}s sr=${rec.sampleRate} peak=${rec.peak} rms=${rec.rms} " +
                    "noiseFloor=${rec.noiseFloor} thr=${rec.threshold} stop=${rec.stopReason} speech=${rec.speechStarted} " +
                    "heard=\"${heard.take(80)}\"",
            )
        }
    }

    /** Toggle hands-free wake-word listening (say "Jarvis"). */
    fun toggleHandsFree(on: Boolean) {
        if (on) {
            handsFree = true
            // Wake word holds the mic open continuously — gate it behind recording consent.
            withRecordingConsent {
                if (startWake()) { status = "Hands-free on — say “Jarvis”"; micActive = true }
                else { handsFree = false; status = "Wake word error: ${wake.lastError}" }
            }
        } else {
            handsFree = false
            micActive = false
            wake.stop()
            status = "Hands-free off"
        }
    }

    private fun startWake(): Boolean = wake.start {
        // "Jarvis" detected. Release the wake mic, hold a full conversation, then resume listening
        // for the next "Jarvis" (you say the wake word once, then talk freely until you're done).
        wake.stop()
        run("Heard “Jarvis” — listening…") {
            converse(continuous = true)
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
                val msg = e.message ?: ""
                // A 401/JWKS failure that survived the one-shot refresh means the session is no longer
                // valid (e.g. the local stack was recreated and our persisted token is stale) — don't
                // fail cryptically or silently; drop the session and prompt a fresh sign-in.
                val authDead = "HTTP 401" in msg || "JWKS" in msg || "JWT" in msg ||
                    "no applicable key" in msg || "invalid token" in msg.lowercase()
                if (authDead) {
                    runCatching { backend.signOut() }
                    loggedIn = false
                    status = "Your session expired — please sign in again"
                } else {
                    status = "Error: ${e.message}"
                }
            } finally {
                busy = false
            }
        }
    }
}
