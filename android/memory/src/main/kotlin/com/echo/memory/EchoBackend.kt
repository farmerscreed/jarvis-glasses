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

    /** True if a (persisted) session token is present — lets the UI skip re-sign-in across launches. */
    val isLoggedIn: Boolean get() = session.isLoggedIn

    /** Sign in (password grant); if the user doesn't exist locally, sign them up. DEV ONLY. */
    suspend fun signIn(email: String, password: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(AuthRequest.serializer(), AuthRequest(email, password))
        val auth = tryAuth("/auth/v1/token?grant_type=password", body)
            ?: tryAuth("/auth/v1/signup", body)
            ?: error("sign-in failed")
        adopt(auth)
    }

    /** Step 1 of email sign-in: Supabase emails a 6-digit code (creates the user on first use). */
    suspend fun requestEmailOtp(email: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(OtpRequest.serializer(), OtpRequest(email))
        val req = Request.Builder()
            .url(session.baseUrl + "/auth/v1/otp")
            .addHeader("apikey", session.anonKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("couldn't send code: HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
        }
    }

    /** Step 2 of email sign-in: exchange the emailed code for a session. */
    suspend fun verifyEmailOtp(email: String, code: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(VerifyOtpRequest.serializer(), VerifyOtpRequest(email, code))
        val req = Request.Builder()
            .url(session.baseUrl + "/auth/v1/verify")
            .addHeader("apikey", session.anonKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // Surface GoTrue's real reason (e.g. otp_expired vs bad request), not a guess.
                val msg = runCatching {
                    json.decodeFromString(AuthError.serializer(), txt).let { it.msg ?: it.error_description ?: it.error }
                }.getOrNull()
                error(msg ?: "verify failed: HTTP ${resp.code}: $txt")
            }
            val auth = json.decodeFromString(AuthResponse.serializer(), txt)
            if (auth.access_token == null) error("verify returned no session")
            adopt(auth)
        }
    }

    /**
     * Rotate an expired access token with the stored refresh token. Returns false when there is
     * nothing to refresh with or the server rejected it — the caller must re-authenticate.
     * Synchronized so concurrent 401s (UI + SyncWorker) don't race the rotation.
     */
    @Synchronized
    fun refreshSession(): Boolean {
        val rt = session.refreshToken ?: return false
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(rt))
        val auth = tryAuth("/auth/v1/token?grant_type=refresh_token", body) ?: return false
        adopt(auth, fallbackRefreshToken = rt)
        return true
    }

    /** Sign out: best-effort server-side revoke, then always drop the local session. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val token = session.accessToken ?: return@runCatching
            val req = Request.Builder()
                .url(session.baseUrl + "/auth/v1/logout")
                .addHeader("apikey", session.anonKey)
                .addHeader("Authorization", "Bearer $token")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            http.newCall(req).execute().close()
        }
        session.clear()
    }

    private fun adopt(auth: AuthResponse, fallbackRefreshToken: String? = null) {
        session.accessToken = auth.access_token
        session.refreshToken = auth.refresh_token ?: fallbackRefreshToken
        auth.user?.id?.let { session.userId = it }
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
                client_id = memory.clientId,
            ),
        )
        val txt = post("/functions/v1/ingest", body)
        json.decodeFromString(IngestResponse.serializer(), txt).memory.toMemory()
    }

    /**
     * Upload a media file to a private bucket (`media` or `audio`), namespaced by user id
     * (RLS requires the first path segment = uid). Returns the storage object key for
     * `memories.media_path`.
     */
    suspend fun uploadMedia(
        bytes: ByteArray,
        fileName: String,
        mimeType: String = mimeFor(fileName),
        bucket: String = "media",
    ): String =
        withContext(Dispatchers.IO) {
            val uid = session.userId ?: error("not signed in")
            val month = java.time.YearMonth.now() // e.g. 2026-06
            val key = "$uid/$month/$fileName"
            fun attempt(): Pair<Int, String> {
                val req = Request.Builder()
                    .url("${session.baseUrl}/storage/v1/object/$bucket/$key")
                    .addHeader("apikey", session.anonKey)
                    .addHeader("Authorization", "Bearer ${session.accessToken}")
                    .addHeader("x-upsert", "true") // idempotent retries
                    .post(bytes.toRequestBody(mimeType.toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    return resp.code to resp.body?.string().orEmpty()
                }
            }
            var (code, err) = attempt()
            if (code == 401 && refreshSession()) {
                val retry = attempt()
                code = retry.first; err = retry.second
            }
            if (code !in 200..299) error("upload failed: HTTP $code: $err")
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

    /** Transcribe an audio clip via the Gemini-backed transcribe function. */
    suspend fun transcribe(audio: ByteArray, mimeType: String = "audio/wav"): String = withContext(Dispatchers.IO) {
        val b64 = android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)
        val body = json.encodeToString(TranscribeRequest.serializer(), TranscribeRequest(b64, mimeType))
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

    /**
     * Streaming RAG turn: [onDelta] is called with each text chunk as Claude produces it (so the
     * caller can speak sentence-by-sentence). Returns the full answer + memories used.
     */
    suspend fun chatStream(message: String, onDelta: (String) -> Unit): ChatResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(message))
        fun call(): okhttp3.Response {
            val builder = Request.Builder()
                .url(session.baseUrl + "/functions/v1/chat-stream")
                .addHeader("apikey", session.anonKey)
                .addHeader("Accept", "text/event-stream")
            session.accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }
            builder.post(body.toRequestBody(jsonMedia))
            return http.newCall(builder.build()).execute()
        }
        var first = call()
        if (first.code == 401 && refreshSession()) {
            first.close()
            first = call()
        }
        first.use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
            val source = resp.body?.source() ?: error("no stream body")
            var event = "message"
            var memories = emptyList<Memory>()
            val full = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isBlank() -> event = "message" // SSE record boundary
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        when (event) {
                            "memories" -> memories =
                                json.decodeFromString(StreamMemories.serializer(), data).matches.map { it.toMemory() }
                            "done", "error" -> {}
                            else -> {
                                val delta = json.decodeFromString(StreamDelta.serializer(), data).t
                                if (delta.isNotEmpty()) { full.append(delta); onDelta(delta) }
                            }
                        }
                    }
                }
            }
            ChatResult(full.toString(), memories)
        }
    }

    private fun post(path: String, body: String): String {
        var (code, txt) = postOnce(path, body)
        // Expired access token: rotate via the refresh token and retry once.
        if (code == 401 && refreshSession()) {
            val retry = postOnce(path, body)
            code = retry.first; txt = retry.second
        }
        if (code !in 200..299) error("HTTP $code: $txt")
        return txt
    }

    private fun postOnce(path: String, body: String): Pair<Int, String> {
        val builder = Request.Builder()
            .url(session.baseUrl + path)
            .addHeader("apikey", session.anonKey)
        session.accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }
        builder.post(body.toRequestBody(jsonMedia))
        http.newCall(builder.build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    companion object {
        fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            "m4a" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg" // Opus ships in an Ogg container; bucket+Gemini accept audio/ogg
            else -> "application/octet-stream"
        }
    }
}
