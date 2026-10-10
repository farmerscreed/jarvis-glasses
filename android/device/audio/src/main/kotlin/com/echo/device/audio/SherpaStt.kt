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

    // Two models: Whisper base.en (preferred — far fewer mishearings on the glasses mic: "say" vs "see",
    // place names) and tiny.en (the original; kept as a fallback so voice works while base downloads).
    // Both int8; same file layout. base.en ≈ 160 MB, downloaded once.
    private val dir = File(context.filesDir, "stt")
    private class Model(val name: String, val encoder: File, val decoder: File, val tokens: File, val parts: List<Triple<File, String, Long>>) {
        val present: Boolean get() = encoder.exists() && decoder.exists() && tokens.exists()
    }
    private fun model(name: String, encSize: Long, decSize: Long): Model {
        val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$name/resolve/main"
        val enc = File(dir, "$name-encoder.int8.onnx"); val dec = File(dir, "$name-decoder.int8.onnx"); val tok = File(dir, "$name-tokens.txt")
        return Model(name, enc, dec, tok, listOf(
            Triple(enc, "$base/$name-encoder.int8.onnx", encSize),
            Triple(dec, "$base/$name-decoder.int8.onnx", decSize),
            Triple(tok, "$base/$name-tokens.txt", 835_554L),
        ))
    }
    private val best = model("base.en", 29_120_534L, 130_669_978L)
    private val fallback = model("tiny.en", 12_937_772L, 89_853_865L)

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedName: String = ""

    /** The preferred (base.en) model is on disk. */
    val isDownloaded: Boolean get() = best.present

    /** Recognizer loaded and ready to transcribe (either model). */
    val isReady: Boolean get() = recognizer != null

    /** The preferred model is the one loaded. */
    val isBestLoaded: Boolean get() = loadedName == best.name
    /** Which on-device model is in use ("base.en" = the better one, "tiny.en" = the small fallback). */
    val loadedModelName: String get() = loadedName.ifBlank { "none" }

    /** Download the model on first run. [onProgress] reports 0f..1f. Resumable per-file (skips done). */
    suspend fun download(onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isDownloaded) return@withContext true
        dir.mkdirs()
        val parts = best.parts
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

    /** Load the best model on disk (base.en, else tiny.en). Re-loads to upgrade once base.en arrives. */
    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.Default) {
        val m = when { best.present -> best; fallback.present -> fallback; else -> return@withContext recognizer != null }
        if (recognizer != null && loadedName == m.name) return@withContext true
        val r = runCatching {
            OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(encoder = m.encoder.path, decoder = m.decoder.path),
                        tokens = m.tokens.path,
                        numThreads = 4,
                        modelType = "whisper",
                    ),
                ),
            )
        }.onFailure { Log.e("SherpaStt", "recognizer load failed (${m.name})", it) }.getOrNull()
        if (r != null) {
            // The previous (small) model is left to the garbage collector rather than released here,
            // so a transcription already running on it can never hit freed native memory.
            recognizer = r; loadedName = m.name
            Log.i("SherpaStt", "loaded ${m.name}")
        }
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
