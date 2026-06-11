package com.echo.device.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream

/**
 * Offline, no-key wake-word detection via Vosk. Listens (phone mic) and fires [onWake] (main thread)
 * when it hears "jarvis". The small English model is bundled in assets/vosk-model-en, copied to
 * filesDir on first use (manual copy for reliability + logging), then loaded. Logs to "EchoWake".
 */
class WakeWordEngine(private val context: Context) {

    companion object { const val TAG = "EchoWake" }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var onWakeCb: (() -> Unit)? = null
    private var fired = false

    var lastError: String? = null
        private set

    val isConfigured: Boolean get() = true

    fun start(onWake: () -> Unit): Boolean {
        onWakeCb = onWake
        fired = false
        if (speech != null) return true
        val existing = model
        if (existing != null) {
            main.post { if (speech == null) beginListening(existing) }
            return true
        }
        Thread {
            try {
                Log.i(TAG, "preparing model…")
                val dir = File(context.filesDir, "vosk-model-en")
                if (!File(dir, "am/final.mdl").exists()) {
                    Log.i(TAG, "copying model from assets to ${dir.absolutePath}")
                    copyAsset("vosk-model-en", dir)
                }
                val m = Model(dir.absolutePath) // loads native lib
                Log.i(TAG, "model loaded OK")
                model = m
                main.post { if (onWakeCb != null && speech == null) beginListening(m) }
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                Log.e(TAG, "model prepare FAILED", t)
            }
        }.start()
        return true
    }

    private fun copyAsset(assetPath: String, out: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            out.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
        } else {
            out.mkdirs()
            for (c in children) copyAsset("$assetPath/$c", File(out, c))
        }
    }

    private fun beginListening(m: Model) {
        try {
            val recognizer = Recognizer(m, 16000.0f)
            val service = SpeechService(recognizer, 16000.0f)
            speech = service
            Log.i(TAG, "listening for 'jarvis'…")
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis)
                override fun onResult(hypothesis: String?) = check(hypothesis)
                override fun onFinalResult(hypothesis: String?) = check(hypothesis)
                override fun onError(e: Exception?) { lastError = e?.message; Log.e(TAG, "listen error", e) }
                override fun onTimeout() {}
            })
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            Log.e(TAG, "beginListening FAILED", t)
        }
    }

    private fun check(hypothesis: String?) {
        if (hypothesis == null) return
        if (hypothesis.contains("\"") && hypothesis.length > 14) Log.i(TAG, "heard: $hypothesis")
        if (fired) return
        val h = hypothesis.lowercase()
        if (h.contains("jarvis") || h.contains("jervis") || h.contains("travis")) {
            fired = true
            Log.i(TAG, "WAKE WORD DETECTED")
            onWakeCb?.invoke()
        }
    }

    fun stop() {
        speech?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        speech = null
        fired = false
    }
}
