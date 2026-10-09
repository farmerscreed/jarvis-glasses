package com.echo.app.translate

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * "Say-it" mode: speaks a phrase in another language OUT LOUD from the phone's loudspeaker, so the
 * person in front of you hears it (your glasses keep the English lead-in private). Uses the phone's
 * own TTS voices (Google TTS ships Mandarin, French, Arabic…): synthesize to a file, then play that
 * file pinned to the built-in speaker — TextToSpeech itself can't be routed to a specific device.
 * Keeps the last phrase so "say it again" replays instantly.
 */
@Singleton
class PhraseSpeaker @Inject constructor(@ApplicationContext private val context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context) { ready.complete(it == TextToSpeech.SUCCESS) }
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastFile: File? = null

    /** Speak [text] in [langCode] (BCP-47) on the loudspeaker. Returns false if no voice is installed. */
    suspend fun speakAloud(text: String, langCode: String): Boolean {
        if (!ready.await() || text.isBlank()) return false
        val locale = localeFor(langCode)
        val avail = tts.isLanguageAvailable(locale)
        if (avail < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "no TTS voice for $langCode ($avail)")
            return false
        }
        tts.language = locale
        val out = File(context.cacheDir, "phrase.wav")
        val ok = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val id = "phrase-${System.nanoTime()}"
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(true) }
                    @Deprecated("deprecated in API level 21")
                    override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (cont.isActive) cont.resume(false) }
                })
                tts.synthesizeToFile(text, null, out, id)
            }
        } ?: false
        if (!ok || !out.exists()) return false
        lastFile = out
        return playOnSpeaker(out)
    }

    /** Replay the last phrase ("say it again"). */
    suspend fun repeat(): Boolean = lastFile?.takeIf { it.exists() }?.let { playOnSpeaker(it) } ?: false

    private suspend fun playOnSpeaker(file: File): Boolean = withContext(Dispatchers.Main) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            mp.setDataSource(file.absolutePath)
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { runCatching { mp.setPreferredDevice(it) } }
            mp.prepare()
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine { cont ->
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                    mp.start()
                    Log.i(TAG, "phrase playing on ${mp.routedDevice?.type}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            false
        } finally {
            runCatching { mp.release() }
        }
    }

    companion object {
        private const val TAG = "EchoPhrase"
        fun localeFor(code: String): Locale = when (TranslationController.normalizeLang(code)) {
            "zh-Hans" -> Locale.SIMPLIFIED_CHINESE
            "zh-Hant" -> Locale.TRADITIONAL_CHINESE
            "fr" -> Locale.FRENCH
            else -> Locale.forLanguageTag(code)
        }
    }
}
