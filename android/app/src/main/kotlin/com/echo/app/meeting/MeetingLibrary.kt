package com.echo.app.meeting

import com.echo.memory.EchoBackend
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A recording as the cloud knows it (Phase 2 · M5). Times are epoch ms. */
data class CloudRec(
    val clientId: String,
    val title: String,
    val startedAt: Long,
    val durationS: Int,
    val status: String,
    val chunksTotal: Int?,
    val bookmarks: List<Bookmark>,
    val speakers: Map<String, String>,
    val summary: JSONObject?,
    val actionDone: Set<Int>,
    val audioBytes: Long,
    val starred: Boolean,
    val audioExpiresAt: Long?,
    val audioDeleted: Boolean,
    val error: String,
    val source: String,
) {
    val isMeeting: Boolean get() = summary?.optBoolean("is_meeting", true) ?: true
    fun list(name: String): List<Any> = summary?.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.get(it) } }.orEmpty()
    val actionCount: Int get() = list("action_items").size
    val decisionCount: Int get() = list("decisions").size
    val inShort: String get() = summary?.optString("in_short").orEmpty()
}

/** One transcript line on the meeting's timeline (seconds from the start). */
data class TLine(val t: Double, val end: Double, val speaker: String, val text: String)

data class MeetingDetail(val rec: CloudRec, val lines: List<TLine>, val chunkSeconds: List<Int>)

data class LibraryInfo(val keepDays: Int = 90, val starred: Int = 0, val audioBytes: Long = 0)

/** A library entry: what's on this phone and/or in the cloud, matched by the recording id. */
data class LibraryItem(val id: String, val local: SavedRecording?, val cloud: CloudRec?) {
    val title: String get() = cloud?.title?.takeIf { it.isNotBlank() } ?: local?.title?.takeIf { it.isNotBlank() } ?: "Meeting"
    val startedAt: Long get() = cloud?.startedAt?.takeIf { it > 0 } ?: local?.startedAt ?: 0
    val durationS: Int get() = cloud?.durationS?.takeIf { it > 0 } ?: local?.durationS ?: 0
    val hasLocalAudio: Boolean get() = (local?.chunks ?: 0) > 0
    val bookmarks: List<Bookmark> get() = cloud?.bookmarks?.takeIf { it.isNotEmpty() } ?: local?.bookmarks.orEmpty()
}

/** The phone's side of the `meeting` function's library ops. Every call throws on failure. */
@Singleton
class MeetingLibrary @Inject constructor(private val backend: EchoBackend) {

    private suspend fun call(body: JSONObject): JSONObject {
        val (code, txt) = backend.meeting(body.toString())
        val o = runCatching { JSONObject(txt) }.getOrElse { JSONObject() }
        if (code !in 200..299) error(o.optString("error").ifBlank { "HTTP $code" })
        return o
    }

    private fun op(name: String, id: String? = null) = JSONObject().put("op", name).apply { id?.let { put("client_id", it) } }

    suspend fun list(): Pair<List<CloudRec>, LibraryInfo> {
        val o = call(op("list"))
        val a = o.optJSONArray("recordings") ?: JSONArray()
        return (0 until a.length()).map { parse(a.getJSONObject(it)) } to
            LibraryInfo(o.optInt("keep_days", 90), o.optInt("starred"), o.optLong("audio_bytes"))
    }

    suspend fun get(id: String): MeetingDetail {
        val o = call(op("get", id))
        val la = o.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until la.length()).map { la.getJSONObject(it) }.map {
            TLine(it.optDouble("t", 0.0), it.optDouble("end", 0.0), it.optString("speaker"), it.optString("text"))
        }
        val ca = o.optJSONArray("chunks") ?: JSONArray()
        return MeetingDetail(parse(o.getJSONObject("recording")), lines, (0 until ca.length()).map { ca.getJSONObject(it).optInt("duration_s") })
    }

    /** Signed links (1 h) to each piece of audio, for when it's no longer on this phone. Empty if deleted. */
    suspend fun audioUrls(id: String): List<Pair<String, Int>> {
        val a = call(op("audio-urls", id)).optJSONArray("urls") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url").isNotBlank() }
            .map { it.optString("url") to it.optInt("duration_s") }
    }

    suspend fun star(id: String, starred: Boolean) { call(op("star", id).put("starred", starred)) }
    suspend fun rename(id: String, title: String) { call(op("title", id).put("title", title)) }
    suspend fun renameSpeaker(id: String, label: String, name: String) { call(op("speaker", id).put("label", label).put("name", name)) }
    suspend fun tick(id: String, index: Int, done: Boolean) { call(op("tick", id).put("index", index).put("done", done)) }
    suspend fun deleteAudio(id: String) { call(op("delete-audio", id)) }

    /** Ask about this meeting → the answer and up to 3 supporting moments (seconds, quote). */
    suspend fun ask(id: String, question: String): Pair<String, List<Pair<Double, String>>> {
        val o = call(op("ask", id).put("question", question))
        val m = o.optJSONArray("moments") ?: JSONArray()
        return o.optString("answer") to (0 until m.length()).map { m.getJSONObject(it) }.map { it.optDouble("t", 0.0) to it.optString("quote") }
    }

    /** Read (null) or change how long meeting audio is kept: 30 / 90 / 365, 0 = forever. */
    suspend fun keepDays(set: Int? = null): Int =
        call(op("settings").apply { set?.let { put("audio_keep_days", it) } }).optInt("audio_keep_days", 90)

    suspend fun costs(): JSONObject = call(op("costs"))

    companion object {
        fun parse(o: JSONObject): CloudRec {
            val bm = o.optJSONArray("bookmarks") ?: JSONArray()
            val sp = o.optJSONObject("speakers") ?: JSONObject()
            val done = o.optJSONArray("action_done") ?: JSONArray()
            return CloudRec(
                clientId = o.optString("client_id"),
                title = o.optString("title"),
                startedAt = ts(o.optString("started_at")) ?: 0,
                durationS = o.optInt("duration_s"),
                status = o.optString("status"),
                chunksTotal = if (o.isNull("chunks_total")) null else o.optInt("chunks_total"),
                bookmarks = (0 until bm.length()).map { bm.getJSONObject(it) }.map { Bookmark(it.optInt("t_s"), it.optString("label")) },
                speakers = sp.keys().asSequence().associateWith { sp.optString(it) },
                summary = o.optJSONObject("summary"),
                actionDone = (0 until done.length()).map { done.optInt(it) }.toSet(),
                audioBytes = o.optLong("audio_bytes"),
                starred = o.optBoolean("starred"),
                audioExpiresAt = ts(o.optString("audio_expires_at")),
                audioDeleted = !o.isNull("audio_deleted_at") && o.optString("audio_deleted_at").isNotBlank(),
                error = if (o.isNull("error")) "" else o.optString("error"),
                source = o.optString("source", "phone"),
            )
        }

        private fun ts(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let { v ->
            runCatching { Instant.parse(v).toEpochMilli() }
                .recoverCatching { java.time.OffsetDateTime.parse(v).toInstant().toEpochMilli() }
                .getOrNull()
        }
    }
}
