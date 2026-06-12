package com.echo.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.echo.memory.MemoryStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Drains the offline-first outbox in the background — survives app kill and device reboot, and runs
 * under a CONNECTED constraint so a memory saved off-grid uploads the moment the network returns,
 * with no app UI open. Resolves [MemoryStore] via a Hilt EntryPoint (no @HiltWorker / custom
 * WorkerFactory needed — the default factory instantiates this standard-constructor worker).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SyncEntryPoint {
        fun memoryStore(): MemoryStore
    }

    override suspend fun doWork(): Result {
        val store = EntryPointAccessors
            .fromApplication(applicationContext, SyncEntryPoint::class.java)
            .memoryStore()
        return try {
            val drained = store.syncAll(force = true) // deferred AI re-run + outbox drain
            Log.i("EchoSync", "background sync: fullyDrained=$drained")
            if (drained) Result.success() else Result.retry() // retry until the outbox is empty
        } catch (e: Exception) {
            Log.w("EchoSync", "background drain failed: ${e.message}")
            Result.retry() // WorkManager backs off and tries again
        }
    }
}
