package com.echo.app.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SPIKE (2026-06-14, dev-only, behind the Developer console) — JARVIS's prospective **Brain 0**: a
 * small on-device LLM (Gemma 3 1B int4) via MediaPipe LLM Inference, kept **WARM** (loaded once,
 * resident) per the director's call. Purpose is **measurement**, not engine integration: cold-load
 * time, tokens/s, RAM, and the latency crossover vs cloud streaming. See `docs/ONDEVICE_BRAIN.md`.
 *
 * Model acquisition (gated — Gemma license): accept the license + download
 *   Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task  (555 MB) from
 *   https://huggingface.co/litert-community/Gemma3-1B-IT
 * then push it to the device (MediaPipe sample convention — adb-pushable, app-readable):
 *   adb push <file>.task /data/local/tmp/llm/gemma3-1b-it-int4.task
 */
class OnDeviceLlm(private val context: Context) {

    private val modelFile = File("/data/local/tmp/llm/gemma3-1b-it-int4.task")

    @Volatile private var llm: LlmInference? = null
    @Volatile var loadMs: Long = 0L; private set

    val isPresent: Boolean get() = modelFile.exists() && modelFile.length() > 0
    val isReady: Boolean get() = llm != null
    val modelPath: String get() = modelFile.absolutePath

    /** Load the model and keep it resident (WARM). False if the model file isn't on the device yet. */
    suspend fun ensureWarm(maxTokens: Int = 512): Boolean = withContext(Dispatchers.Default) {
        if (llm != null) return@withContext true
        if (!isPresent) { Log.w("EchoLlm", "model not present at ${modelFile.path}"); return@withContext false }
        val t0 = System.currentTimeMillis()
        llm = runCatching {
            LlmInference.createFromOptions(
                context,
                LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(maxTokens)
                    .build(),
            )
        }.onFailure { Log.e("EchoLlm", "load failed", it) }.getOrNull()
        loadMs = System.currentTimeMillis() - t0
        Log.i("EchoLlm", "warm load=${loadMs}ms ready=${llm != null}")
        llm != null
    }

    /** Generate a full response (sync) with timing. Null if not warm or on failure. */
    suspend fun generate(prompt: String): LlmResult? = withContext(Dispatchers.Default) {
        val engine = llm ?: return@withContext null
        val t0 = System.currentTimeMillis()
        val out = runCatching { engine.generateResponse(prompt) }
            .onFailure { Log.e("EchoLlm", "generate failed", it) }.getOrNull() ?: return@withContext null
        val ms = System.currentTimeMillis() - t0
        val tokens = out.trim().split(Regex("\\s+")).count { it.isNotBlank() } // rough: word ≈ token
        LlmResult(out, ms, tokens)
    }

    fun close() { runCatching { llm?.close() }; llm = null }
}

/** One on-device generation result + derived rate. */
data class LlmResult(val text: String, val ms: Long, val approxTokens: Int) {
    val tokensPerSec: Double get() = if (ms > 0) approxTokens * 1000.0 / ms else 0.0
}
