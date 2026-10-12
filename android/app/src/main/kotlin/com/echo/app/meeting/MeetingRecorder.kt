package com.echo.app.meeting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class Bookmark(val tS: Int, val label: String)

/** What the meeting screens show. */
data class MeetingUi(
    val active: Boolean = false,
    val paused: Boolean = false,
    val id: String = "",
    val title: String = "",
    val startedAt: Long = 0,
    val elapsedMs: Long = 0,
    val chunks: Int = 0,
    val bytes: Long = 0,
    /** 0..1 loudness right now (for the meter). */
    val level: Float = 0f,
    val bookmarks: List<Bookmark> = emptyList(),
    val error: String = "",
    /** Set after a stop: the recording that was just saved. */
    val lastSaved: SavedRecording? = null,
)

data class SavedRecording(
    val id: String,
    val title: String,
    val durationS: Int,
    val chunks: Int,
    val bytes: Long,
    val dir: File,
    val startedAt: Long = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    /** Chunks the cloud has confirmed (size + sha256), and whether the whole recording is up. */
    val uploadedChunks: Int = 0,
    val inCloud: Boolean = false,
) {
    /** The playable pieces, in order. */
    fun files(): List<File> = dir.listFiles { f -> f.extension == "m4a" && f.length() > 0 }?.sortedBy { it.name }.orEmpty()
}

/**
 * Phase 2 · M1 — records a meeting with the PHONE's microphone (on the table it hears the whole room; the
 * glasses mic is tuned to the wearer). AAC 32 kbps mono 16 kHz in ~5-minute MPEG-4 CHUNKS (≈14 MB/hour):
 * each finished chunk is a complete, playable file, so a crash or kill never loses more than the chunk in
 * progress, and later milestones upload + transcribe chunks while the meeting is still going.
 *
 * Files: `filesDir/recordings/<id>/<seq>.m4a` + `meta.json` (title, start/end, chunks, bookmarks).
 * Keeps running with the screen off via [MeetingRecorderService] (foreground service, type microphone).
 * See docs/PHASE2_DESIGN.md §4.
 */
