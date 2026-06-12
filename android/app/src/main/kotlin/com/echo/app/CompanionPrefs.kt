package com.echo.app

import android.content.Context

/** User preference: keep the companion foreground service running in the background. */
object CompanionPrefs {
    private const val PREFS = "echo-session"
    private const val KEY_BG = "background_companion"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BG, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BG, enabled).apply()
    }
}
