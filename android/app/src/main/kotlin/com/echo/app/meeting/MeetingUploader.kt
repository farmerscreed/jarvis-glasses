package com.echo.app.meeting

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echo.memory.EchoBackend
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** How far a recording has got to the cloud (from its `upload.json`). */
data class UploadState(val uploaded: Set<String> = emptySet(), val finished: Boolean = false, val v: Int = 0)

/**
 * Phase 2 · M2 — sends each finished ~5-minute chunk to the cloud while the meeting is still going, and
 * tells the `meeting` function when the recording is complete. The phone stays the source of truth: a chunk
 * counts as uploaded only after the server has checked its size + sha256. Progress lives in
 * `recordings/<id>/upload.json`, so it survives app kill; [MeetingUploadWorker] retries with no signal.
 * Deleting a recording on the phone also deletes the cloud copy (queued in `recordings/.deleted` if offline).
 */
@Singleton
class MeetingUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: MeetingRecorder,
    private val backend: EchoBackend,
) {
    private val lock = Mutex()

    fun state(dir: File): UploadState = runCatching {
        val o = JSONObject(File(dir, STATE).readText())
        val a = o.optJSONArray("uploaded") ?: JSONArray()
        UploadState((0 until a.length()).map { a.getString(it) }.toSet(), o.optBoolean("finished"), o.optInt("v"))
    }.getOrDefault(UploadState())

    private fun save(dir: File, s: UploadState) = runCatching {
        File(dir, STATE).writeText(JSONObject().put("uploaded", JSONArray(s.uploaded.sorted())).put("finished", s.finished).put("v", s.v).toString())
    }

    /** Remember to delete [id] from the cloud too, then try now. */
    fun queueCloudDelete(id: String) {
        runCatching { File(recorder.root(), DELETED).appendText("$id\n") }
        MeetingUploadWorker.enqueue(context)
    }

    /** Upload everything that's ready. Returns true when nothing is left to do (so the worker can stop). */
    suspend fun drain(): Boolean = lock.withLock {
        if (!backend.isLoggedIn) return true
        var allDone = drainDeletes()
        for (d in recorder.root().listFiles()?.filter { it.isDirectory }.orEmpty()) {
            if (!File(d, "meta.json").exists()) continue
            allDone = runCatching { drainOne(d) }.onFailure { Log.w(TAG, "upload ${d.name}: ${it.message}") }.getOrDefault(false) && allDone
        }
        allDone
    }

    private suspend fun drainOne(dir: File): Boolean {
        val id = dir.name
        var st = state(dir)
        // v < CLOUD_V: finished before the cloud could transcribe → send "finish" once more (it re-kicks the work).
        if (st.finished && st.v >= CLOUD_V) { cleanUpLocal(dir); return true }
        val meta = JSONObject(File(dir, "meta.json").readText())
        val open = recorder.openChunk(id)              // the file still being written (never upload it)
        val recording = recorder.isActive && recorder.ui.value.id == id
        val files = dir.listFiles { f -> f.extension == "m4a" && f.length() > 0 }?.sortedBy { it.name }.orEmpty()
        for (f in files) {
            if (f.name == open || f.name in st.uploaded) continue
            val bytes = f.readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val body = JSONObject()
                .put("op", "chunk").put("client_id", id).put("name", f.name).put("bytes", bytes.size).put("sha256", sha)
                .put("duration_s", durationS(f)).put("title", meta.optString("title")).put("started_at", meta.optLong("started_at"))
                .put("kind", meta.optString("kind", "meeting")).toString()
            var ok = false
            for (attempt in 1..2) {                    // 409 = the server saw different bytes → send again
                backend.uploadRecordingChunk(id, f.name, bytes)
                val (code, txt) = backend.meeting(body)
                if (code in 200..299) { ok = true; break }
                if (code != 409) error("chunk ${f.name}: HTTP $code ${txt.take(160)}")
            }
            if (!ok) error("chunk ${f.name}: checksum kept mismatching")
            st = st.copy(uploaded = st.uploaded + f.name)
            save(dir, st)
            Log.i(TAG, "uploaded $id/${f.name} (${bytes.size} B)")
        }
        if (recording) return true                      // up to date for now; the next chunk close enqueues again
        val names = files.map { it.name }.toSet()
        if (!st.uploaded.containsAll(names)) return false
        if (names.isEmpty()) { save(dir, st.copy(finished = true, v = CLOUD_V)); return true }
        val (code, txt) = backend.meeting(
            JSONObject().put("op", "finish").put("client_id", id).put("chunks_total", names.size)
                .put("duration_s", meta.optInt("duration_s")).put("title", meta.optString("title"))
                .put("ended_at", dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis())
                .put("bookmarks", meta.optJSONArray("bookmarks") ?: JSONArray()).toString(),
        )
        if (code !in 200..299) error("finish: HTTP $code ${txt.take(160)}")
        save(dir, st.copy(finished = true, v = CLOUD_V))
        Log.i(TAG, "recording $id complete in the cloud: $txt")
        return true
    }

    /**
     * M6: once every piece is confirmed in the cloud (size + sha256) and the meeting is more than
     * [KEEP_LOCAL_DAYS] old, remove the phone's copy of the audio. meta.json stays, so the library still lists
     * it; playback then streams from the cloud (until the cloud audio expires — the text stays forever).
     */
    private fun cleanUpLocal(dir: File) {
        val audio = dir.listFiles { f -> f.extension == "m4a" }.orEmpty()
        if (audio.isEmpty()) return
        val st = state(dir)
        if (!audio.all { it.name in st.uploaded }) return
        val started = runCatching { JSONObject(File(dir, "meta.json").readText()).optLong("started_at") }.getOrDefault(0L)
        if (started <= 0 || System.currentTimeMillis() - started < KEEP_LOCAL_DAYS * 86_400_000L) return
        audio.forEach { it.delete() }
        Log.i(TAG, "freed ${audio.size} local audio piece(s) of ${dir.name} (backed up, older than $KEEP_LOCAL_DAYS days)")
    }

    private suspend fun drainDeletes(): Boolean {
        val f = File(recorder.root(), DELETED)
        val ids = runCatching { f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.distinct() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return true
        val left = ids.filterNot { id ->
            runCatching { backend.meeting(JSONObject().put("op", "delete").put("client_id", id).toString()).first in 200..299 }.getOrDefault(false)
        }
        runCatching { if (left.isEmpty()) f.delete() else f.writeText(left.joinToString("\n", postfix = "\n")) }
        return left.isEmpty()
    }

    private fun durationS(f: File): Int = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(f.absolutePath)
            ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
        }
    }.getOrDefault(0)

    companion object {
        private const val TAG = "EchoMeeting"
        const val STATE = "upload.json"
        /** Bump when the cloud learns to do more with a finished recording (3 = M3 transcription). */
        private const val CLOUD_V = 3
        /** The phone keeps the newest week of meeting audio for offline playback. */
        private const val KEEP_LOCAL_DAYS = 7
        private const val DELETED = ".deleted"
    }
}

/** Runs [MeetingUploader.drain] whenever there's a network; retries (with back-off) until it's all up. */
class MeetingUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UploaderEntryPoint { fun meetingUploader(): MeetingUploader }

    override suspend fun doWork(): Result {
        val up = EntryPointAccessors.fromApplication(applicationContext, UploaderEntryPoint::class.java).meetingUploader()
        return try {
            if (up.drain()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("EchoMeeting", "upload worker: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "echo-meeting-upload"

        /** Call when a chunk closes, a recording stops, or one is deleted. */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<MeetingUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // APPEND_OR_REPLACE: a run already going still gets a follow-up pass for the chunk that just closed.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
