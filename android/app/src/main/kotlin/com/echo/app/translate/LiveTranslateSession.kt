package com.echo.app.translate

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One direction of live speech translation: a WebSocket to Gemini Live Translate
 * (`gemini-3.5-live-translate-preview`). Audio in = 16 kHz PCM16 mono (100 ms chunks); audio out =
 * 24 kHz PCM16 mono, plus input/output transcripts with language codes.
 *
 * The session is authorized by a single-use ephemeral token minted by our `translate` Edge
 * Function, with the whole config (model, target language, echo=false, transcription) LOCKED
 * server-side — so the client only sends `{setup:{model}}`. Protocol verified against the live API
 * on 2026-10-09 (see supabase/functions/translate): v1beta `BidiGenerateContentConstrained`,
 * `serverContent.{inputTranscription,outputTranscription,modelTurn.parts[].inlineData}`; no
 * `turnComplete` is sent for translation turns, so callers segment lines on silence gaps.
 *
 * [urls] are tried in order until one reaches `setupComplete` (served by the backend so a Google
 * endpoint change doesn't need an app update).
 */
class LiveTranslateSession(
    private val http: OkHttpClient,
    val target: String,
    private val token: String,
    private val model: String,
    private val urls: List<String>,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady(session: LiveTranslateSession)
        fun onAudio(session: LiveTranslateSession, pcm24k: ByteArray)
        fun onInputText(session: LiveTranslateSession, text: String, lang: String)
        fun onOutputText(session: LiveTranslateSession, text: String, lang: String)
        /** Closed for good (error = true if it never connected or dropped unexpectedly). */
        fun onClosed(session: LiveTranslateSession, reason: String, error: Boolean)
    }

    private var ws: WebSocket? = null
    private val ready = AtomicBoolean(false)
    private val closedByUs = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    @Volatile var lastError: String = ""; private set

    val isReady: Boolean get() = ready.get()

    fun connect() = tryUrl(0)

    private fun tryUrl(i: Int) {
        if (closedByUs.get()) return
        if (i >= urls.size) { finish("couldn't connect: $lastError", true); return }
        val url = urls[i] + java.net.URLEncoder.encode(token, "UTF-8")
        Log.i(TAG, "[$target] connecting via url#$i")
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("setup", JSONObject().put("model", model)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lastError = "closed $code $reason"
                Log.i(TAG, "[$target] closed $code $reason (ready=${ready.get()})")
                if (!ready.get() && !closedByUs.get()) tryUrl(i + 1) // never got going → next URL
                else finish(reason.ifBlank { "closed ($code)" }, !closedByUs.get())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "[$target] failure: $lastError (ready=${ready.get()})")
                if (!ready.get() && !closedByUs.get()) tryUrl(i + 1)
                else finish(lastError, !closedByUs.get())
            }
        })
    }

    private fun handle(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (m.has("setupComplete")) {
            if (ready.compareAndSet(false, true)) listener.onReady(this)
            return
        }
        if (m.has("goAway")) Log.i(TAG, "[$target] goAway: ${m.optJSONObject("goAway")}")
        val sc = m.optJSONObject("serverContent") ?: return
        sc.optJSONObject("inputTranscription")?.let { t ->
            val s = t.optString("text"); if (s.isNotEmpty()) listener.onInputText(this, s, t.optString("languageCode"))
        }
        sc.optJSONObject("outputTranscription")?.let { t ->
            val s = t.optString("text"); if (s.isNotEmpty()) listener.onOutputText(this, s, t.optString("languageCode"))
        }
        val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
            if (data.isNotEmpty()) listener.onAudio(this, Base64.decode(data, Base64.DEFAULT))
        }
    }

    /** Send one chunk of 16 kHz PCM16 mono audio (ideally ~100 ms = 3200 bytes). */
    fun sendPcm16k(pcm: ByteArray, len: Int = pcm.size) {
        if (!ready.get()) return
        val b64 = Base64.encodeToString(pcm, 0, len, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
    }

    fun close() {
        if (!closedByUs.compareAndSet(false, true)) return
        runCatching { ws?.close(1000, "done") }
        finish("stopped", false)
    }

    private fun finish(reason: String, error: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        ready.set(false)
        listener.onClosed(this, reason, error)
    }

    companion object { const val TAG = "EchoTranslate" }
}
