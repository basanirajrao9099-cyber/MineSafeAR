package com.minesafear.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the sync schedule. Sites often have connectivity only near the surface
 * office, so sync is constrained on the network rather than triggered by the UI.
 *
 * ## Two requests, not one
 *
 * [schedulePeriodicSync] is the safety net: it catches anything the app forgot to
 * ask about, and it is what eventually retries a batch a server refused.
 *
 * [requestSyncNow] is what actually delivers "syncs when connectivity returns". A
 * six-hour periodic job does not: WorkManager will not run a periodic request early
 * because a constraint became satisfiable, so a worker who finishes a drill offline
 * at the start of a shift and walks past the office an hour later would still be
 * waiting. A one-shot request enqueued at the moment the record is written sits
 * dormant until the network constraint is met and then fires immediately.
 */
object SyncScheduler {

    private const val REPEAT_INTERVAL_HOURS = 6L
    private const val BACKOFF_MINUTES = 15L

    /** Idempotent — safe to call on every app start. */
    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_WORK_NAME,
            // KEEP so an app restart does not reset the interval that is already running.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Asks for an upload as soon as there is a network, and no sooner.
     *
     * Call this immediately after writing a record with `pending_sync = 1`. It is
     * cheap and it is safe to call when offline — that is the point. WorkManager
     * persists the request across process death and reboot, so the enqueue survives
     * a phone that spends the rest of the shift underground and is switched off in
     * between.
     *
     * Called from the UI layer rather than from `TrainingRepository`, which has no
     * [Context] and should not acquire one just to schedule work. The trade-off is
     * that a new write site has to remember to call this; the periodic run is the
     * backstop for when it does not.
     */
    fun requestSyncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SyncWorker.ON_DEMAND_WORK_NAME,
            // APPEND_OR_REPLACE, weighing the four options:
            //  - KEEP would drop this request if one is already waiting on the
            //    network, so a drill finished while an earlier upload is pending
            //    would never trigger a run of its own.
            //  - REPLACE cancels a run that is in flight, which mid-POST means an
            //    upload the server may have already committed and we never hear
            //    about.
            //  - APPEND deadlocks the chain if a prerequisite ends in failure or
            //    cancellation: every later request is cancelled with it.
            // APPEND_OR_REPLACE queues behind a healthy predecessor and clears a
            // broken one, which is the behaviour wanted at every write site.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.UNIQUE_WORK_NAME)
    }

    /**
     * [NetworkType.CONNECTED] rather than `UNMETERED`: these payloads are a few
     * hundred bytes each, and a worker's phone on a mobile connection at the pit
     * head is the normal case, not the exception. Requiring Wi-Fi would mean
     * certificates sitting unsynced for weeks.
     */
    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
