package com.echo.app.video

import android.content.Context

/** Glasses video settings kept on the phone (Phase 3 · V1). */
object VideoPrefs {
    private const val PREFS = "echo-video"
    private const val KEY_LENGTH = "video_length_s"
    /** Law's choice (2026-10-15): 3 minutes for normal videos; site walks run without a limit. */
    const val DEFAULT_LENGTH = 180

    fun length(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LENGTH, DEFAULT_LENGTH)

    fun setLength(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LENGTH, seconds).apply()
    }

    fun label(seconds: Int): String = when {
        seconds >= 65535 -> "No limit"
        seconds < 60 -> "$seconds seconds"
        seconds == 60 -> "1 minute"
        else -> "${seconds / 60} minutes"
    }
}
