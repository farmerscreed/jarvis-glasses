package com.echo.app

import android.app.Application
import com.echo.app.sync.SyncScheduler
import com.echo.app.sync.SyncWorker
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Wire the offline-first outbox to WorkManager: every local write enqueues a background
        // drain (survives app kill), plus a periodic backstop for anything still pending.
        val store = EntryPointAccessors
            .fromApplication(this, SyncWorker.SyncEntryPoint::class.java)
            .memoryStore()
        store.onNeedsSync = { SyncScheduler.enqueueNow(this) }
        SyncScheduler.enqueueNow(this)      // flush anything pending from a previous run
        SyncScheduler.enqueuePeriodic(this) // periodic backstop
    }
}
