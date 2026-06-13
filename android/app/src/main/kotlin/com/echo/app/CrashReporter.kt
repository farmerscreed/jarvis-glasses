package com.echo.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Lightweight crash telemetry with no external SDK: captures uncaught exceptions to a local
 * rotating log file, then chains to the platform handler so the system still reports/restarts.
 * The log (filesDir/crash.log) is retrievable via adb or surfaced in the dev console; it can also
 * ride along in the GDPR export later. Avoids the Firebase dependency for v1.
 */
object CrashReporter {
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 64 * 1024L // keep the last ~64KB

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appCtx, thread, throwable) }
            previous?.uncaughtException(thread, throwable) // let the OS do its thing (dialog/restart)
        }
        Log.i("EchoCrash", "crash reporter installed")
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val entry = buildString {
            append("=== CRASH ").append(System.currentTimeMillis()).append(" ===\n")
            append("thread=").append(thread.name).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.SDK_INT).append('\n')
            append("app=").append(BuildConfig.FLAVOR).append(' ').append(BuildConfig.VERSION_NAME).append('\n')
            append(sw.toString()).append('\n')
        }
        val file = File(context.filesDir, FILE)
        if (file.exists() && file.length() > MAX_BYTES) file.delete() // simple rotation
        file.appendText(entry)
    }

    /** The most recent crash log text, or null. Surfaced in the dev console for diagnosis. */
    fun lastLog(context: Context): String? =
        File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeLast(4000)
}
