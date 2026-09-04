package com.minesafear.ui.home

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minesafear.R
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.ui.assessment.sampleQuestions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Site-wide safety performance analytics dashboard for supervisors.
 */
@Composable
fun SafetyAnalyticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }

    val workers by repository.observeAllWorkers().collectAsStateWithLifecycle(emptyList())

    // Aggregates assessment results across workers
    val allAssessments by remember(repository) {
        repository.observeResults("local_worker")
    }.collectAsStateWithLifecycle(emptyList())

    val totalMiners = remember(workers) { workers.size.coerceAtLeast(1) }
    val totalAttempts = remember(allAssessments) { allAssessments.size }
    val passedAttempts = remember(allAssessments) { allAssessments.count { it.passed } }
    val passRatePercent = remember(totalAttempts, passedAttempts) {
        if (totalAttempts > 0) (passedAttempts * 100) / totalAttempts else 0
    }
    val avgScorePercent = remember(allAssessments, totalAttempts) {
        if (totalAttempts > 0) (allAssessments.sumOf { it.scorePercent }) / totalAttempts else 0
    }

    // Role-wise breakdown
    val rolesMap = remember(workers) {
        if (workers.isEmpty()) {
            mapOf("Local Worker" to 1)
        } else {
            workers.groupBy { it.jobRole.ifBlank { "Operator" } }.mapValues { it.value.size }
        }
    }

    // Site-wise breakdown
    val sitesMap = remember(workers) {
        if (workers.isEmpty()) {
            mapOf("SITE-1" to 1)
        } else {
            workers.groupBy { it.siteId.ifBlank { "SITE-1" } }.mapValues { it.value.size }
        }
    }

    var exportResultMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(R.string.analytics_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = stringResource(R.string.analytics_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // KPI Summary Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KpiCard(
                title = stringResource(R.string.analytics_total_miners),
                value = totalMiners.toString(),
                modifier = Modifier.weight(1f),
            )
            KpiCard(
                title = stringResource(R.string.analytics_pass_rate),
                value = "$passRatePercent%",
                modifier = Modifier.weight(1f),
            )
        }

        KpiCard(
            title = stringResource(R.string.analytics_avg_score),
            value = "$avgScorePercent%",
            modifier = Modifier.fillMaxWidth(),
        )

        // Visual Progress Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Overall Site Pass Rate",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                LinearProgressIndicator(
                    progress = { passRatePercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "$passedAttempts of $totalAttempts statutory assessment attempts passed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Job Role Breakdown Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.analytics_role_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider()

                rolesMap.forEach { (role, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = "• $role", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "$count miners enrolled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Mine Site ID Breakdown Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.analytics_site_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider()

                sitesMap.forEach { (site, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = "• Mine Site $site", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "$count miners assigned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Missed Hazards Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.analytics_top_hazards),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider()

                val commonHazards = remember(sampleQuestions) {
                    sampleQuestions.mapNotNull { it.hazardTag }.distinct()
                }

                if (commonHazards.isEmpty()) {
                    Text(
                        text = stringResource(R.string.analytics_no_hazards),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    commonHazards.forEach { hazard ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "• $hazard",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "Monitored",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Analytics Summary Export Button
        OutlinedButton(
            onClick = {
                try {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    val reportContent = buildString {
                        appendLine("==================================================")
                        appendLine("       MINESAFEAR SITE SAFETY ANALYTICS REPORT    ")
                        appendLine("==================================================")
                        appendLine("Generated: $dateStr")
                        appendLine()
                        appendLine("KPI SUMMARY:")
                        appendLine("- Total Enrolled Miners: $totalMiners")
                        appendLine("- Total Quiz Attempts: $totalAttempts")
                        appendLine("- Passed Quiz Attempts: $passedAttempts ($passRatePercent%)")
                        appendLine("- Average Assessment Score: $avgScorePercent%")
                        appendLine()
                        appendLine("JOB ROLE DISTRIBUTION:")
                        rolesMap.forEach { (role, count) ->
                            appendLine("- $role: $count miner(s)")
                        }
                        appendLine()
                        appendLine("MINE SITE DISTRIBUTION:")
                        sitesMap.forEach { (site, count) ->
                            appendLine("- Site $site: $count miner(s)")
                        }
                        appendLine()
                        appendLine("MONITORED SAFETY HAZARD TOPICS:")
                        sampleQuestions.mapNotNull { it.hazardTag }.distinct().forEach { tag ->
                            appendLine("- $tag")
                        }
                        appendLine()
                        appendLine("==================================================")
                    }

                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "MineSafeAR_Analytics_Report.txt",
                    )
                    FileOutputStream(file).use { out ->
                        out.write(reportContent.toByteArray())
                    }
                    exportResultMsg = "Analytics report exported to Downloads/MineSafeAR_Analytics_Report.txt"
                } catch (_: Exception) {
                    exportResultMsg = "Failed to export analytics report."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.analytics_export_button))
        }

        exportResultMsg?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
