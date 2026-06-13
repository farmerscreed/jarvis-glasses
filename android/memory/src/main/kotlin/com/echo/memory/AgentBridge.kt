package com.echo.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for the JARVIS Agent Bridge — the **deliberate lane** that delegates heavy, tool-using
 * tasks to headless Claude Code on the director's PC (`claude -p`, Max subscription). See
 * `agent-bridge/server.js` and `docs/AGENT_DELEGATION.md`.
 *
 * Phase 1 / M1: synchronous + local. The dev app reaches the bridge over `adb reverse tcp:8765`
 * (so [baseUrl] is `http://127.0.0.1:8765`). In prod the URL is empty ⇒ [isConfigured] is false ⇒
 * the voice loop falls back to a normal chat answer instead of delegating. Phase 2 moves the bridge
 * to a hosted env with async tickets + push.
 *
 * The [http] client given here MUST have a long read timeout (research can take a minute+).
 */
class AgentBridge(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** True when a bridge URL is configured (dev builds). Prod = false until Phase 2 (hosted). */
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /**
     * Run a research task: Claude Code with read-only + web tools, instructed to verify
     * time-sensitive/factual claims with WebSearch and cite sources (the M0 grounding finding —
     * headless Claude otherwise over-trusts its training). Returns a spoken-friendly answer.
     */
    suspend fun research(query: String): AgentResult = task(
        prompt = query,
        allowedTools = "WebSearch,WebFetch,Read,Glob,Grep",
        appendSystemPrompt = RESEARCH_PRESET,
        timeoutMs = 240_000,
    )

    /** Low-level: POST a task to the bridge and normalize the result. Never throws — returns ok=false. */
    suspend fun task(
        prompt: String,
        allowedTools: String,
        timeoutMs: Long,
        appendSystemPrompt: String? = null,
        cwd: String? = null,
    ): AgentResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext AgentResult(false, "The agent isn't available in this build.", 0)
        runCatching {
            val body = json.encodeToString(
                AgentTaskRequest.serializer(),
                AgentTaskRequest(prompt, allowedTools, timeoutMs, appendSystemPrompt, cwd),
            )
            val req = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/task")
                .apply { if (token.isNotBlank()) addHeader("Authorization", "Bearer $token") }
                .post(body.toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                val parsed = runCatching { json.decodeFromString(AgentTaskResponse.serializer(), txt) }.getOrNull()
                when {
                    parsed != null && parsed.status == "ok" && parsed.result.isNotBlank() ->
                        AgentResult(true, parsed.result.trim(), parsed.durationMs)
                    parsed?.status == "timeout" ->
                        AgentResult(false, "That took too long, so I stopped. Try a narrower question.", parsed.durationMs)
                    else -> {
                        val why = parsed?.error ?: "HTTP ${resp.code}"
                        AgentResult(false, "I couldn't complete that ($why).", parsed?.durationMs ?: 0)
                    }
                }
            }
        }.getOrElse { e ->
            // Bridge unreachable (PC off / adb reverse not set) — be honest, don't fake a result.
            AgentResult(false, "I couldn't reach my research agent. Is the bridge running on the PC?", 0)
        }
    }

    companion object {
        /**
         * Research preset, layered on as a SYSTEM prompt (`--append-system-prompt`). Enforces the
         * SOUL truth charter on the deliberate lane: verify, never guess, and answer in a form that
         * reads aloud well. The app speaks the part before "Sources:" and saves the whole thing.
         */
        val RESEARCH_PRESET = """
            You are JARVIS's research agent, acting for the director. Be truthful above all.
            ALWAYS verify time-sensitive or factual claims with the WebSearch tool before answering —
            do not rely on training memory for anything that could be out of date (versions, prices,
            news, dates, current facts). If sources conflict or you can't verify, say so plainly
            rather than guessing.
            Answer in a natural SPOKEN style: 3 to 6 short sentences, no markdown, no bullet points,
            and no URLs in the spoken part. Then, on a final line, write "Sources:" followed by the
            source URLs you actually used, one per line.
        """.trimIndent()
    }
}
