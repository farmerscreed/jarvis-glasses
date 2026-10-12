package com.echo.app.meeting

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * Keeps a meeting recording going with the screen OFF or the app in the background (Android 14+ only
 * lets an app use the mic while visible or from a foreground service of type `microphone`). Holds a
 * partial wake lock so the CPU keeps encoding. The notification shows the timer with **Mark** and
 * **Stop**. Started/stopped by [MeetingRecorder].
 */
@AndroidEntryPoint
class MeetingRecorderService : Service() {

    @Inject lateinit var recorder: MeetingRecorder

    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, buildNotification("Recording · 0:00"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, buildNotification("Recording · 0:00"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't go foreground: ${e.message}")
            recorder.onBackgroundRefused()
            stopSelf()
            return
        }
        acquireWake()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> recorder.stop()
            ACTION_MARK -> recorder.mark()
            ACTION_UPDATE -> intent.getStringExtra(EXTRA_TEXT)?.let { update(it) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }; wake = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:meeting").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // safety cap: 6 h
            }
        }
    }

    private fun update(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_MEETING, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, MeetingRecorderService::class.java).apply { action = a },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is recording")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Mark", action(ACTION_MARK, 21))
            .addAction(0, "Stop", action(ACTION_STOP, 22))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Meeting recording", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while JARVIS records a meeting, so it keeps going with the screen off." }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "EchoMeeting"
        private const val CHANNEL_ID = "jarvis_meeting"
        private const val NOTIF_ID = 44
        private const val ACTION_STOP = "com.echo.app.STOP_MEETING"
        private const val ACTION_MARK = "com.echo.app.MARK_MEETING"
        private const val ACTION_UPDATE = "com.echo.app.UPDATE_MEETING"
        private const val EXTRA_TEXT = "text"
        const val EXTRA_OPEN_MEETING = "open_meeting"

        /** Returns false if Android refused (app in the background, e.g. phone locked). */
        fun start(context: Context): Boolean = try {
            val i = Intent(context, MeetingRecorderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            true
        } catch (e: Exception) {
            Log.w(TAG, "meeting service not started: ${e.message}")
            false
        }

        fun update(context: Context, text: String) {
            runCatching { context.startService(Intent(context, MeetingRecorderService::class.java).apply { action = ACTION_UPDATE; putExtra(EXTRA_TEXT, text) }) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MeetingRecorderService::class.java)) }
        }
    }
}
