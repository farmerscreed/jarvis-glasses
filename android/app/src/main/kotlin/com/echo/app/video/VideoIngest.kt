package com.echo.app.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.echo.app.meeting.MeetingRecorder
import com.echo.app.meeting.MeetingUploadWorker
import com.echo.app.meeting.MeetingUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 3 · V2 (docs/PHASE3_VIDEO_DESIGN.md §3). A video from the glasses or the phone camera becomes a
 * recording of kind `video` (or `walk`): the ORIGINAL stays on the phone (path in meta.json) and a small
 * ANALYSIS COPY — 480p, ~0.7 Mbps, mono AAC, in ≤ 3-minute pieces `NNN.mp4` — goes through the existing
 * chunk upload (MeetingUploader → `meeting` op chunk). ~5 MB per minute instead of the original's 30–100 MB.
 */
@Singleton
class VideoIngest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recorder: MeetingRecorder,
) {
    private val lock = Mutex() // one transcode at a time (the hardware encoder is shared)

    /**
     * Register [original] and make its analysis copy. [startedAt] = when it was filmed (epoch ms; from the
     * glasses' file name when possible). Returns the recording id, or null if the file can't be read.
     */
    suspend fun ingest(original: File, source: String, kind: String? = null, title: String = "", startedAt: Long = startedAtOf(original)): String? = lock.withLock {
        val durationMs = withContext(Dispatchers.IO) { durationOf(original) }
        if (durationMs <= 0) { Log.w(TAG, "can't read ${original.name}"); return null }
        // One recording per original (a re-sync of the same file doesn't make a second one).
        recorder.saved().firstOrNull { runCatching { JSONObject(File(it.dir, "meta.json").readText()).optString("original") }.getOrNull() == original.absolutePath }
            ?.let { return it.id }
        // No trustworthy time in the name → it started (about) its length before it reached the phone.
        @Suppress("NAME_SHADOWING")
        val startedAt = if (startedAt == original.lastModified()) original.lastModified() - durationMs else startedAt
        // A glasses video filmed during a site walk becomes a walk: the walk's time window, or "a walk was filmed on
        // the glasses and this is the next glasses video" (the glasses' clock can be months off).
        val kind = kind ?: if (VideoPrefs.inWalk(context, startedAt) || (source == "glasses" && VideoPrefs.takePending(context, "walk"))) "walk" else "video"
        val id = "rec-$startedAt-" + UUID.randomUUID().toString().take(6)
        // Build it in a hidden folder (no meta.json → the uploader ignores it) and move it into place only when
        // every piece is written. Built in place, the uploader once sent a half-written piece (107 KB of 943 KB)
        // — and could even mark a recording with no pieces yet as finished.
        recorder.root().listFiles { f -> f.name.startsWith(".ingest-") }?.forEach { it.deleteRecursively() }
        val dir = File(recorder.root(), ".ingest-$id").apply { mkdirs() }
        val pieces = ((durationMs + PIECE_MS - 1) / PIECE_MS).toInt().coerceAtLeast(1)
        Log.i(TAG, "ingest $id: ${original.name} ${durationMs / 1000}s, ${original.length() / 1_000_000} MB → $pieces piece(s)")
        for (i in 0 until pieces) {
            val out = File(dir, "%03d.mp4".format(i))
            val startMs = i * PIECE_MS
            val endMs = minOf(durationMs, startMs + PIECE_MS)
            runCatching { transcode(original, out, startMs, endMs) }
                .onFailure { Log.e(TAG, "transcode piece $i of $id failed", it); out.delete() }
        }
        val made = dir.listFiles { f -> f.extension == "mp4" && f.length() > 0 }?.size ?: 0
        Log.i(TAG, "ingest $id: $made/$pieces piece(s) ready, ${dir.listFiles()?.filter { it.extension == "mp4" }?.sumOf { it.length() }?.div(1000)} KB")
        if (made == 0) { dir.deleteRecursively(); return null }
        writeMeta(dir, id, title, source, kind, startedAt, (durationMs / 1000).toInt(), original)
        if (!dir.renameTo(File(recorder.root(), id))) { Log.e(TAG, "ingest $id: couldn't move into place"); dir.deleteRecursively(); return null }
        MeetingUploadWorker.enqueue(context)
        id
    }

    /**
     * Ingest glasses videos from the last [days] days that have no recording yet (or only a broken one: marked
     * finished in the cloud with no piece uploaded). Older videos are left alone — they predate the video pipeline.
     */
    suspend fun catchUp(media: List<File>, days: Int = 3) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val byOriginal = withContext(Dispatchers.IO) { // file reads: never on the main thread (it runs at app start)
            recorder.saved().associateBy { runCatching { JSONObject(File(it.dir, "meta.json").readText()).optString("original") }.getOrDefault("") }
        }
        val phone = File(context.filesDir, "videos").listFiles()?.toList().orEmpty() // the phone camera's videos (V5)
        for (f in (media + phone).filter { it.extension.lowercase() == "mp4" && it.lastModified() >= cutoff }.sortedBy { it.lastModified() }) {
            val rec = byOriginal[f.absolutePath]
            if (rec != null) {
                val up = runCatching { JSONObject(File(rec.dir, MeetingUploader.STATE).readText()) }.getOrNull()
                val broken = up != null && up.optBoolean("finished") && (up.optJSONArray("uploaded")?.length() ?: 0) == 0
                if (!broken) continue
                Log.i(TAG, "catch-up: ${rec.id} was finished with nothing uploaded — making it again")
                rec.dir.deleteRecursively()
            }
            ingest(f, source = if (f.parentFile?.name == "videos") "phone" else "glasses")
        }
    }

    /** M8: is this glasses audio file a meeting? (One started from the app, or anything 2 minutes or longer.) */
    fun isGlassesMeeting(f: File): Boolean =
        VideoPrefs.takePending(context, "meeting") || durationOf(f) >= MEETING_MIN_MS

    /**
     * M8: a MEETING recorded by the glasses (their own audio recorder: hold BACK, or Record → Meeting → Glasses mic).
     * The .opus file becomes AAC pieces of ≤ 5 min (`NNN.m4a`, like a phone meeting) → the meetings pipeline
     * (transcript, notes, memory, push). Built off to the side and moved into place when complete, as for video.
     * Its start time is when it reached the phone minus its length (the glasses' clock can't be trusted).
     */
    suspend fun ingestAudio(original: File, title: String = ""): String? = lock.withLock {
        val durationMs = withContext(Dispatchers.IO) { durationOf(original) }
        if (durationMs <= 0) { Log.w(TAG, "can't read ${original.name}"); return null }
        recorder.saved().firstOrNull { runCatching { JSONObject(File(it.dir, "meta.json").readText()).optString("original") }.getOrNull() == original.absolutePath }
            ?.let { return it.id }
        val named = startedAt(original.nameWithoutExtension, original.lastModified())
        val startedAt = if (named == original.lastModified()) original.lastModified() - durationMs else named
        val id = "rec-$startedAt-" + UUID.randomUUID().toString().take(6)
        recorder.root().listFiles { f -> f.name.startsWith(".ingest-") }?.forEach { it.deleteRecursively() }
        val dir = File(recorder.root(), ".ingest-$id").apply { mkdirs() }
        val pieces = ((durationMs + AUDIO_PIECE_MS - 1) / AUDIO_PIECE_MS).toInt().coerceAtLeast(1)
        Log.i(TAG, "ingest audio $id: ${original.name} ${durationMs / 1000}s → $pieces piece(s)")
        for (i in 0 until pieces) {
            val out = File(dir, "%03d.m4a".format(i))
            val startMs = i * AUDIO_PIECE_MS
            runCatching { transcodeAudio(original, out, startMs, minOf(durationMs, startMs + AUDIO_PIECE_MS)) }
                .onFailure { Log.e(TAG, "audio piece $i of $id failed", it); out.delete() }
        }
        val made = dir.listFiles { f -> f.extension == "m4a" && f.length() > 0 }?.size ?: 0
        Log.i(TAG, "ingest audio $id: $made/$pieces piece(s), ${dir.listFiles()?.sumOf { it.length() }?.div(1000)} KB")
        if (made == 0) { dir.deleteRecursively(); return null }
        writeMeta(dir, id, title, "glasses", "meeting", startedAt, (durationMs / 1000).toInt(), original)
        if (!dir.renameTo(File(recorder.root(), id))) { dir.deleteRecursively(); return null }
        MeetingUploadWorker.enqueue(context)
        id
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcodeAudio(input: File, output: File, startMs: Long, endMs: Long) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(input))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                .build()
            val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(edited, output.absolutePath)
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        }
    }

    /** V7: a small copy of the whole video (480p) for sharing on slow data — into [out]. Same encoder lock as ingest. */
    suspend fun makeSmallCopy(original: File, out: File): Boolean = lock.withLock {
        val durationMs = withContext(Dispatchers.IO) { durationOf(original) }
        if (durationMs <= 0) return false
        out.parentFile?.mkdirs(); out.delete()
        runCatching { transcode(original, out, 0, durationMs) }.onFailure { Log.e(TAG, "small copy failed", it); out.delete() }.isSuccess && out.length() > 0
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcode(input: File, output: File, startMs: Long, endMs: Long) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(input))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                .build()
            val edited = EditedMediaItem.Builder(item)
                .setEffects(Effects(listOf(), listOf(Presentation.createForHeight(HEIGHT))))
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(VIDEO_BPS).build())
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(edited, output.absolutePath)
            cont.invokeOnCancellation { runCatching { transformer.cancel() } }
        }
    }

    private fun writeMeta(dir: File, id: String, title: String, source: String, kind: String, startedAt: Long, durationS: Int, original: File) {
        File(dir, "meta.json").writeText(
            JSONObject()
                .put("id", id).put("title", title).put("source", source).put("kind", kind)
                .put("started_at", startedAt).put("ended", true).put("duration_s", durationS)
                .put("original", original.absolutePath).put("bookmarks", JSONArray())
                .toString(2),
        )
    }

    companion object {
        private const val TAG = "EchoVideo"
        const val PIECE_MS = 180_000L
        /** M8: glasses meeting audio pieces, like a phone meeting's (~5 min). */
        const val AUDIO_PIECE_MS = 300_000L
        /** A glasses audio recording this long (or longer) is treated as a meeting, not a voice note. */
        const val MEETING_MIN_MS = 120_000L
        const val HEIGHT = 480
        const val VIDEO_BPS = 700_000

        fun durationOf(f: File): Long = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

        /**
         * When it was filmed. Glasses files are named `yyyyMMddHHmmssSSS.mp4`, but the glasses' clock can be months
         * off (seen: June while it was October) — so the name is only trusted if it's within 2 days before the file
         * reached the phone ([File.lastModified] = download time); otherwise the download time is used.
         */
        fun startedAtOf(f: File): Long = startedAt(f.nameWithoutExtension, f.lastModified())

        fun startedAt(name: String, arrivedAt: Long): Long {
            val m = Regex("""(\d{17})""").find(name)?.value
            val named = m?.let { runCatching { SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).parse(it)?.time }.getOrNull() }
            return if (named != null && named <= arrivedAt + 3_600_000L && arrivedAt - named <= 2 * 86_400_000L) named else arrivedAt
        }
    }
}
