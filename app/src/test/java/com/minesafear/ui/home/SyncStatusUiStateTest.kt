package com.minesafear.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home indicator's state machine.
 *
 * Worth testing in its own right because the wrong branch here tells a worker their
 * certificate has reached the office when it has not.
 */
class SyncStatusUiStateTest {

    @Test
    fun `a queue means pending, whatever the history`() {
        val state = SyncStatusUiState.from(pendingCount = 3, lastSyncedAtMillis = null)

        assertTrue(state.toString(), state is SyncStatusUiState.Pending)
        assertEquals(3, (state as SyncStatusUiState.Pending).count)
        assertNull(state.lastSyncedAtMillis)
    }

    /**
     * Pending outranks a previous success. A phone that uploaded this morning and has
     * queued something since is pending, not synced.
     */
    @Test
    fun `pending keeps the earlier timestamp`() {
        val state = SyncStatusUiState.from(pendingCount = 1, lastSyncedAtMillis = 1_000L)

        assertTrue(state.toString(), state is SyncStatusUiState.Pending)
        assertEquals(1_000L, state.lastSyncedAtMillis)
    }

    @Test
    fun `an empty queue with a history is synced`() {
        val state = SyncStatusUiState.from(pendingCount = 0, lastSyncedAtMillis = 1_000L)

        assertTrue(state.toString(), state is SyncStatusUiState.Synced)
        assertEquals(1_000L, state.lastSyncedAtMillis)
    }

    /**
     * The state that stops the app claiming credit it has not earned: a fresh install
     * has an empty queue and has synced nothing, and must not say "Synced".
     */
    @Test
    fun `a fresh install is neither synced nor pending`() {
        val state = SyncStatusUiState.from(pendingCount = 0, lastSyncedAtMillis = null)

        assertEquals(SyncStatusUiState.NothingToSync, state)
        assertNull(state.lastSyncedAtMillis)
    }

    /** COUNT(*) cannot go negative, but the branch should not depend on that. */
    @Test
    fun `a nonsense count is not treated as a queue`() {
        assertEquals(
            SyncStatusUiState.NothingToSync,
            SyncStatusUiState.from(pendingCount = -1, lastSyncedAtMillis = null),
        )
    }

    /** Epoch zero is the store's "never" sentinel, so it should never arrive here. */
    @Test
    fun `a zero timestamp is still a timestamp once it gets this far`() {
        val state = SyncStatusUiState.from(pendingCount = 0, lastSyncedAtMillis = 0L)

        assertTrue(state.toString(), state is SyncStatusUiState.Synced)
    }
}
