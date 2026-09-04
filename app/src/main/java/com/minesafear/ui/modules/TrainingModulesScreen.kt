package com.minesafear.ui.modules

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minesafear.R

/**
 * The catalogue of training modules.
 *
 * One real entry so far. The list is hard-coded rather than read from
 * `training_modules`, because nothing seeds that table yet and a screen that shows
 * an empty list on a fresh install would be worse than one that shows what ships in
 * the APK. Move to the database once there is a bundled catalogue to seed it from.
 */
@Composable
fun TrainingModulesScreen(
    onStartFireModule: () -> Unit,
    onStartExtinguisherModule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.title_modules),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))

        ModuleCard(
            titleRes = R.string.fire_module_title,
            summaryRes = R.string.fire_module_card_summary,
            onStart = onStartFireModule,
        )

        Spacer(modifier = Modifier.height(16.dp))

        ModuleCard(
            titleRes = R.string.extinguisher_module_title,
            summaryRes = R.string.extinguisher_module_summary,
            onStart = onStartExtinguisherModule,
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.modules_more_coming),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModuleCard(
    @StringRes titleRes: Int,
    @StringRes summaryRes: Int,
    onStart: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(summaryRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStart,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(text = stringResource(R.string.modules_start))
            }
        }
    }
}