@Singleton
class MeetingRecorder @Inject constructor(@ApplicationContext private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(MeetingUi())
    val ui: StateFlow<MeetingUi> = _ui

    private var recorder: MediaRecorder? = null
    private var dir: File? = null
    private var seq = 0
    private var tickJob: Job? = null
    private var runStartedAt = 0L      // when the current un-paused stretch began
    private var accumulatedMs = 0L     // recorded time before the current stretch
    @Volatile private var lastMarkAt = 0L // the notification shows "Marked at…" for a few seconds
    @Volatile private var openName: String? = null // the chunk file still being written

    /** The chunk of [id] that is still being written (null when it isn't recording) — never upload it. */
    fun openChunk(id: String): String? = if (_ui.value.active && _ui.value.id == id) openName else null

    val isActive: Boolean get() = _ui.value.active

    fun root(): File = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Start recording. Returns false (with [MeetingUi.error] set) if it can't. */
    fun start(title: String): Boolean {
        if (_ui.value.active) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _ui.value = _ui.value.copy(error = "JARVIS needs the microphone permission to record.")
            return false
        }
        val id = "rec-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().take(6)
        val d = File(root(), id).apply { mkdirs() }
        dir = d
        seq = 0
        val now = System.currentTimeMillis()
        _ui.value = MeetingUi(active = true, id = id, title = title.trim(), startedAt = now)
        // Foreground service first: Android only lets us keep the mic with the screen off from one.
        if (!MeetingRecorderService.start(context)) {
            _ui.value = MeetingUi(error = "Please unlock your phone and start the recording from the app.")
            d.deleteRecursively()
            return false
        }
        return try {
            recorder = newRecorder(nextFile().also { openName = it.name }).also { it.start() }
            runStartedAt = now; accumulatedMs = 0
            writeMeta(ended = false)
            startTicker()
            Log.i(TAG, "recording $id started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            cleanupRecorder()
            MeetingRecorderService.stop(context)
            _ui.value = MeetingUi(error = "Couldn't start recording (${e.message?.take(80)}).")
            false
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (_ui.value.paused || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        runCatching { r.pause() }.onSuccess {
            accumulatedMs += System.currentTimeMillis() - runStartedAt
            _ui.value = _ui.value.copy(paused = true, level = 0f)
            MeetingRecorderService.update(context, "Paused · ${clock(elapsedMs())}")
        }
    }

    fun resume() {
        val r = recorder ?: return
        if (!_ui.value.paused) return
        runCatching { r.resume() }.onSuccess {
            runStartedAt = System.currentTimeMillis()
            _ui.value = _ui.value.copy(paused = false)
        }
    }

    /** Bookmark this moment ("mark that — price agreed"). */
    fun mark(label: String = "") {
        if (!_ui.value.active) return
        val b = Bookmark((elapsedMs() / 1000).toInt(), label.trim())
        _ui.value = _ui.value.copy(bookmarks = _ui.value.bookmarks + b)
        writeMeta(ended = false)
        Log.i(TAG, "marked at ${b.tS}s ${b.label}")
        lastMarkAt = System.currentTimeMillis()
        // Visible confirmation (the notification is often where Mark was tapped).
        MeetingRecorderService.update(context, "Marked at ${clock(b.tS * 1000L)} · recording ${clock(elapsedMs())}")
    }

    /** Stop and keep everything. Returns what was saved (null if nothing was recording). */
    fun stop(): SavedRecording? {
        if (!_ui.value.active) return null
        tickJob?.cancel(); tickJob = null
        val dur = elapsedMs()
        runCatching { recorder?.stop() }.onFailure { Log.w(TAG, "stop: ${it.message}") }
        cleanupRecorder()
        MeetingRecorderService.stop(context)
        openName = null
        MeetingUploadWorker.enqueue(context)
        val d = dir ?: return null
        val files = chunkFiles(d)
        val saved = SavedRecording(_ui.value.id, _ui.value.title, (dur / 1000).toInt(), files.size, files.sumOf { it.length() }, d)
        _ui.value = _ui.value.copy(active = false, paused = false, elapsedMs = dur, chunks = files.size, bytes = saved.bytes, level = 0f)
        writeMeta(ended = true)
        _ui.value = MeetingUi(lastSaved = saved)
        Log.i(TAG, "recording ${saved.id} saved: ${saved.durationS}s, ${saved.chunks} chunks, ${saved.bytes} bytes")
        return saved
    }

    /** The service couldn't go foreground (phone locked, app not visible). */
    fun onBackgroundRefused() {
        if (!_ui.value.active) return
        tickJob?.cancel()
        runCatching { recorder?.stop() }
        cleanupRecorder()
        dir?.deleteRecursively()
        _ui.value = MeetingUi(error = "Please unlock your phone and start the recording from the app.")
    }

    fun clearSaved() { _ui.value = _ui.value.copy(lastSaved = null, error = "") }

    fun elapsedMs(): Long = if (!_ui.value.active) _ui.value.elapsedMs
    else accumulatedMs + if (_ui.value.paused) 0 else System.currentTimeMillis() - runStartedAt

    /** Every recording on this phone, newest first (read from each folder's meta.json). */
    fun saved(): List<SavedRecording> = root().listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
        val meta = runCatching { JSONObject(File(d, "meta.json").readText()) }.getOrNull() ?: return@mapNotNull null
        val files = chunkFiles(d)
        val marks = meta.optJSONArray("bookmarks")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Bookmark(it.optInt("t_s"), it.optString("label")) } } }.orEmpty()
        val up = runCatching { JSONObject(File(d, MeetingUploader.STATE).readText()) }.getOrNull()
        SavedRecording(
            d.name, meta.optString("title"), meta.optInt("duration_s"), files.size, files.sumOf { it.length() }, d, meta.optLong("started_at"), marks,
            uploadedChunks = up?.optJSONArray("uploaded")?.length() ?: 0, inCloud = up?.optBoolean("finished") == true,
        )
    }?.sortedByDescending { it.startedAt }.orEmpty()

    /** Delete a recording from this phone (not the one in progress). */
    fun delete(id: String): Boolean {
        if (_ui.value.active && _ui.value.id == id) return false
        return File(root(), id).takeIf { it.isDirectory }?.deleteRecursively() ?: false
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────────

    private fun nextFile(): File = File(dir, "%03d.m4a".format(seq++))

    private fun chunkFiles(d: File) = d.listFiles { f -> f.extension == "m4a" && f.length() > 0 }?.sortedBy { it.name }.orEmpty()

    @Suppress("DEPRECATION")
    private fun newRecorder(first: File): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC) // normal phone processing (gain) — distant voices stay audible
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(16_000)
        r.setAudioEncodingBitRate(BITRATE)
        r.setMaxFileSize(CHUNK_BYTES)
        r.setOutputFile(first.absolutePath)
        // Prefer the phone's own mic even when the glasses are connected for sound.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { runCatching { r.setPreferredDevice(it) } }
        }
        r.setOnInfoListener { mr, what, _ ->
            when (what) {
                // ~5 minutes in: hand the recorder the next file; it switches seamlessly.
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { mr.setNextOutputFile(nextFile()) }.onFailure { Log.e(TAG, "next file", it) }
                MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {
                    // The previous chunk is now closed and complete → send it to the cloud.
                    openName = "%03d.m4a".format(seq - 1)
                    Log.i(TAG, "chunk ${seq - 1} started")
                    writeMeta(ended = false)
                    MeetingUploadWorker.enqueue(context)
                }
                MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> { Log.w(TAG, "max size reached without next file — restarting"); restartAfterError() }
            }
        }
        r.setOnErrorListener { _, what, extra -> Log.e(TAG, "recorder error $what/$extra — restarting"); restartAfterError() }
        r.prepare()
        return r
    }

    /** Keep going after a recorder hiccup: close what we have, open a fresh chunk. */
    private fun restartAfterError() {
        if (!_ui.value.active) return
        scope.launch {
            runCatching { recorder?.stop() }
            cleanupRecorder()
            accumulatedMs = elapsedMs(); runStartedAt = System.currentTimeMillis()
            recorder = runCatching { newRecorder(nextFile().also { openName = it.name }).also { it.start() } }.getOrNull()
            MeetingUploadWorker.enqueue(context)
            if (recorder == null) _ui.value = _ui.value.copy(error = "The recording stopped unexpectedly — what was recorded so far is saved.")
        }
    }

    private fun cleanupRecorder() {
        runCatching { recorder?.reset() }; runCatching { recorder?.release() }; recorder = null
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            var n = 0
            while (isActive && _ui.value.active) {
                val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val d = dir
                val files = if (n % 10 == 0 && d != null) chunkFiles(d) else null
                _ui.value = _ui.value.copy(
                    elapsedMs = elapsedMs(),
                    level = if (_ui.value.paused) 0f else (amp / 20_000f).coerceIn(0f, 1f),
                    chunks = files?.size ?: _ui.value.chunks,
                    bytes = files?.sumOf { it.length() } ?: _ui.value.bytes,
                )
                val markShowing = System.currentTimeMillis() - lastMarkAt < 5_000
                if (n % 5 == 0 && !_ui.value.paused && !markShowing) MeetingRecorderService.update(context, "Recording · ${clock(_ui.value.elapsedMs)}")
                n++
                delay(200)
            }
        }
    }

    private fun writeMeta(ended: Boolean) {
        val d = dir ?: return
        val u = _ui.value
        runCatching {
            val o = JSONObject()
                .put("id", u.id).put("title", u.title).put("source", "phone").put("kind", "meeting")
                .put("started_at", u.startedAt).put("ended", ended).put("duration_s", (elapsedMs() / 1000).toInt())
                .put("bitrate", BITRATE).put("chunk_bytes", CHUNK_BYTES)
                .put("bookmarks", JSONArray().apply { u.bookmarks.forEach { put(JSONObject().put("t_s", it.tS).put("label", it.label)) } })
            File(d, "meta.json").writeText(o.toString(2))
        }
    }

    companion object {
        private const val TAG = "EchoMeeting"
        const val BITRATE = 32_000
        /** ≈5 minutes at [BITRATE]. */
        const val CHUNK_BYTES = BITRATE / 8L * 300

        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** Set when the recording notification is tapped; AppRoot opens the meeting screen and clears it. */
object MeetingNav {
    var openRequested by androidx.compose.runtime.mutableStateOf(false)
    /** A recording to open in Memories → Recordings (from a "summary ready" notification). */
    var openRecording by androidx.compose.runtime.mutableStateOf<String?>(null)
}
