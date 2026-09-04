package com.minesafear.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.minesafear.R
import com.minesafear.ui.theme.MineSafeArTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Home screen says about sync.
 *
 * A value type derived by [from] rather than three booleans read at the call site,
 * so the state machine is unit-testable without a composition — see
 * `SyncStatusUiStateTest`.
 */
sealed interface SyncStatusUiState {

    /**
     * When records last reached the server, or null if they never have. Carried on
     * every state because it is worth showing next to a pending queue too: "pending,
     * and the last upload was three days ago" is a different situation from
     * "pending, and the last upload was a minute ago".
     */
    val lastSyncedAtMillis: Long?

    /** Records are queued. Nothing for the worker to do — this is not an error. */
    data class Pending(
        /**
         * Not shown. "3 items pending" invites a worker to wonder which three and
         * what to do about them, when the answer is "nothing, walk to the surface".
         * Kept on the state because it is what the tests assert on and what makes a
         * logged state readable.
         */
        val count: Int,
        override val lastSyncedAtMillis: Long?,
    ) : SyncStatusUiState

    /** Queue empty and something has been uploaded, so the timestamp is non-null. */
    data class Synced(
        override val lastSyncedAtMillis: Long,
    ) : SyncStatusUiState

    /**
     * Queue empty and nothing has ever been uploaded — a fresh install.
     *
     * A distinct state rather than folding into [Synced] with a blank timestamp: a
     * phone that has never uploaded anything is not "Synced", and telling a worker
     * it is would be the app's first lie to them.
     */
    data object NothingToSync : SyncStatusUiState {
        override val lastSyncedAtMillis: Long? get() = null
    }

    companion object {
        fun from(pendingCount: Int, lastSyncedAtMillis: Long?): SyncStatusUiState = when {
            pendingCount > 0 -> Pending(pendingCount, lastSyncedAtMillis)
            lastSyncedAtMillis != null -> Synced(lastSyncedAtMillis)
            else -> NothingToSync
        }
    }
}

/**
 * The sync line on Home: an icon, a one-line status, and a timestamp when there is
 * one.
 *
 * Read-only on purpose. There is no "sync now" button because tapping it offline
 * would do nothing a worker could perceive, and [com.minesafear.sync.SyncScheduler]
 * has already queued the upload — the honest affordance is to say what is happening
 * and let WorkManager act on it.
 */
@Composable
fun SyncStatusIndicator(
    state: SyncStatusUiState,
    modifier: Modifier = Modifier,
) {
    val (icon, message) = when (state) {
        is SyncStatusUiState.Pending ->
            Icons.Default.Refresh to stringResource(R.string.sync_status_pending)

        is SyncStatusUiState.Synced ->
            Icons.Default.CheckCircle to stringResource(R.string.sync_status_synced)

        SyncStatusUiState.NothingToSync ->
            Icons.Default.Info to stringResource(R.string.sync_status_never)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                // Decorative: the text beside it says the same thing, and a screen
                // reader announcing "refresh icon, pending sync" is worse than one.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.lastSyncedAtMillis?.let { millis ->
                    Text(
                        text = stringResource(
                            R.string.sync_status_last_synced,
                            rememberFormattedSyncTime(millis),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Date *and* time, unlike the certificate screens' date-only formatter: sync
 * happens several times a shift, so a bare date would read as stale after the first
 * upload of the day.
 *
 * Both parts are [DateFormat.SHORT] to keep the line inside one row on a small
 * screen, and the whole thing is keyed on the locale so a language change in
 * Settings reformats instead of leaving the previous locale's month names in place.
 */
@Composable
private fun rememberFormattedSyncTime(epochMillis: Long): String {
    val locale = Locale.getDefault()
    return remember(epochMillis, locale) {
        DateFormat
            .getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
            .format(Date(epochMillis))
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncStatusIndicatorPreview() {
    MineSafeArTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            SyncStatusIndicator(SyncStatusUiState.NothingToSync)
            SyncStatusIndicator(
                SyncStatusUiState.Pending(count = 2, lastSyncedAtMillis = null),
                modifier = Modifier.padding(top = 12.dp),
            )
            SyncStatusIndicator(
                // Fixed instant rather than System.currentTimeMillis(), so the
                // preview renders the same thing every time it is re-rendered.
                SyncStatusUiState.Synced(lastSyncedAtMillis = 1_760_000_000_000L),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
