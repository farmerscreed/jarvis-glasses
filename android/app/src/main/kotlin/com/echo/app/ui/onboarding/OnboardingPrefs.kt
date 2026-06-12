package com.echo.app.ui.onboarding

import android.content.Context

/** First-run flag: whether the Companion Setup wizard has been completed. */
object OnboardingPrefs {
    private const val PREFS = "echo-session" // shared with the session store
    private const val KEY_DONE = "onboarding_done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun setDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}
