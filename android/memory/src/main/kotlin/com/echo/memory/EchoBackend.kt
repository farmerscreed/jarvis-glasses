package com.echo.memory

import com.echo.core.model.Memory
import com.echo.core.model.MemoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to the Supabase backend: Auth (/auth/v1) for sign-in and the Edge Functions
 * (/functions/v1/{ingest,recall,chat}) for the memory loop. Implements [MemoryRepository];
 * also exposes [chat] for RAG turns.
 */
class EchoBackend(
    private val session: SupabaseSession,
    private val http: OkHttpClient,
    private val json: Json,
) : MemoryRepository {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Sign in (password grant); if the user doesn't exist locally, sign them up. */
    suspend fun signIn(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password))
        val token = tryAuth("/auth/v1/token?grant_type=password", body)
            ?: tryAuth("/auth/v1/signup", body)
            ?: error("sign-in failed")
        session.accessToken = token
    }

    private fun tryAuth(path: String, body: String): String? {
        val req = Request.Builder()
            .url(session.baseUrl + path)
            .addHeader("apikey", session.anonKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val txt = resp.body?.string() ?: return null
            return json.decodeFromString(AuthResponse.serializer(), txt).access_token
        }
    }

    override suspend fun remember(memory: Memory): Memory = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            IngestRequest.serializer(),
            IngestRequest(text = memory.text.orEmpty(), type = memory.type.wire),
        )
        val txt = post("/functions/v1/ingest", body)
        json.decodeFromString(IngestResponse.serializer(), txt).memory.toMemory()
    }

    override suspend fun recall(query: String, limit: Int, type: MemoryType?): List<Memory> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                RecallRequest.serializer(),
                RecallRequest(query = query, limit = limit, type = type?.wire),
            )
            val txt = post("/functions/v1/recall", body)
            json.decodeFromString(RecallResponse.serializer(), txt).matches.map { it.toMemory() }
        }

    /** Transcribe a WAV clip (from the glasses mic) via the Gemini-backed transcribe function. */
    suspend fun transcribe(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
        val body = json.encodeToString(TranscribeRequest.serializer(), TranscribeRequest(b64))
        val txt = post("/functions/v1/transcribe", body)
        json.decodeFromString(TranscribeResponse.serializer(), txt).text
    }

    suspend fun chat(message: String): ChatResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(message))
        val txt = post("/functions/v1/chat", body)
        val resp = json.decodeFromString(ChatResponse.serializer(), txt)
        ChatResult(resp.answer, resp.memories_used.map { it.toMemory() })
    }

    private fun post(path: String, body: String): String {
        val builder = Request.Builder()
            .url(session.baseUrl + path)
            .addHeader("apikey", session.anonKey)
        session.accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }
        builder.post(body.toRequestBody(jsonMedia))
        http.newCall(builder.build()).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: $txt")
            return txt
        }
    }
}
