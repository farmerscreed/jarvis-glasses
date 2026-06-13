package com.echo.app

import android.content.Context

/** One-time consent that audio is recorded and sent to the cloud for transcription. */
object ConsentPrefs {
    private const val PREFS = "echo-session"
    private const val KEY = "recording_consented"

    fun isGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun grant(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}
