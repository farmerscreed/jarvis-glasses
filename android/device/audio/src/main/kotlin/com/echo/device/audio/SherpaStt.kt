package com.echo.device.audio

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device speech-to-text via sherpa-onnx (offline Whisper) — the primary STT path: real-time, no
 * network, and never quota-limited (the cloud Gemini free tier ran dry). The model (~103 MB,
 * Whisper-tiny.en int8) is **downloaded on first run** to filesDir/stt and loaded once; cloud STT
 * stays as a fallback for when the model isn't downloaded yet or returns nothing.
 *
 * Our voice pipeline already endpoints a complete utterance (record → VAD), i.e. batch transcription,
 * which is exactly where on-device Whisper is fast (~1–3 s for a short clip on a modern SoC).
 */
class SherpaStt(private val context: Context) {

    // Whisper-tiny.en int8 — small + fast; bump to base.en here if accuracy on the noisy glasses mic
    // proves insufficient (same file layout, larger download).
    private val modelBase =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main"
    private val dir = File(context.filesDir, "stt")
    private val encoder = File(dir, "tiny.en-encoder.int8.onnx")
    private val decoder = File(dir, "tiny.en-decoder.int8.onnx")
    private val tokens = File(dir, "tiny.en-tokens.txt")
    private val parts = listOf(
        Triple(encoder, "$modelBase/tiny.en-encoder.int8.onnx", 12_937_772L),
        Triple(decoder, "$modelBase/tiny.en-decoder.int8.onnx", 89_853_865L),
        Triple(tokens, "$modelBase/tiny.en-tokens.txt", 835_554L),
    )

    @Volatile private var recognizer: OfflineRecognizer? = null

    /** Model files present on disk. */
    val isDownloaded: Boolean get() = encoder.exists() && decoder.exists() && tokens.exists()

    /** Recognizer loaded and ready to transcribe. */
    val isReady: Boolean get() = recognizer != null

    /** Download the model on first run. [onProgress] reports 0f..1f. Resumable per-file (skips done). */
    suspend fun download(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded) return@withContext true
        dir.mkdirs()
        val grand = parts.sumOf { it.third }.toFloat()
        var done = 0L
        try {
            for ((target, url, size) in parts) {
                if (target.exists() && target.length() > 0) { done += size; onProgress(done / grand); continue }
                val tmp = File(target.path + ".part")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                }
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var n = input.read(buf)
                        while (n > 0) {
                            out.write(buf, 0, n); done += n
                            onProgress((done / grand).coerceIn(0f, 1f))
                            n = input.read(buf)
                        }
                    }
                }
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            }
            isDownloaded
        } catch (e: Exception) {
            Log.e("SherpaStt", "model download failed", e)
            false
        }
    }

    /** Load the recognizer from the downloaded files (idempotent, off the main thread). */
    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.Default) {
        if (recognizer != null) return@withContext true
        if (!isDownloaded) return@withContext false
        recognizer = runCatching {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(encoder = encoder.path, decoder = decoder.path),
                        tokens = tokens.path,
                        numThreads = 2,
                        modelType = "whisper",
                    ),
                ),
            )
        }.onFailure { Log.e("SherpaStt", "recognizer load failed", it) }.getOrNull()
        recognizer != null
    }

    /** Transcribe 16-bit little-endian mono PCM. Returns "" if not ready or on error. */
    suspend fun transcribe(pcm16: ByteArray, sampleRate: Int): String = withContext(Dispatchers.Default) {
        val rec = recognizer ?: return@withContext ""
        runCatching {
            val n = pcm16.size / 2
            val samples = FloatArray(n)
            var i = 0
            while (i < n) {
                val lo = pcm16[2 * i].toInt() and 0xFF
                val hi = pcm16[2 * i + 1].toInt() // sign-extends → preserves negative samples
                samples[i] = ((hi shl 8) or lo).toShort() / 32768f
                i++
            }
            val stream = rec.createStream()
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val text = rec.getResult(stream).text
            stream.release()
            text.trim()
        }.onFailure { Log.e("SherpaStt", "transcribe failed", it) }.getOrDefault("")
    }
}
