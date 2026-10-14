package com.echo.app.meeting

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerUi(
    val recordingId: String = "",
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val fromCloud: Boolean = false,
    val error: String = "",
)

/** One piece of a recording: a file on the phone or a (signed) link to the cloud copy. */
data class AudioPiece(val source: String, val durationMs: Long)

/**
 * Plays a recording's ~5-minute pieces back to back as ONE timeline: position, drag, ±15 s, jump to a mark
 * or a transcript line, 1× / 1.5× / 2×. Pieces come from the phone, or from the cloud once the phone has
 * cleaned up its copy (M6). Preparing a piece runs off the main thread (cloud links need the network).
 */
@Singleton
class RecordingPlayer @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Mutex()
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private var pieces: List<AudioPiece> = emptyList()
    private var offsets: List<Long> = emptyList()   // start of each piece on the overall timeline
    private var index = 0
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Load a recording from files on the phone (stops whatever was playing). */
    fun loadLocal(id: String, files: List<File>) {
        if (_ui.value.recordingId == id) return
        release()
        _ui.value = PlayerUi(recordingId = id, loading = true)
        scope.launch {
            val ps = withContext(Dispatchers.IO) { files.map { AudioPiece(it.absolutePath, durationOf(it)) } }
            setPieces(id, ps, fromCloud = false)
        }
    }

    /** Load a recording from cloud links (each with its length in seconds). */
    fun loadCloud(id: String, urls: List<Pair<String, Int>>) {
        if (_ui.value.recordingId == id && pieces.isNotEmpty()) return
        release()
        setPieces(id, urls.map { AudioPiece(it.first, it.second * 1000L) }, fromCloud = true)
    }

    private fun setPieces(id: String, ps: List<AudioPiece>, fromCloud: Boolean) {
        if (_ui.value.recordingId != id && _ui.value.recordingId.isNotEmpty()) return // switched meanwhile
        pieces = ps
        offsets = ps.runningFold(0L) { acc, p -> acc + p.durationMs }.dropLast(1)
        index = 0
        _ui.value = PlayerUi(recordingId = id, durationMs = ps.sumOf { it.durationMs }, fromCloud = fromCloud, speed = _ui.value.speed)
    }

    fun toggle() { if (_ui.value.playing) pause() else play() }

    fun play() {
        if (pieces.isEmpty()) return
        scope.launch {
            lock.withLock {
                if (player == null) openPiece(index, (_ui.value.positionMs - offsets.getOrElse(index) { 0L }).toInt().coerceAtLeast(0))
                runCatching { player?.start() }
                applySpeed()
            }
            _ui.value = _ui.value.copy(playing = player != null)
            startTicker()
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        _ui.value = _ui.value.copy(playing = false)
    }

    /** Jump to [ms] on the overall timeline (keeps playing if it was). */
    fun seekTo(ms: Long, andPlay: Boolean = false) {
        if (pieces.isEmpty()) return
        val t = ms.coerceIn(0, (_ui.value.durationMs - 500).coerceAtLeast(0))
        val i = offsets.indexOfLast { it <= t }.coerceAtLeast(0)
        val keepPlaying = _ui.value.playing || andPlay
        _ui.value = _ui.value.copy(positionMs = t)
        scope.launch {
            lock.withLock {
                if (i != index || player == null) openPiece(i, (t - offsets[i]).toInt())
                else runCatching { player?.seekTo((t - offsets[i]).toInt()) }
                if (keepPlaying) { runCatching { player?.start() }; applySpeed() }
            }
            if (keepPlaying) { _ui.value = _ui.value.copy(playing = player != null); startTicker() }
        }
    }

    fun skip(deltaMs: Long) = seekTo(_ui.value.positionMs + deltaMs)

    /** 1× → 1.5× → 2× → 1×. */
    fun cycleSpeed() {
        val next = when (_ui.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        _ui.value = _ui.value.copy(speed = next)
        if (_ui.value.playing) applySpeed()
    }

    fun release() {
        ticker?.cancel(); ticker = null
        runCatching { player?.release() }; player = null
        pieces = emptyList(); offsets = emptyList(); index = 0
        _ui.value = PlayerUi(speed = _ui.value.speed)
    }

    private fun applySpeed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching { player?.let { it.playbackParams = it.playbackParams.setSpeed(_ui.value.speed) } }
    }

    /** Prepare piece [i] (on IO — a cloud link downloads), positioned at [startMs]. */
    private suspend fun openPiece(i: Int, startMs: Int) {
        runCatching { player?.release() }; player = null
        index = i
        _ui.value = _ui.value.copy(loading = true, error = "")
        val p = withContext(Dispatchers.IO) {
            runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(pieces[i].source)
                    prepare()
                    if (startMs > 0) seekTo(startMs)
                }
            }.onFailure { Log.w("EchoMeeting", "can't play piece $i: ${it.message}") }.getOrNull()
        }
        p?.setOnCompletionListener { scope.launch { next() } }
        player = p
        _ui.value = _ui.value.copy(loading = false, error = if (p == null) "Couldn't play this part — check your connection." else "")
    }

    /** End of a piece → the next one, seamlessly; end of the last → stop at the end. */
    private suspend fun next() = lock.withLock {
        if (index + 1 < pieces.size) {
            openPiece(index + 1, 0)
            runCatching { player?.start() }
            applySpeed()
        } else {
            _ui.value = _ui.value.copy(playing = false, positionMs = _ui.value.durationMs)
            runCatching { player?.release() }; player = null; index = 0
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player
                if (p != null && _ui.value.playing && !_ui.value.loading) {
                    val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                    _ui.value = _ui.value.copy(positionMs = offsets.getOrElse(index) { 0L } + pos)
                }
                delay(250)
            }
        }
    }

    private fun durationOf(f: File): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)
}
