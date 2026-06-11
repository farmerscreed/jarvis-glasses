package com.echo.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent

/**
 * Receives the glasses' physical button presses. The temple buttons send Bluetooth AVRCP media
 * commands; an *active* MediaSession is what they get routed to. We mark this session active +
 * "playing" so the glasses' taps land in the callbacks here, then fire [onTrigger]. Logs to "EchoBtn".
 */
class GlassesButtonController(context: Context) {

    companion object { const val TAG = "EchoBtn" }

    private val main = Handler(Looper.getMainLooper())
    private val session = MediaSessionCompat(context.applicationContext, "EchoGlasses")

    /** Invoked (main thread) when a glasses button we treat as "start listening" is pressed. */
    var onTrigger: (() -> Unit)? = null

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { log("onPlay"); fire() }
            override fun onPause() { log("onPause"); fire() }
            override fun onSkipToNext() { log("onSkipToNext"); fire() }
            override fun onSkipToPrevious() { log("onSkipToPrevious"); fire() }
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                val ke = mediaButtonEvent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                log("mediaButton keyCode=${ke?.keyCode} (${ke?.let { KeyEvent.keyCodeToString(it.keyCode) }}) action=${ke?.action}")
                return super.onMediaButtonEvent(mediaButtonEvent)
            }
        })
    }

    fun activate() {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
            .build()
        session.setPlaybackState(state)
        session.isActive = true
        Log.i(TAG, "MediaSession active — glasses buttons should now route here")
    }

    private fun fire() = main.post { onTrigger?.invoke() }
    private fun log(msg: String) = Log.i(TAG, msg)

    fun release() {
        runCatching { session.isActive = false }
        runCatching { session.release() }
    }
}
