package com.minesafear.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

/**
 * The retry policy, which is the part of sync that decides whether a phone spends
 * its battery usefully.
 */
class SyncOutcomeTest {

    @Test
    fun `2xx succeeds`() {
        assertEquals(SyncOutcome.SUCCESS, SyncOutcomes.forHttpStatus(200))
        assertEquals(SyncOutcome.SUCCESS, SyncOutcomes.forHttpStatus(201))
        assertEquals(SyncOutcome.SUCCESS, SyncOutcomes.forHttpStatus(204))
    }

    /** The server is having a bad day; the same bytes will work later. */
    @Test
    fun `5xx retries`() {
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(500))
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(502))
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(503))
    }

    /**
     * The important one. A 400 means the server looked at the payload and refused
     * it, and will refuse it identically in fifteen minutes.
     */
    @Test
    fun `4xx is permanent`() {
        assertEquals(SyncOutcome.PERMANENT_FAILURE, SyncOutcomes.forHttpStatus(400))
        assertEquals(SyncOutcome.PERMANENT_FAILURE, SyncOutcomes.forHttpStatus(401))
        assertEquals(SyncOutcome.PERMANENT_FAILURE, SyncOutcomes.forHttpStatus(403))
        assertEquals(SyncOutcome.PERMANENT_FAILURE, SyncOutcomes.forHttpStatus(404))
        assertEquals(SyncOutcome.PERMANENT_FAILURE, SyncOutcomes.forHttpStatus(422))
    }

    /** 4xx by number, transient by meaning. */
    @Test
    fun `timeout and rate limit retry despite being 4xx`() {
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(408))
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(429))
    }

    @Test
    fun `an unrecognised status retries rather than discarding the queue`() {
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(0))
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forHttpStatus(600))
    }

    @Test
    fun `io failures retry`() {
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forException(IOException("no route")))
        assertEquals(SyncOutcome.RETRY, SyncOutcomes.forException(SocketTimeoutException()))
    }

    @Test
    fun `a programming error is permanent`() {
        assertEquals(
            SyncOutcome.PERMANENT_FAILURE,
            SyncOutcomes.forException(IllegalStateException("no converter factory")),
        )
    }

    /**
     * Cancellation is WorkManager stopping the worker, not a verdict on the payload.
     * Swallowing it would either burn a retry or report an upload that never happened.
     */
    @Test(expected = CancellationException::class)
    fun `cancellation is rethrown`() {
        SyncOutcomes.forException(CancellationException("stopped"))
    }

    @Test
    fun `retries are capped`() {
        val outcome = SyncOutcome.RETRY
        // Attempt numbers are zero-based, so the last allowed attempt index is
        // MAX_ATTEMPTS - 1.
        for (attempt in 0 until SyncOutcomes.MAX_ATTEMPTS - 1) {
            assertEquals(
                "attempt $attempt",
                SyncOutcome.RETRY,
                SyncOutcomes.capRetries(outcome, attempt),
            )
        }
        assertEquals(
            SyncOutcome.PERMANENT_FAILURE,
            SyncOutcomes.capRetries(outcome, SyncOutcomes.MAX_ATTEMPTS - 1),
        )
        assertEquals(
            SyncOutcome.PERMANENT_FAILURE,
            SyncOutcomes.capRetries(outcome, SyncOutcomes.MAX_ATTEMPTS + 20),
        )
    }

    @Test
    fun `capping leaves success and permanent failure alone`() {
        assertEquals(SyncOutcome.SUCCESS, SyncOutcomes.capRetries(SyncOutcome.SUCCESS, 99))
        assertEquals(
            SyncOutcome.PERMANENT_FAILURE,
            SyncOutcomes.capRetries(SyncOutcome.PERMANENT_FAILURE, 0),
        )
    }

    @Test
    fun `nothing to upload is not a failure`() {
        assertEquals(SyncOutcome.SUCCESS, SyncOutcomes.combine(emptyList()))
    }

    /**
     * The case the function exists for: results uploaded, certificates 500'd. The run
     * must come back, whichever order the endpoints were called in.
     */
    @Test
    fun `one retryable endpoint makes the whole run retry`() {
        assertEquals(
            SyncOutcome.RETRY,
            SyncOutcomes.combine(listOf(SyncOutcome.SUCCESS, SyncOutcome.RETRY)),
        )
        assertEquals(
            SyncOutcome.RETRY,
            SyncOutcomes.combine(listOf(SyncOutcome.RETRY, SyncOutcome.SUCCESS)),
        )
        assertEquals(
            SyncOutcome.RETRY,
            SyncOutcomes.combine(listOf(SyncOutcome.PERMANENT_FAILURE, SyncOutcome.RETRY)),
        )
    }

    @Test
    fun `a permanent failure outranks a success`() {
        assertEquals(
            SyncOutcome.PERMANENT_FAILURE,
            SyncOutcomes.combine(listOf(SyncOutcome.SUCCESS, SyncOutcome.PERMANENT_FAILURE)),
        )
    }

    @Test
    fun `all clear is a success`() {
        assertEquals(
            SyncOutcome.SUCCESS,
            SyncOutcomes.combine(listOf(SyncOutcome.SUCCESS, SyncOutcome.SUCCESS)),
        )
    }

    /**
     * Guards the invariant the rest of the worker leans on: a run that does not
     * succeed never clears a flag, so a failure cannot lose a record.
     */
    @Test
    fun `only success is treated as an upload`() {
        assertTrue(SyncOutcomes.forHttpStatus(200) == SyncOutcome.SUCCESS)
        assertFalse(SyncOutcomes.forHttpStatus(500) == SyncOutcome.SUCCESS)
        assertFalse(SyncOutcomes.forHttpStatus(400) == SyncOutcome.SUCCESS)
    }
}
