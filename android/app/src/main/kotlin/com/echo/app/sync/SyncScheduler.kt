package com.echo.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Enqueues the outbox-drain [SyncWorker]. */
object SyncScheduler {

    private const val DRAIN_NOW = "echo-drain-now"
    private const val DRAIN_PERIODIC = "echo-drain-periodic"

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Fire a drain as soon as there's a network — used right after every local write. */
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connected)
            .build()
        // KEEP: if a drain is already queued/running, don't pile on — it picks up all pending rows.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DRAIN_NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Backstop: catch anything still pending (failed/backed-off) on a periodic cadence. */
    fun enqueuePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(connected)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(DRAIN_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
