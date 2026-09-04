package com.minesafear.sync

import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [SyncApiService] that talks to nobody.
 *
 * Logs the payload it was handed and returns HTTP 200. This is the whole sync
 * transport for now: there is no backend, and the point of the exercise is that the
 * *local* half — draining `pending_sync`, clearing flags only for accepted ids,
 * retrying with backoff — is real and observable before a server exists.
 *
 * ## Why this is not a test double
 *
 * It lives in `main`, not `test`, and it is what ships. A phone in a mine with this
 * build will queue its records, run the sync worker when it finds signal, log what
 * it would have sent, and mark it done. That is a deliberate stand-in, not an
 * accident, and [Behaviour.Failing] exists so the failure paths can be exercised
 * without a server to break.
 *
 * ## Reading the log
 *
 * One line per batch and one per record, at `Log.i`, tagged [TAG]:
 *
 * ```
 * SyncApi  I  → POST v1/module-results  batch#1  device=… records=2
 * SyncApi  I    [1/2] ModuleResultDto(id=8f3…, moduleId=fire_explosion_response, …)
 * SyncApi  I    [2/2] ModuleResultDto(id=1c9…, moduleId=fire_explosion_response, …)
 * SyncApi  I  ← 200  accepted=2
 * ```
 *
 * `data class` `toString()` is the payload format, deliberately: no serializer is
 * wired up (see [SyncApi]), and inventing a hand-rolled JSON writer purely to make
 * the log look like a request body would mean maintaining an escaping routine that
 * nothing verifies. When a converter factory lands, log its output instead.
 *
 * **Records carry worker names and certificate signatures.** That is acceptable at
 * `Log.i` in a prototype and is not acceptable in a release build — see the note on
 * [SyncApi] before wiring a real client.
 */
class FakeSyncApiService(
    /** What to pretend the server did. */
    private val behaviour: Behaviour = Behaviour.Accepting,
    /**
     * Simulated round-trip time. Non-zero by default so the worker spends real time
     * "uploading" and the Home screen's pending count is observably transient
     * rather than flickering; tests pass 0.
     */
    private val latencyMillis: Long = 400L,
) : SyncApiService {

    sealed interface Behaviour {
        /** 200, everything accepted. */
        data object Accepting : Behaviour

        /**
         * 200, but only the first [acceptCount] records are accepted.
         *
         * The interesting case, and the reason [SyncAck.acceptedIds] exists: the
         * rest must stay `pending_sync = 1` and come back on the next run.
         */
        data class PartiallyAccepting(val acceptCount: Int) : Behaviour

        /**
         * An HTTP error. 5xx is retryable, 4xx is not — see [SyncOutcome].
         *
         * Must be >= 400: `Response.error` rejects a success code, so this cannot be
         * used to fake a 200 with an error body.
         */
        data class Failing(val code: Int) : Behaviour

        /** A dropped connection, i.e. what actually happens in a mine. */
        data object Unreachable : Behaviour
    }

    private val batchCounter = AtomicInteger(0)

    override suspend fun uploadModuleResults(
        batch: SyncBatch<ModuleResultDto>,
    ): Response<SyncAck> = respond(
        path = "v1/module-results",
        batch = batch,
        ids = batch.records.map { it.recordId() },
    )

    override suspend fun uploadCertificates(
        batch: SyncBatch<CertificateDto>,
    ): Response<SyncAck> = respond(
        path = "v1/certificates",
        batch = batch,
        ids = batch.records.map { it.recordId() },
    )

    override suspend fun uploadAssessmentResults(
        batch: SyncBatch<AssessmentResultDto>,
    ): Response<SyncAck> = respond(
        path = "v1/assessment-results",
        batch = batch,
        ids = batch.records.map { it.recordId() },
    )

    private suspend fun <T> respond(
        path: String,
        batch: SyncBatch<T>,
        ids: List<String>,
    ): Response<SyncAck> {
        val n = batchCounter.incrementAndGet()
        Log.i(
            TAG,
            "→ POST $path  batch#$n  device=${batch.deviceId} " +
                "sentAt=${batch.sentAtMillis} records=${batch.records.size}",
        )
        batch.records.forEachIndexed { index, record ->
            Log.i(TAG, "  [${index + 1}/${batch.records.size}] $record")
        }

        if (latencyMillis > 0) delay(latencyMillis)

        return when (behaviour) {
            Behaviour.Accepting -> {
                Log.i(TAG, "← 200  accepted=${ids.size}")
                Response.success(SyncAck(acceptedIds = ids))
            }

            is Behaviour.PartiallyAccepting -> {
                val accepted = ids.take(behaviour.acceptCount.coerceAtLeast(0))
                Log.i(TAG, "← 200  accepted=${accepted.size}/${ids.size}")
                Response.success(
                    SyncAck(
                        acceptedIds = accepted,
                        message = "simulated partial accept",
                    )
                )
            }

            is Behaviour.Failing -> {
                Log.w(TAG, "← ${behaviour.code}  (simulated)")
                Response.error(
                    behaviour.code,
                    """{"error":"simulated ${behaviour.code}"}"""
                        .toResponseBody("application/json".toMediaType()),
                )
            }

            // Thrown, not returned: this is what a real transport does when the
            // signal drops mid-request, and SyncWorker has to survive it.
            Behaviour.Unreachable -> {
                Log.w(TAG, "← connection dropped (simulated)")
                throw java.io.IOException("simulated unreachable host")
            }
        }
    }

    companion object {
        const val TAG = "SyncApi"
    }
}
