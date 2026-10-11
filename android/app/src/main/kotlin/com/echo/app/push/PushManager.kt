package com.echo.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.echo.app.BuildConfig
import com.echo.app.R
import com.echo.memory.EchoBackend
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Push notifications (FCM), 2026-10-11. Firebase is set up by hand from BuildConfig (read at build
 * time from the gitignored app/google-services.json) — no Google Services plugin, and no file means
 * push is simply off. The phone's FCM token is registered with the `push` Edge Function, which is
 * what sends ("Your meeting summary is ready").
 */
object PushManager {
    const val CHANNEL_ID = "jarvis_updates"
    private const val TAG = "EchoPush"

    val isConfigured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank()

    /** Call from Application.onCreate. Safe to call when push isn't configured. */
    fun init(context: Context) {
        createChannel(context)
        if (!isConfigured || FirebaseApp.getApps(context).isNotEmpty()) return
        runCatching {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Firebase init failed: ${it.message}") }
    }

    /** Fetch this phone's token and register it with the cloud (call after sign-in). */
    suspend fun register(context: Context, backend: EchoBackend): Boolean {
        if (!isConfigured || !backend.isLoggedIn) return false
        val token = currentToken() ?: return false
        return runCatching { backend.pushRegister(token, context.packageName) }
            .onFailure { Log.w(TAG, "register failed: ${it.message}") }
            .isSuccess
    }

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { Log.w(TAG, "token failed: ${it.message}"); cont.resume(null) }
        }.onFailure { cont.resume(null) }
    }

    private fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "JARVIS updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When JARVIS finishes something for you (a summary, research, a reminder)."
            },
        )
    }

    /** Show a notification (used when a push arrives while the app is open — Android won't auto-show it). */
    fun show(context: Context, title: String, body: String, data: Map<String, String>) {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP; data.forEach { (k, v) -> putExtra("push_$k", v) } }
        val pi = open?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { pi?.let { setContentIntent(it) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % 100000).toInt(), n) }
            .onFailure { Log.w(TAG, "notify failed (permission?): ${it.message}") }
    }
}
