package com.echo.app.push

import com.echo.memory.EchoBackend
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives pushes. With the app closed Android shows the notification itself; with it open we show it
 * here. A rotated token is re-registered with the cloud straight away.
 */
class JarvisMessagingService : FirebaseMessagingService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PushEntryPoint {
        fun backend(): EchoBackend
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val backend = EntryPointAccessors.fromApplication(applicationContext, PushEntryPoint::class.java).backend()
        if (!backend.isLoggedIn) return // registered after sign-in instead
        scope.launch { runCatching { backend.pushRegister(token, packageName) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "JARVIS"
        val body = message.notification?.body ?: message.data["body"] ?: return
        PushManager.show(applicationContext, title, body, message.data)
    }
}
