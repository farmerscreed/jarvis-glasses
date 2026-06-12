package com.echo.app.ui.components

import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Plays a local capture video (the glasses' .mp4) with standard transport controls. Uses the
 * framework VideoView — no extra dependency — wrapped for Compose. For files already on the phone.
 */
@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.absolutePath)
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { it.isLooping = false; start() }
            }
        },
    )
}
