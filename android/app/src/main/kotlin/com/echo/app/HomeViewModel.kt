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
import com.echo.memory.EchoBackend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val backend: EchoBackend,
    private val audio: BtAudioEngine,
    private val ble: GlassesBleManager,
    private val tts: TtsEngine,
    private val wake: WakeWordEngine,
    private val buttons: GlassesButtonController,
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
    var handsFree by mutableStateOf(false); private set

    init {
        // Mirror the BLE manager's status into Compose state (declared after the state it sets).
        viewModelScope.launch { ble.status.collect { bleStatus = it } }
        // Glasses physical button -> start a voice turn (hands-free, no phone).
        buttons.onTrigger = { onGlassesButton() }
        buttons.activate()
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
        val m = backend.remember(Memory(type = MemoryType.NOTE, text = memoryText))
        status = "Remembered (${m.id?.take(8) ?: "ok"})"
    }

    fun ask() = run("Thinking…") {
        val result = backend.chat(question)
        answer = result.answer
        recalled = result.memoriesUsed
        status = "Answered"
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
        status = "Transcribing…"
        val heard = backend.transcribe(WavUtil.pcm16ToWav(rec.pcm, rec.sampleRate))
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
