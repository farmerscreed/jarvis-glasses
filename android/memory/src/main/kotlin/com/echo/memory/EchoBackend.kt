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
        val auth = tryAuth("/auth/v1/token?grant_type=password", body)
            ?: tryAuth("/auth/v1/signup", body)
            ?: error("sign-in failed")
        session.accessToken = auth.access_token
        session.userId = auth.user?.id
    }

    private fun tryAuth(path: String, body: String): AuthResponse? {
        val req = Request.Builder()
            .url(session.baseUrl + path)
            .addHeader("apikey", session.anonKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val txt = resp.body?.string() ?: return null
            val auth = json.decodeFromString(AuthResponse.serializer(), txt)
            return if (auth.access_token != null) auth else null
        }
    }

    override suspend fun remember(memory: Memory): Memory = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            IngestRequest.serializer(),
            IngestRequest(
                text = memory.text.orEmpty(),
                type = memory.type.wire,
                media_path = memory.mediaPath,
                lat = memory.lat,
                lng = memory.lng,
                tags = memory.tags,
            ),
        )
        val txt = post("/functions/v1/ingest", body)
        json.decodeFromString(IngestResponse.serializer(), txt).memory.toMemory()
    }

    /**
     * Upload a media file to the private `media` bucket, namespaced by user id (RLS requires
     * the first path segment = uid). Returns the storage object key for `memories.media_path`.
     */
    suspend fun uploadMedia(bytes: ByteArray, fileName: String, mimeType: String = mimeFor(fileName)): String =
        withContext(Dispatchers.IO) {
            val uid = session.userId ?: error("not signed in")
            val month = java.time.YearMonth.now() // e.g. 2026-06
            val key = "$uid/$month/$fileName"
            val req = Request.Builder()
                .url("${session.baseUrl}/storage/v1/object/media/$key")
                .addHeader("apikey", session.anonKey)
                .addHeader("Authorization", "Bearer ${session.accessToken}")
                .addHeader("x-upsert", "true") // idempotent retries
                .post(bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("upload failed: HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            }
            key
        }

    /** Short-lived signed URL for a private storage object (the read path for `media_path`). */
    suspend fun signedMediaUrl(path: String, expiresInSec: Int = 3600): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SignUrlRequest.serializer(), SignUrlRequest(expiresInSec))
        val txt = post("/storage/v1/object/sign/media/$path", body)
        val signed = json.decodeFromString(SignUrlResponse.serializer(), txt).signedURL
        "${session.baseUrl}/storage/v1$signed"
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

    /** Send a synced photo to Claude vision; returns its description/answer. */
    suspend fun describeImage(jpeg: ByteArray, prompt: String? = null): String = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val body = json.encodeToString(VisionRequest.serializer(), VisionRequest(b64, prompt = prompt))
        val txt = post("/functions/v1/vision", body)
        json.decodeFromString(VisionResponse.serializer(), txt).text
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

    private companion object {
        fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
