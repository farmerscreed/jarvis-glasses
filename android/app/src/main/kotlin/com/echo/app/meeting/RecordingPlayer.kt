package com.echo.app.meeting

import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerUi(val recordingId: String = "", val playing: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0)

/**
 * Plays a recording's ~5-minute pieces back to back as ONE timeline (position, ±15 s, jump to a
 * marked moment). Plays on the normal media route (the glasses when connected for sound).
 */
@Singleton
class RecordingPlayer @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = _ui

    private var files: List<File> = emptyList()
    private var offsets: List<Long> = emptyList()   // start of each piece on the overall timeline
    private var lengths: List<Long> = emptyList()
    private var index = 0
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Load a recording (stops whatever was playing). */
    fun load(rec: SavedRecording) {
        if (_ui.value.recordingId == rec.id) return
        release()
        files = rec.files()
        lengths = files.map { durationOf(it) }
        offsets = lengths.runningFold(0L) { acc, l -> acc + l }.dropLast(1)
        index = 0
        _ui.value = PlayerUi(recordingId = rec.id, durationMs = lengths.sum())
    }

    fun toggle() { if (_ui.value.playing) pause() else play() }

    fun play() {
        if (files.isEmpty()) return
        if (player == null) openPiece(index, 0)
        runCatching { player?.start() }
        _ui.value = _ui.value.copy(playing = true)
        startTicker()
    }

    fun pause() {
        runCatching { player?.pause() }
        _ui.value = _ui.value.copy(playing = false)
    }

    /** Jump to [ms] on the overall timeline. */
    fun seekTo(ms: Long) {
        if (files.isEmpty()) return
        val t = ms.coerceIn(0, (_ui.value.durationMs - 500).coerceAtLeast(0))
        val i = offsets.indexOfLast { it <= t }.coerceAtLeast(0)
        val wasPlaying = _ui.value.playing
        if (i != index || player == null) openPiece(i, (t - offsets[i]).toInt())
        else runCatching { player?.seekTo((t - offsets[i]).toInt()) }
        _ui.value = _ui.value.copy(positionMs = t)
        if (wasPlaying) { runCatching { player?.start() } }
    }

    fun skip(deltaMs: Long) = seekTo(_ui.value.positionMs + deltaMs)

    fun release() {
        ticker?.cancel(); ticker = null
        runCatching { player?.release() }; player = null
        _ui.value = PlayerUi()
    }

    private fun openPiece(i: Int, startMs: Int) {
        runCatching { player?.release() }
        index = i
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(files[i].absolutePath)
                prepare()
                if (startMs > 0) seekTo(startMs)
                setOnCompletionListener { next() }
            }
        }.onFailure { Log.w("EchoMeeting", "can't play ${files.getOrNull(i)?.name}: ${it.message}") }.getOrNull()
    }

    /** End of a piece → the next one, seamlessly; end of the last → stop at the end. */
    private fun next() {
        if (index + 1 < files.size) {
            openPiece(index + 1, 0)
            runCatching { player?.start() }
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
                if (p != null && _ui.value.playing) {
                    val pos = runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                    _ui.value = _ui.value.copy(positionMs = (offsets.getOrElse(index) { 0L } + pos))
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
