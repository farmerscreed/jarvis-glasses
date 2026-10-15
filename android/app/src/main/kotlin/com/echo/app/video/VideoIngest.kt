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
        // A glasses video filmed during a site walk becomes a walk (the walk's start/stop times are kept).
        val kind = kind ?: if (VideoPrefs.inWalk(context, startedAt)) "walk" else "video"
        val durationMs = withContext(Dispatchers.IO) { durationOf(original) }
        if (durationMs <= 0) { Log.w(TAG, "can't read ${original.name}"); return null }
        // One recording per original (a re-sync of the same file doesn't make a second one).
        recorder.saved().firstOrNull { runCatching { JSONObject(File(it.dir, "meta.json").readText()).optString("original") }.getOrNull() == original.absolutePath }
            ?.let { return it.id }
        val id = "rec-$startedAt-" + UUID.randomUUID().toString().take(6)
        val dir = File(recorder.root(), id).apply { mkdirs() }
        writeMeta(dir, id, title, source, kind, startedAt, (durationMs / 1000).toInt(), original)
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
        MeetingUploadWorker.enqueue(context)
        id
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
        const val HEIGHT = 480
        const val VIDEO_BPS = 700_000

        fun durationOf(f: File): Long = runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }
        }.getOrDefault(0L)

        /** Glasses files are named `yyyyMMddHHmmssSSS.mp4` (local time); otherwise the file's modified time. */
        fun startedAtOf(f: File): Long {
            val m = Regex("""(\d{17})""").find(f.nameWithoutExtension)?.value
            return m?.let { runCatching { SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).parse(it)?.time }.getOrNull() } ?: f.lastModified()
        }
    }
}
