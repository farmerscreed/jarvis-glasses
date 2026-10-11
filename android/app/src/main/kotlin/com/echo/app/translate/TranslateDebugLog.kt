package com.echo.app.translate

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One line per translator decision, kept on the phone because logcat only holds a few minutes.
 * `files/translate-debug.log` (≈500 KB, then rolled to `.1`). `PHONE-LOGS.bat` pulls it with
 * `run-as`. Temporary aid for P0 (the echo bug) — slim or remove once it's closed.
 */
class TranslateDebugLog(context: Context) {
    private val file = File(context.filesDir, "translate-debug.log")
    private val old = File(context.filesDir, "translate-debug.log.1")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "translate-log").apply { isDaemon = true } }
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(msg: String) {
        val line = fmt.format(Date()) + " " + msg.replace('\n', ' ') + "\n"
        io.execute {
            runCatching {
                if (file.length() > MAX_BYTES) { old.delete(); file.renameTo(old) }
                file.appendText(line)
            }
        }
    }

    private companion object { const val MAX_BYTES = 500_000L }
}
