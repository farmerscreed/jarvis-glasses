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
        val body = json.encodeToString(OtpRequest.serializer(), OtpRequest(email, create_user = true))
        val req = Request.Builder()
            .url(session.baseUrl + "/auth/v1/otp")
            .addHeader("apikey", session.anonKey)
            .post(body.toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("couldn't send code: HTTP ${resp.code}: ${resp.body?.string().orEmpty()}")
        }
    }

    /** Sign in with a Google ID token (One-Tap). Supabase verifies it against the configured provider. */
    suspend fun signInWithGoogle(idToken: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(IdTokenRequest.serializer(), IdTokenRequest(provider = "google", id_token = idToken))
        val auth = tryAuth("/auth/v1/token?grant_type=id_token", body) ?: error("Google sign-in failed")
        adopt(auth)
    }

    /** Step 2 of email sign-in: exchange the emailed code for a session. */
    suspend fun verifyEmailOtp(email: String, code: String): Unit = withContext(Dispatchers.IO) {
        val body = json.encodeToString(VerifyOtpRequest.serializer(), VerifyOtpRequest(email, code, type = "email"))
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

    /**
     * Upload one meeting chunk to the private `recordings` bucket at `<uid>/<clientId>/<name>` (upsert, so a
     * retry just overwrites). Throws on failure. Phase 2 · M2.
     */
    suspend fun uploadRecordingChunk(clientId: String, name: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val uid = session.userId ?: error("not signed in")
        fun attempt(): Pair<Int, String> {
            val req = Request.Builder()
                .url("${session.baseUrl}/storage/v1/object/recordings/$uid/$clientId/$name")
                .addHeader("apikey", session.anonKey)
                .addHeader("Authorization", "Bearer ${session.accessToken}")
                .addHeader("x-upsert", "true")
                .post(bytes.toRequestBody("audio/mp4".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp -> return resp.code to resp.body?.string().orEmpty() }
        }
        var r = attempt()
        if (r.first == 401 && refreshSession()) r = attempt()
        if (r.first !in 200..299) error("chunk upload failed: HTTP ${r.first}: ${r.second.take(200)}")
    }

    /** POST a JSON body to the `meeting` function; returns (HTTP code, body) so callers can react to 409. */
    suspend fun meeting(body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        postAuthed("/functions/v1/meeting", body, http)
    }

    /** Short-lived signed URL for a private storage object (the read path for `media_path`). */
    suspend fun signedMediaUrl(path: String, expiresInSec: Int = 3600): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SignUrlRequest.serializer(), SignUrlRequest(expiresInSec))
        val txt = post("/storage/v1/object/sign/media/$path", body)
        val signed = json.decodeFromString(SignUrlResponse.serializer(), txt).signedURL
        "${session.baseUrl}/storage/v1$signed"
    }

    /** Delete a memory row from the cloud by server id (RLS: owner only). Idempotent. */
    suspend fun deleteMemory(serverId: String): Unit = withContext(Dispatchers.IO) {
        delete("/rest/v1/memories?id=eq.$serverId")
    }

    /** Delete ALL of the caller's memory rows (RLS scopes to the user). GDPR "delete everything". */
    suspend fun deleteAllMemories(): Unit = withContext(Dispatchers.IO) {
        delete("/rest/v1/memories?id=not.is.null") // PostgREST requires a filter; RLS limits to the user
    }

    /** Delete a stored media object (RLS: owner only). Idempotent — a missing object is fine. */
    suspend fun deleteMedia(path: String, bucket: String = "media"): Unit = withContext(Dispatchers.IO) {
        delete("/storage/v1/object/$bucket/$path")
    }

    /** Authed DELETE with a single 401 refresh-retry, mirroring [post]. 2xx/404 are success. */
    private fun delete(path: String) {
        fun once(): Int {
            val builder = Request.Builder()
                .url(session.baseUrl + path)
                .addHeader("apikey", session.anonKey)
            session.accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }
            builder.delete()
            http.newCall(builder.build()).execute().use { return it.code }
        }
        var code = once()
        if (code == 401 && refreshSession()) code = once()
        if (code !in 200..299 && code != 404) error("HTTP $code on DELETE $path")
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
    suspend fun describeImage(jpeg: ByteArray, prompt: String? = null, fast: Boolean = false): String = withContext(Dispatchers.IO) {
        // Shrink before upload: the glasses shoot ~1.5 MB JPEGs; ~1600 px is plenty for reading text and
        // cuts the upload (often over mobile data) to a few hundred KB.
        val small = shrinkJpeg(jpeg, maxSide = 1600, quality = 85)
        val b64 = android.util.Base64.encodeToString(small, android.util.Base64.NO_WRAP)
        val body = json.encodeToString(VisionRequest.serializer(), VisionRequest(b64, prompt = prompt, fast = fast))
        val txt = post("/functions/v1/vision", body)
        json.decodeFromString(VisionResponse.serializer(), txt).text
    }

    suspend fun chat(message: String, history: List<ChatMsg> = emptyList()): ChatResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(message, history))
        val txt = post("/functions/v1/chat", body)
        val resp = json.decodeFromString(ChatResponse.serializer(), txt)
        ChatResult(resp.answer, resp.memories_used.map { it.toMemory() })
    }

    /**
     * Streaming RAG turn: [onDelta] is called with each text chunk as Claude produces it (so the
     * caller can speak sentence-by-sentence). Returns the full answer + memories used.
     * [history] threads prior turns for multi-turn conversation.
     */
    suspend fun chatStream(
        message: String,
        history: List<ChatMsg> = emptyList(),
        onDelta: (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ChatRequest.serializer(), ChatRequest(message, history))
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

    /**
     * Distill durable facts from a finished conversation into the user's profile (assistant memory
     * v1.2). Best-effort and fire-and-forget — never block or fail the conversation on this.
     */
    suspend fun distill(transcript: List<ChatMsg>): Unit = withContext(Dispatchers.IO) {
        if (transcript.isEmpty()) return@withContext
        runCatching {
            val body = json.encodeToString(DistillRequest.serializer(), DistillRequest(transcript))
            post("/functions/v1/distill", body)
        }
    }

    /** Read the assistant profile (SOUL + curated user facts). */
    suspend fun getProfile(): ProfileDto = withContext(Dispatchers.IO) {
        json.decodeFromString(ProfileDto.serializer(), get("/functions/v1/profile"))
    }

    /** Update the profile — pass only the field(s) to change (encodeDefaults=false drops the nulls). */
    suspend fun setProfile(soul: String? = null, userFacts: String? = null): ProfileDto = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ProfileUpdate.serializer(), ProfileUpdate(soul, userFacts))
        json.decodeFromString(ProfileDto.serializer(), post("/functions/v1/profile", body))
    }

    /** Explicit "remember that…" — pin a fact into the profile immediately (v1.3). */
    suspend fun remember(text: String): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(RememberRequest.serializer(), RememberRequest(text))
            post("/functions/v1/remember", body)
        }
    }

    // ── Deliberate lane in the cloud (2026-10-09) ─────────────────────────────────────────────
    // Research + translation run as Edge Functions on the API key, so they work anywhere (no PC
    // bridge). Bodies are built with org.json to sidestep the kotlinx encodeDefaults=false trap.

    /** Long-running calls (web research can take 10–60 s) get their own generous read timeout. */
    private val slowHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(150, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /**
     * Web research (Claude + web search). Returns the spoken answer followed by "Sources:" and one
     * URL per line — the same shape the agent-bridge research lane produced, so cards parse it.
     */
    /** Web research. With [image] (a photo of what the wearer is looking at) the agent identifies the
     *  object first, then researches it (price, where to buy, reviews…) — "research this cot". */
    suspend fun research(query: String, history: List<ChatMsg> = emptyList(), image: ByteArray? = null): ResearchResult = withContext(Dispatchers.IO) {
        val hist = org.json.JSONArray()
        history.forEach { hist.put(org.json.JSONObject().put("role", it.role).put("content", it.content)) }
        val o0 = org.json.JSONObject().put("query", query).put("history", hist)
        if (image != null) {
            val small = shrinkJpeg(image, maxSide = 1280, quality = 82)
            o0.put("image", android.util.Base64.encodeToString(small, android.util.Base64.NO_WRAP))
        }
        val body = o0.toString()
        val t0 = System.currentTimeMillis()
        val o = org.json.JSONObject(post("/functions/v1/research", body, slowHttp))
        ResearchResult(
            text = o.optString("text"),
            spoken = o.optString("spoken"),
            searches = o.optInt("searches"),
            item = o.optString("item"),
            durationMs = System.currentTimeMillis() - t0,
            headline = o.optString("headline"),
            sources = o.optJSONArray("sources")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("title") to it.optString("url") } }
                    .filter { it.second.isNotBlank() }
            }.orEmpty(),
        )
    }

    /** A single-use Gemini Live Translate token locked to [target] (BCP-47, e.g. "en", "zh-Hans"). */
    suspend fun translateToken(target: String): TranslateToken = withContext(Dispatchers.IO) {
        val o = org.json.JSONObject(post("/functions/v1/translate", org.json.JSONObject().put("op", "token").put("target", target).toString()))
        val urls = o.optJSONArray("urls")
        TranslateToken(
            token = o.getString("token"),
            model = o.optString("model", "models/gemini-3.5-live-translate-preview"),
            urls = (0 until (urls?.length() ?: 0)).map { urls!!.getString(it) },
        )
    }

    /** "How do I say X in Y" → translation + romanization (+ the language code for the TTS voice). */
    suspend fun translatePhrase(text: String, target: String? = null): PhraseResult = withContext(Dispatchers.IO) {
        val b = org.json.JSONObject().put("op", "phrase").put("text", text)
        if (target != null) b.put("target", target)
        val o = org.json.JSONObject(post("/functions/v1/translate", b.toString()))
        PhraseResult(
            langCode = o.optString("langCode"),
            langName = o.optString("langName"),
            phrase = o.optString("phrase"),
            translation = o.optString("translation"),
            romanization = o.optString("romanization"),
            spoken = o.optString("spoken"),
        )
    }

    /** Summarize a finished translated conversation and save it as a memory. Returns the summary. */
    suspend fun translateSave(lines: List<TranslatedLine>, mode: String, seconds: Long): String = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray()
        lines.forEach {
            arr.put(org.json.JSONObject().put("who", it.who).put("lang", it.lang).put("text", it.text).put("translation", it.translation))
        }
        val b = org.json.JSONObject().put("op", "save").put("lines", arr).put("mode", mode).put("seconds", seconds)
        org.json.JSONObject(post("/functions/v1/translate", b.toString(), slowHttp)).optString("summary")
    }

    // ── Push notifications (FCM): the phone registers its token; the cloud sends. ──
    suspend fun pushRegister(token: String, appId: String): Unit = withContext(Dispatchers.IO) {
        post("/functions/v1/push", org.json.JSONObject().put("op", "register").put("token", token).put("appId", appId).toString())
    }

    suspend fun pushUnregister(token: String): Unit = withContext(Dispatchers.IO) {
        post("/functions/v1/push", org.json.JSONObject().put("op", "unregister").put("token", token).toString())
    }

    /** Ask the cloud to send a test notification to this user's phones. Returns how many accepted it. */
    suspend fun pushTest(): Int = withContext(Dispatchers.IO) {
        val (code, txt) = postAuthed("/functions/v1/push", """{"op":"test"}""", http)
        val o = runCatching { org.json.JSONObject(txt) }.getOrElse { org.json.JSONObject() }
        if (code !in 200..299) error(o.optString("error").ifBlank { "HTTP $code" })
        o.optInt("sent")
    }

    /** People memory: structure "remember this person: …" into fields (nothing is saved here). */
    suspend fun personParse(text: String): PersonParse = withContext(Dispatchers.IO) {
        val o = org.json.JSONObject(post("/functions/v1/person", org.json.JSONObject().put("text", text).toString()))
        PersonParse(
            name = o.optString("name"),
            role = o.optString("role"),
            organisation = o.optString("organisation"),
            where = o.optString("where"),
            phone = o.optString("phone"),
            email = o.optString("email"),
            memoryText = o.optString("memoryText"),
            spoken = o.optString("spoken"),
        )
    }

    // ── Google: calendar + Gmail drafts in the cloud (Phase 1). The phone only ever sends Google's
    // one-time code; the server keeps the refresh token. Failures come back as a plain sentence. ──

    suspend fun googleStatus(): GoogleStatus = withContext(Dispatchers.IO) {
        val o = org.json.JSONObject(post("/functions/v1/google-connect", """{"op":"status"}"""))
        GoogleStatus(
            serverReady = o.optBoolean("configured"),
            connected = o.optBoolean("connected"),
            email = o.optString("email"),
            needsReconnect = o.optString("status") == "needs_reconnect",
        )
    }

    /** Hand Google's one-time server auth code to the cloud. Returns the linked Google email. */
    suspend fun googleConnect(serverAuthCode: String): String = withContext(Dispatchers.IO) {
        val (code, txt) = postAuthed("/functions/v1/google-connect", org.json.JSONObject().put("op", "connect").put("serverAuthCode", serverAuthCode).toString(), http)
        val o = runCatching { org.json.JSONObject(txt) }.getOrElse { org.json.JSONObject() }
        if (code !in 200..299) error(o.optString("error").ifBlank { "HTTP $code" })
        o.optString("email")
    }

    suspend fun googleDisconnect(): Unit = withContext(Dispatchers.IO) {
        post("/functions/v1/google-connect", """{"op":"disconnect"}""")
    }

    suspend fun calendarQuery(text: String) = googleLane("/functions/v1/calendar", org.json.JSONObject().put("op", "query").put("text", text))
    /** Step 1 of adding: the server works out the event and holds it; nothing is written yet. */
    suspend fun calendarPropose(text: String) = googleLane("/functions/v1/calendar", org.json.JSONObject().put("op", "propose").put("text", text))
    /** Step 2: create the event the server proposed (only works once, within 10 minutes). */
    suspend fun calendarConfirm(id: String) = googleLane("/functions/v1/calendar", org.json.JSONObject().put("op", "confirm").put("id", id))
    suspend fun calendarCancel(id: String) = googleLane("/functions/v1/calendar", org.json.JSONObject().put("op", "cancel").put("id", id))
    /** Draft (never send) an email in Gmail; the recipient is looked up in Google contacts. */
    suspend fun emailDraft(text: String) = googleLane("/functions/v1/email", org.json.JSONObject().put("op", "draft").put("text", text))

    private suspend fun googleLane(path: String, body: org.json.JSONObject): GoogleLaneResult = withContext(Dispatchers.IO) {
        val (code, txt) = postAuthed(path, body.toString(), slowHttp)
        val o = runCatching { org.json.JSONObject(txt) }.getOrElse { org.json.JSONObject() }
        if (code in 200..299) {
            val spoken = o.optString("spoken")
            GoogleLaneResult(true, o.optString("text").ifBlank { spoken }, spoken, o.optString("id"), needsMore = o.optBoolean("needsMore"))
        } else {
            val msg = o.optString("spoken").ifBlank { o.optString("error") }.ifBlank { "HTTP $code" }
            GoogleLaneResult(false, msg, msg, notConnected = o.optBoolean("notConnected"))
        }
    }

    /** POST with one token refresh on 401, returning (code, body) instead of throwing. */
    private fun postAuthed(path: String, body: String, client: OkHttpClient): Pair<Int, String> {
        var r = postOnce(path, body, client)
        if (r.first == 401 && refreshSession()) r = postOnce(path, body, client)
        return r
    }

    private fun get(path: String): String {
        var (code, txt) = getOnce(path)
        if (code == 401 && refreshSession()) { val r = getOnce(path); code = r.first; txt = r.second }
        if (code !in 200..299) error("HTTP $code: $txt")
        return txt
    }

    private fun getOnce(path: String): Pair<Int, String> {
        val builder = Request.Builder()
            .url(session.baseUrl + path)
            .addHeader("apikey", session.anonKey)
        session.accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }
        http.newCall(builder.get().build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun post(path: String, body: String, client: OkHttpClient = http): String {
        var (code, txt) = postOnce(path, body, client)
        // Expired access token: rotate via the refresh token and retry once.
        if (code == 401 && refreshSession()) {
            val retry = postOnce(path, body, client)
            code = retry.first; txt = retry.second
        }
        if (code !in 200..299) error("HTTP $code: $txt")
        return txt
    }

    private fun postOnce(path: String, body: String, client: OkHttpClient = http): Pair<Int, String> {
        val builder = Request.Builder()
            .url(session.baseUrl + path)
            .addHeader("apikey", session.anonKey)
        session.accessToken?.let { builder.addHeader("Authorization", "Bearer $it") }
        builder.post(body.toRequestBody(jsonMedia))
        client.newCall(builder.build()).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }

    companion object {
        /** Downscale a JPEG so its longest side ≤ [maxSide] (keeps EXIF rotation baked in). Returns the
         *  original bytes if it's already small or anything fails. */
        fun shrinkJpeg(jpeg: ByteArray, maxSide: Int, quality: Int): ByteArray = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0 || (longest <= maxSide && jpeg.size < 600_000)) return jpeg
            var sample = 1
            while (longest / (sample * 2) >= maxSide) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return jpeg
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            var out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            // Respect the camera's EXIF orientation (phone photos are often stored sideways).
            val rot = runCatching {
                when (androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg))
                    .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            }.getOrDefault(0f)
            if (rot != 0f) out = android.graphics.Bitmap.createBitmap(out, 0, 0, out.width, out.height, android.graphics.Matrix().apply { postRotate(rot) }, true)
            val bos = java.io.ByteArrayOutputStream()
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, bos)
            bos.toByteArray().takeIf { it.isNotEmpty() && it.size < jpeg.size } ?: jpeg
        }.getOrDefault(jpeg)

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

data class ResearchResult(
    val text: String,
    val spoken: String,
    val searches: Int,
    val durationMs: Long,
    val item: String = "",
    /** The key answer in a few words ("₦45,000 – ₦60,000") for the answer card's headline. */
    val headline: String = "",
    /** (title, url) of each source Claude actually cited. */
    val sources: List<Pair<String, String>> = emptyList(),
)
data class TranslateToken(val token: String, val model: String, val urls: List<String>)
data class PhraseResult(
    val langCode: String,
    val langName: String,
    val phrase: String,
    val translation: String,
    val romanization: String,
    val spoken: String,
)
/** One line of a translated conversation: [who] = "me" | "them"; [text] original, [translation] rendered. */
data class TranslatedLine(val who: String, val lang: String, val text: String, val translation: String)

