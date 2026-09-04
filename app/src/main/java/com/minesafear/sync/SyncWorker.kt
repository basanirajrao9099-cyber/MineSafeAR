package com.minesafear.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.repository.TrainingRepository
import retrofit2.Response

/**
 * Drains the local upload queue when the device has connectivity.
 *
 * Every completed drill and issued certificate is written with `pending_sync = 1`,
 * so the queue is the database itself rather than a separate outbox — there is no
 * window in which a result exists but is not queued, and no second place for it to
 * be lost. This worker reads those rows, posts them through [SyncApiService], and
 * clears the flag **only for the ids the server acknowledged**.
 *
 * Constrained to [androidx.work.NetworkType.CONNECTED] by [SyncScheduler], which is
 * what makes "syncs when connectivity returns" true without this class ever asking
 * whether there is a network: WorkManager holds the run until there is one.
 *
 * ## What it does not do
 *
 * - **No pull.** Module content and worker profiles should download here so a
 *   freshly-provisioned phone can be handed to a new hire underground.
 *   [SyncApiService] has no endpoint for either yet.
 * - **No `module_progress`, `assessment_results` or `workers`.** All three carry a
 *   `pending_sync` column and none has an endpoint. They are excluded rather than
 *   silently attempted; see the note on `TrainingRepository.observePendingSyncCount`.
 *
 * ## Failure handling
 *
 * A run that cannot reach the server returns [Result.retry] so WorkManager's
 * exponential backoff applies; a run the server actively rejected returns
 * [Result.failure]. Neither loses data — the `pending_sync` flag is untouched unless
 * an id was acknowledged, so the next run sees the same queue. See [SyncOutcomes].
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** One endpoint's worth of work: what happened, and how much of it stuck. */
    private data class Upload(val outcome: SyncOutcome, val acceptedCount: Int)

    override suspend fun doWork(): Result {
        val repository = TrainingRepository(DatabaseProvider.get(applicationContext))

        val pendingResults = repository.pendingModuleResults()
        val pendingCertificates = repository.pendingCertificates()

        if (pendingResults.isEmpty() && pendingCertificates.isEmpty()) {
            // Deliberately does not touch the last-synced timestamp: nothing was
            // synced. The Home indicator already reads an empty queue as "Synced",
            // so there is nothing this would add except a misleading clock.
            Log.i(TAG, "Nothing pending, nothing sent.")
            return Result.success()
        }

        val api = SyncApi.service(applicationContext)
        val deviceId = SyncStatusStore.deviceId(applicationContext)
        // One timestamp for the whole run so a batch pair is recognisable as one
        // upload attempt on the far end.
        val sentAt = System.currentTimeMillis()

        Log.i(
            TAG,
            "Run ${runAttemptCount + 1}: ${pendingResults.size} result(s), " +
                "${pendingCertificates.size} certificate(s) queued.",
        )

        val uploads = mutableListOf<Upload>()

        if (pendingResults.isNotEmpty()) {
            val dtos = pendingResults.map { it.toDto() }
            uploads += upload(
                label = "module results",
                batch = SyncBatch(deviceId, sentAt, dtos),
                ids = dtos.map { it.recordId() },
                post = { api.uploadModuleResults(it) },
                markSynced = { accepted -> repository.markModuleResultsSynced(accepted) },
            )
        }

        // Attempted even if the results upload failed. The two are independent on the
        // wire for exactly this reason: a worker whose certificate is stuck behind a
        // rejected drill attempt cannot prove their certification, and the drill
        // attempt is the less important of the two.
        if (pendingCertificates.isNotEmpty()) {
            val dtos = pendingCertificates.map { it.toDto() }
            uploads += upload(
                label = "certificates",
                batch = SyncBatch(deviceId, sentAt, dtos),
                ids = dtos.map { it.recordId() },
                post = { api.uploadCertificates(it) },
                markSynced = { accepted -> repository.markCertificatesSynced(accepted) },
            )
        }

        // Recorded on any accepted record, independent of the run's verdict: "last
        // synced" means the last time data reached the server, and a run where the
        // results landed but the certificates 500'd did reach it.
        if (uploads.sumOf { it.acceptedCount } > 0) {
            SyncStatusStore.recordSyncSuccess(applicationContext, System.currentTimeMillis())
        }

        val verdict = SyncOutcomes.capRetries(
            SyncOutcomes.combine(uploads.map { it.outcome }),
            runAttemptCount,
        )
        Log.i(TAG, "Run finished: $verdict (${uploads.sumOf { it.acceptedCount }} accepted)")

        return when (verdict) {
            SyncOutcome.SUCCESS -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.PERMANENT_FAILURE -> Result.failure()
        }
    }

    /**
     * Posts one batch and clears the flag for whatever came back acknowledged.
     *
     * Generic over the record type because the two endpoints differ only in their
     * payload and their id field; the accept-then-clear logic that must not be got
     * wrong is written once.
     *
     * @param ids the record ids in [batch], in order, for matching against
     *   [SyncAck.acceptedIds].
     */
    private suspend fun <T> upload(
        label: String,
        batch: SyncBatch<T>,
        ids: List<String>,
        post: suspend (SyncBatch<T>) -> Response<SyncAck>,
        markSynced: suspend (List<String>) -> Unit,
    ): Upload {
        val response = try {
            post(batch)
        } catch (throwable: Throwable) {
            // Rethrows CancellationException rather than classifying it.
            val outcome = SyncOutcomes.forException(throwable)
            Log.w(
                TAG,
                "Posting ${ids.size} $label threw " +
                    "${throwable.javaClass.simpleName}: ${throwable.message} -> $outcome",
            )
            return Upload(outcome, acceptedCount = 0)
        }

        val outcome = SyncOutcomes.forHttpStatus(response.code())
        if (outcome != SyncOutcome.SUCCESS) {
            Log.w(TAG, "Server answered ${response.code()} for ${ids.size} $label -> $outcome")
            return Upload(outcome, acceptedCount = 0)
        }

        // A null body (204) or a null acceptedIds means "all of them" — what a
        // minimal backend that only returns 200 will do. See SyncAck.
        val claimed = response.body()?.acceptedIds ?: ids
        // Intersected with what was actually sent, so a server echoing an id we never
        // uploaded cannot clear the flag on an unrelated row.
        val sent = ids.toSet()
        val accepted = claimed.filter { it in sent }.distinct()

        markSynced(accepted)

        val left = ids.size - accepted.size
        if (left > 0) {
            // Not a retry: the server returned 200 having chosen not to take these,
            // and asking again in fifteen minutes will get the same answer. They stay
            // queued for the periodic run, and the Home indicator keeps showing
            // pending, which is the truth.
            Log.w(
                TAG,
                "Server accepted ${accepted.size}/${ids.size} $label; " +
                    "$left still queued. ${response.body()?.message ?: ""}",
            )
        } else {
            Log.i(TAG, "Uploaded ${accepted.size} $label.")
        }

        return Upload(SyncOutcome.SUCCESS, acceptedCount = accepted.size)
    }

    companion object {
        private const val TAG = "SyncWorker"

        const val UNIQUE_WORK_NAME = "minesafear-periodic-sync"

        /** @see SyncScheduler.requestSyncNow */
        const val ON_DEMAND_WORK_NAME = "minesafear-sync-now"
    }
}
