package com.minesafear.sync

import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * What [SyncWorker] should do about a response.
 *
 * Pure classification, kept out of the worker so it can be unit-tested without
 * WorkManager — see `SyncOutcomeTest`.
 */
enum class SyncOutcome {
    /** The server took it. Clear `pending_sync` for the accepted ids. */
    SUCCESS,

    /** Transient. Come back later with backoff. */
    RETRY,

    /**
     * This will never be accepted, so stop asking.
     *
     * **This does not lose data.** `pending_sync` is only ever cleared for ids the
     * server explicitly accepted, so a permanent failure leaves every record still
     * queued; the next periodic run picks them up. The distinction is purely about
     * whether to keep waking the radio in a tight backoff loop, which on a phone at
     * the bottom of a shaft is the difference between a flat battery and a full one.
     */
    PERMANENT_FAILURE,
}

object SyncOutcomes {

    /**
     * Attempts before a retryable failure is treated as permanent.
     *
     * With WorkManager's exponential backoff from 15 minutes, five attempts spans
     * roughly four hours. After that the periodic schedule is the right mechanism —
     * it will try again on its own without holding a retry chain open.
     */
    const val MAX_ATTEMPTS: Int = 5

    /**
     * Maps an HTTP status onto an action.
     *
     * The 4xx/5xx split is the important one. A 500 means the server is having a bad
     * day and the same bytes will work later. A 400 means the server has looked at
     * these bytes and refused them, and it will refuse them identically in fifteen
     * minutes — retrying is a battery leak that fixes nothing. Three exceptions:
     *
     * - **408** and **429** are 4xx by number but transient by meaning.
     * - **401/403** are treated as permanent because there is no auth layer to
     *   refresh a token from yet. When one exists, 401 should become a refresh
     *   followed by a retry, and this is the line to change.
     */
    fun forHttpStatus(code: Int): SyncOutcome = when {
        code in 200..299 -> SyncOutcome.SUCCESS
        code == 408 || code == 429 -> SyncOutcome.RETRY
        code in 400..499 -> SyncOutcome.PERMANENT_FAILURE
        code in 500..599 -> SyncOutcome.RETRY
        // Anything else is a transport the app does not understand. Retry once or
        // twice rather than discarding a queue on a status nobody anticipated.
        else -> SyncOutcome.RETRY
    }

    /**
     * Maps a thrown exception onto an action.
     *
     * [IOException] covers the whole family that matters here — no route to host, a
     * dropped connection, a socket timeout, a captive portal closing the stream
     * mid-body. All of them mean "try again when there is real signal".
     *
     * Everything else is a bug in this app or a mismatch with the backend's schema:
     * a serialization failure, a null where the contract promised a value. Those do
     * not improve with waiting, so they are permanent — which, per
     * [SyncOutcome.PERMANENT_FAILURE], still leaves the records queued.
     *
     * @throws CancellationException rethrown, never classified. WorkManager cancels
     *   the worker's coroutine when its constraints stop being met, and reporting
     *   that as a failure — or worse, as a success — would either burn a retry or
     *   claim an upload that did not happen.
     */
    fun forException(throwable: Throwable): SyncOutcome {
        if (throwable is CancellationException) throw throwable
        return if (throwable is IOException) SyncOutcome.RETRY else SyncOutcome.PERMANENT_FAILURE
    }

    /**
     * Downgrades a [SyncOutcome.RETRY] to permanent once [MAX_ATTEMPTS] is spent.
     *
     * @param runAttemptCount WorkManager's own counter, 0 on the first run.
     */
    fun capRetries(outcome: SyncOutcome, runAttemptCount: Int): SyncOutcome =
        if (outcome == SyncOutcome.RETRY && runAttemptCount + 1 >= MAX_ATTEMPTS) {
            SyncOutcome.PERMANENT_FAILURE
        } else {
            outcome
        }

    /**
     * Combines the per-endpoint outcomes of one worker run into one verdict.
     *
     * Order matters and is the point of the function: if the drill results uploaded
     * cleanly but the certificates hit a 500, the run must retry, and a naive "last
     * one wins" or "first one wins" would report whichever endpoint happened to be
     * called last. Retry beats permanent failure beats success, because the goal is
     * to come back for whatever is still queued.
     *
     * An empty list is [SyncOutcome.SUCCESS]: nothing to upload is not a failure.
     */
    fun combine(outcomes: List<SyncOutcome>): SyncOutcome = when {
        outcomes.isEmpty() -> SyncOutcome.SUCCESS
        outcomes.any { it == SyncOutcome.RETRY } -> SyncOutcome.RETRY
        outcomes.any { it == SyncOutcome.PERMANENT_FAILURE } -> SyncOutcome.PERMANENT_FAILURE
        else -> SyncOutcome.SUCCESS
    }
}
