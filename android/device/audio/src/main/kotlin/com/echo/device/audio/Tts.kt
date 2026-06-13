package com.echo.device.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * On-device text-to-speech (Android TextToSpeech) — free, no key. Output uses the media route,
 * so when the glasses are the active A2DP device the answer is spoken into your ear.
 *
 * [stop] cuts playback AND unblocks whatever is suspended in [speak]/[finishStream] — Android's
 * TextToSpeech.stop() does not reliably deliver onDone, so without this an End/barge-in would leave
 * the conversation coroutine hung waiting for speech that was already cancelled.
 */
class TtsEngine(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    // The continuation currently waiting on playback to finish (speak or finishStream). Resumed by
    // onDone/onError in the normal case, or by stop() when speech is cut short.
    @Volatile private var pending: CancellableContinuation<Unit>? = null

    /**
     * Route speech over the Bluetooth SCO / voice-communication output instead of A2DP media. Needed
     * for barge-in: while a held SCO session lets us hear the user, the answer must play on that same
     * full-duplex link (A2DP is suspended during SCO). Quality is narrowband but intelligible. Call
     * with false to restore the hi-fi media route for normal one-shot answers.
     */
    fun useCommunicationRoute(on: Boolean) {
        val usage = if (on) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
    }

    @Synchronized private fun resumePending() {
        val c = pending ?: return
        pending = null
        if (c.isActive) c.resume(Unit)
    }

    /** Speak [text] and suspend until playback finishes (or is stopped). */
    suspend fun speak(text: String) {
        if (!ready.await()) return
        tts.language = Locale.US
        suspendCancellableCoroutine { cont ->
            pending = cont
            cont.invokeOnCancellation { pending = null }
            val id = "echo-${cont.hashCode()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { resumePending() }
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) { resumePending() }
                override fun onError(utteranceId: String?, errorCode: Int) { resumePending() }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    /** Prepare for a streamed turn: wait for init, set language, clear any queued speech. */
    suspend fun beginStream(): Boolean {
        if (!ready.await()) return false
        tts.language = Locale.US
        runCatching { tts.stop() }
        return true
    }

    /** Queue one sentence to speak after whatever is already queued (non-blocking) — for streaming. */
    fun enqueue(text: String) {
        if (text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "echo-stream-${System.identityHashCode(text)}")
    }

    /**
     * Suspend until everything queued via [enqueue] has been spoken — a silent sentinel utterance
     * is appended and we resume when it completes. Keeps streamed turns sequential (hands-free
     * mustn't re-open the mic while the answer is still playing).
     */
    suspend fun finishStream() {
        if (!ready.await()) return
        suspendCancellableCoroutine { cont ->
            pending = cont
            cont.invokeOnCancellation { pending = null }
            val id = "echo-stream-end-${cont.hashCode()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) resumePending()
                }
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) { resumePending() }
                override fun onError(utteranceId: String?, errorCode: Int) { resumePending() }
            })
            tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, id)
        }
    }

    /** True while TTS is actively speaking — used by barge-in to know there's something to interrupt. */
    val isSpeaking: Boolean get() = runCatching { tts.isSpeaking }.getOrDefault(false)

    /** Cut current/queued speech immediately and unblock any suspended speak()/finishStream(). */
    fun stop() {
        runCatching { tts.stop() }
        resumePending()
    }

    fun shutdown() {
        runCatching { tts.stop() }
        resumePending()
        runCatching { tts.shutdown() }
    }
}
