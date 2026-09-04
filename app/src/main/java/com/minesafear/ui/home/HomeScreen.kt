package com.minesafear.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minesafear.R
import com.minesafear.ar.ARTestActivity
import com.minesafear.ar.openings.ExitDetectionActivity
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.sync.SyncStatusStore
import com.minesafear.ui.theme.MineSafeArTheme

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }

    // Both sources are observed rather than read once: a sync can complete while
    // this screen is on top, and an indicator that only refreshes on navigation
    // would sit on "Pending" after the upload had already happened.
    val pendingCount by remember(repository) { repository.observePendingSyncCount() }
        .collectAsStateWithLifecycle(0)
    val lastSyncedAt by remember(context) { SyncStatusStore.observeLastSyncedAt(context) }
        .collectAsStateWithLifecycle(null)

    val syncStatus = SyncStatusUiState.from(pendingCount, lastSyncedAt)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        SyncStatusIndicator(
            state = syncStatus,
            modifier = Modifier.padding(top = 24.dp),
        )
        // Temporary: the only way into the AR harness, which has no nav
        // destination of its own. Remove this button with ARTestActivity.
        FilledTonalButton(
            onClick = { context.startActivity(Intent(context, ARTestActivity::class.java)) },
            modifier = Modifier.padding(top = 32.dp),
        ) {
            Text(text = stringResource(R.string.ar_test_open))
        }
        // Egress detection. Needs no ARCore, so it stays usable on this phone.
        FilledTonalButton(
            onClick = { context.startActivity(Intent(context, ExitDetectionActivity::class.java)) },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(text = stringResource(R.string.exit_detect_open))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    MineSafeArTheme {
        // Opens the real database, so this renders in the emulator-backed preview
        // but not in a plain layout preview. Kept as-is because the screen's own
        // chrome is trivial; SyncStatusIndicator has previews of every sync state.
        HomeScreen()
    }
}
