package com.echo.device.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * On-device text-to-speech (Android TextToSpeech) — free, no key. Output uses the media route,
 * so when the glasses are the active A2DP device the answer is spoken into your ear.
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

    /** Speak [text] and suspend until playback finishes. */
    suspend fun speak(text: String) {
        if (!ready.await()) return
        tts.language = Locale.US
        suspendCancellableCoroutine { cont ->
            val id = "echo-${cont.hashCode()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(Unit) }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
