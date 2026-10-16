package com.echo.app.ui.components

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File

/** Lets the page around a [VideoPlayer] jump to a moment and follow where playback is. */
class VideoControl {
    internal var view: VideoView? = null
    /** Where playback is (ms), updated a few times a second while the player is on screen. */
    var positionMs by mutableLongStateOf(0L)
        internal set

    fun seekTo(ms: Long) {
        view?.let { it.seekTo(ms.toInt()); it.start() }
    }
}

@Composable
fun rememberVideoControl() = remember { VideoControl() }

/**
 * Plays a local capture video (the glasses' .mp4) with standard transport controls. Uses the
 * framework VideoView — no extra dependency — wrapped for Compose. For files already on the phone.
 * [autoPlay] false = shows the first frame and waits for a tap (the video page opens on the notes).
 */
@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier, control: VideoControl? = null, autoPlay: Boolean = true) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { it.isLooping = false; if (autoPlay) start() else seekTo(1) }
                control?.view = this
            }
        },
        onRelease = { v -> v.stopPlayback(); if (control?.view === v) control.view = null },
    )
    if (control != null) LaunchedEffect(control) {
        while (true) {
            control.view?.let { v -> runCatching { control.positionMs = v.currentPosition.toLong() } }
            delay(400)
        }
    }
}
