package com.minesafear.ui.modules.extinguisher

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minesafear.R

@Composable
fun ExtinguisherResultsScreen(
    outcome: ExtinguisherOutcome,
    onRetry: () -> Unit,
    onNextModule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.fire_module_results_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.extinguisher_module_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))

        ScoreBadge(outcome = outcome)

        Spacer(modifier = Modifier.height(24.dp))

        ResultRow(
            labelRes = R.string.fire_module_results_correct,
            value = outcome.correctChoices.toString(),
        )
        HorizontalDivider()
        ResultRow(
            labelRes = R.string.fire_module_results_wrong,
            value = outcome.wrongChoices.toString(),
        )
        HorizontalDivider()
        ResultRow(
            labelRes = R.string.fire_module_results_time,
            value = stringResource(
                R.string.fire_module_elapsed,
                outcome.durationSeconds / 60,
                outcome.durationSeconds % 60,
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (outcome.passed) R.string.extinguisher_results_passed_advice
                else R.string.extinguisher_results_failed_advice
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (outcome.passed) {
            Button(onClick = onNextModule, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.fire_module_next_module))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.fire_module_retry))
            }
        } else {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.fire_module_retry))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onNextModule, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.fire_module_next_module))
            }
        }
    }
}

@Composable
private fun ScoreBadge(outcome: ExtinguisherOutcome) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (outcome.passed) colors.primaryContainer else colors.errorContainer,
        contentColor = if (outcome.passed) colors.onPrimaryContainer else colors.onErrorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    R.string.fire_module_results_score,
                    outcome.score,
                    100,
                ),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (outcome.passed) R.string.fire_module_results_passed
                    else R.string.fire_module_results_failed
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.fire_module_results_pass_mark,
                    70,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun ResultRow(@StringRes labelRes: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
