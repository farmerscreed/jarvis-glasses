package com.echo.app

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
import androidx.core.app.NotificationCompat
import com.echo.device.ble.GlassesBleManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps JARVIS reacting to the glasses with the app backgrounded or killed.
 * It holds the BLE link and runs the shared [GlassesCaptureReactor], so a button press still
 * auto-syncs + captions a capture into your memory when no UI is open. The outbox drain itself
 * already survives via WorkManager; this adds the *capture reaction* half. Started/stopped from
 * Settings ("Keep listening in the background").
 */
@AndroidEntryPoint
class ConnectedCompanionService : Service() {

    @Inject lateinit var reactor: GlassesCaptureReactor
    @Inject lateinit var ble: GlassesBleManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(NOTIF_ID, buildNotification("Listening for your glasses"))
        reactor.start()          // become a reactor host (refcounted; survives the UI)
        ble.connectGlasses()     // keep the control link up so button events arrive
        // Reflect the pipeline's progress in the ongoing notification.
        scope.launch { reactor.status.collect { updateNotification(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY // restart if the system kills us
    }

    override fun onDestroy() {
        reactor.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ConnectedCompanionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is on")
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
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS background",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps JARVIS reacting to your glasses." }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(id, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_companion"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.echo.app.STOP_COMPANION"

        fun start(context: Context) {
            val intent = Intent(context, ConnectedCompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectedCompanionService::class.java))
        }
    }
}
