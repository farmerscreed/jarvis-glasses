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

    // ── site-walk windows: a glasses video that STARTED inside one is a walk (kept: the last 20) ──────────
    private const val KEY_WALKS = "walk_windows"

    fun walkStarted(context: Context, at: Long = System.currentTimeMillis()) = saveWalks(context, walks(context) + (at to Long.MAX_VALUE))
    fun walkStopped(context: Context, at: Long = System.currentTimeMillis()) =
        saveWalks(context, walks(context).map { if (it.second == Long.MAX_VALUE) it.first to at else it })

    /** True if [t] falls in a walk (30 s of slack: the glasses' clock and file names aren't exact). */
    fun inWalk(context: Context, t: Long): Boolean = walks(context).any { t >= it.first - 30_000 && t <= it.second }

    private fun walks(context: Context): List<Pair<Long, Long>> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WALKS, "").orEmpty()
            .split(";").mapNotNull { w -> w.split("-").takeIf { it.size == 2 }?.let { (a, b) -> a.toLongOrNull()?.let { s -> b.toLongOrNull()?.let { e -> s to e } } } }

    private fun saveWalks(context: Context, list: List<Pair<Long, Long>>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WALKS, list.takeLast(20).joinToString(";") { "${it.first}-${it.second}" }).apply()
    }

    fun label(seconds: Int): String = when {
        seconds >= 65535 -> "No limit"
        seconds < 60 -> "$seconds seconds"
        seconds == 60 -> "1 minute"
        else -> "${seconds / 60} minutes"
    }
}
