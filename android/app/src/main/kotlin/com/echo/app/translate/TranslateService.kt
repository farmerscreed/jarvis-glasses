package com.echo.app.translate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echo.app.MainActivity
import com.echo.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps live translation running with the screen OFF or the app in the background.
 *
 * Why it exists: since Android 14 an app may only use the microphone while it is visible OR while it
 * runs a foreground service of type `microphone`. Without this, translation worked until the screen
 * went dark, then the mic silently delivered nothing (the Gemini connection stayed open — verified
 * from the server logs on 2026-10-10). It also holds a partial wake lock (CPU keeps streaming audio)
 * and a low-latency Wi-Fi lock (the WebSocket doesn't stall when the screen sleeps).
 *
 * Started/stopped by [TranslationController.start]/[TranslationController.stop]. The notification
 * shows what's happening and has a Stop button.
 */
@AndroidEntryPoint
class TranslateService : Service() {

    @Inject lateinit var translator: TranslationController

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, buildNotification("Listening and translating"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification("Listening and translating"))
            }
        } catch (e: Exception) {
            // Android refuses a microphone service started from the background (e.g. double-tap with
            // the phone locked and the app not visible). Translation still runs while the app is open.
            Log.w(TAG, "couldn't go foreground: ${e.message}")
            translator.onBackgroundRefused()
            stopSelf()
            return
        }
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { translator.stop(save = true); stopSelf() }
            ACTION_UPDATE -> intent.getStringExtra(EXTRA_TEXT)?.let { updateNotification(it) }
        }
        return START_NOT_STICKY // never restart translation on our own after the system kills us
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:translate").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // safety cap: 4 h
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifi = wm.createWifiLock(mode, "jarvis:translate").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        runCatching { wifi?.takeIf { it.isHeld }?.release() }; wifi = null
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, TranslateService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is translating")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Translation", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while JARVIS is translating, so it keeps working with the screen off." }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "EchoTranslate"
        private const val CHANNEL_ID = "jarvis_translate"
        private const val NOTIF_ID = 43
        private const val ACTION_STOP = "com.echo.app.STOP_TRANSLATE"
        private const val ACTION_UPDATE = "com.echo.app.UPDATE_TRANSLATE"
        private const val EXTRA_TEXT = "text"

        /** Returns false if Android refused (app in the background, e.g. phone locked). */
        fun start(context: Context): Boolean = try {
            val i = Intent(context, TranslateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            true
        } catch (e: Exception) {
            Log.w(TAG, "translate service not started: ${e.message}")
            false
        }

        fun update(context: Context, text: String) {
            runCatching {
                context.startService(Intent(context, TranslateService::class.java).apply { action = ACTION_UPDATE; putExtra(EXTRA_TEXT, text) })
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TranslateService::class.java)) }
        }
    }
}
