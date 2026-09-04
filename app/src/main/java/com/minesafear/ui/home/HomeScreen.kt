package com.minesafear.ui.home

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minesafear.R
import com.minesafear.ar.ARTestActivity
import com.minesafear.ar.openings.ExitDetectionActivity
import com.minesafear.data.ActiveWorkerPreference
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.entity.WorkerEntity
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.sync.SyncScheduler
import com.minesafear.sync.SyncStatusStore
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun HomeScreen(
    onOpenPassport: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }
    val scope = rememberCoroutineScope()

    var activeWorkerId by remember {
        mutableStateOf(ActiveWorkerPreference.getActiveWorkerId(context))
    }

    val activeWorker by remember(repository, activeWorkerId) {
        repository.observeWorker(activeWorkerId)
    }.collectAsStateWithLifecycle(null)

    val pendingCount by remember(repository) { repository.observePendingSyncCount() }
        .collectAsStateWithLifecycle(0)
    val lastSyncedAt by remember(context) { SyncStatusStore.observeLastSyncedAt(context) }
        .collectAsStateWithLifecycle(null)

    val syncStatus = SyncStatusUiState.from(pendingCount, lastSyncedAt)
    var showWorkerDialog by remember { mutableStateOf(value = false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
        )

        // Active Miner Profile Card
        ActiveWorkerCard(
            worker = activeWorker,
            onSwitchWorker = { showWorkerDialog = true },
            onOpenPassport = onOpenPassport,
        )

        // Certificate Expiry Alert (if expiring or expired)
        val activeCertificates by remember(repository, activeWorkerId) {
            repository.observeCertificates(activeWorkerId)
        }.collectAsStateWithLifecycle(emptyList())

        val nowMillis = remember(activeCertificates) { System.currentTimeMillis() }
        val latestActiveCert = remember(activeCertificates) { activeCertificates.maxByOrNull { it.issuedDate } }
        val daysUntilExpiry = remember(latestActiveCert, nowMillis) {
            latestActiveCert?.let { ((it.expiryDate - nowMillis) / (1000 * 60 * 60 * 24)).toInt() }
        }

        if ((latestActiveCert != null) && (daysUntilExpiry != null) && (daysUntilExpiry <= 30)) {
            CertExpiryHomeBanner(daysRemaining = daysUntilExpiry)
        }

        // Safety Competency Card
        CompetencyCard(
            repository = repository,
            workerId = activeWorkerId,
            onOpenPassport = onOpenPassport,
        )

        // Sync Status & Trigger
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SyncStatusIndicator(state = syncStatus)

                OutlinedButton(
                    onClick = { SyncScheduler.requestSyncNow(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "Sync Now")
                }

                Button(
                    onClick = onOpenAnalytics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.analytics_open_button))
                }
            }
        }

        // Temporary AR harness buttons
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "AR Diagnostics & Harness",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(context, ARTestActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.ar_test_open))
                }
                FilledTonalButton(
                    onClick = { context.startActivity(Intent(context, ExitDetectionActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.exit_detect_open))
                }
            }
        }
    }

    if (showWorkerDialog) {
        WorkerProfileDialog(
            repository = repository,
            activeWorkerId = activeWorkerId,
            onSelectWorker = { selectedId ->
                activeWorkerId = selectedId
                ActiveWorkerPreference.setActiveWorkerId(context, selectedId)
                showWorkerDialog = false
            },
            onDismiss = { showWorkerDialog = false },
            onAddWorker = { newWorker ->
                scope.launch {
                    repository.upsertWorker(newWorker)
                    activeWorkerId = newWorker.id
                    ActiveWorkerPreference.setActiveWorkerId(context, newWorker.id)
                    showWorkerDialog = false
                }
            },
        )
    }
}

@Composable
private fun ActiveWorkerCard(
    worker: WorkerEntity?,
    onSwitchWorker: () -> Unit,
    onOpenPassport: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPassport)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.worker_card_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            val name = worker?.fullName?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.worker_unprovisioned_label)
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            worker?.let { w ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Badge: ${w.employeeCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Role: ${w.jobRole}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = onSwitchWorker,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.worker_card_switch))
            }
        }
    }
}

@Composable
private fun WorkerProfileDialog(
    repository: TrainingRepository,
    activeWorkerId: String,
    onSelectWorker: (String) -> Unit,
    onAddWorker: (WorkerEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val workers by repository.observeAllWorkers().collectAsStateWithLifecycle(emptyList())
    var isAddingNew by remember { mutableStateOf(value = false) }

    var fullName by remember { mutableStateOf("") }
    var employeeCode by remember { mutableStateOf("") }
    var jobRole by remember { mutableStateOf("") }
    var siteId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isAddingNew) R.string.worker_dialog_add_title
                    else R.string.worker_dialog_title,
                ),
            )
        },
        text = {
            if (isAddingNew) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fullName,
                        onValueChange = { fullName = it },
                        label = { Text(stringResource(R.string.worker_field_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = employeeCode,
                        onValueChange = { employeeCode = it },
                        label = { Text(stringResource(R.string.worker_field_code)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = jobRole,
                        onValueChange = { jobRole = it },
                        label = { Text(stringResource(R.string.worker_field_role)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = siteId,
                        onValueChange = { siteId = it },
                        label = { Text(stringResource(R.string.worker_field_site)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    ) {
                        item {
                            val isDefaultSelected = activeWorkerId == TrainingRepository.UNPROVISIONED_USER_ID
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectWorker(TrainingRepository.UNPROVISIONED_USER_ID) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = isDefaultSelected, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.worker_unprovisioned_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            HorizontalDivider()
                        }

                        items(workers, key = { it.id }) { worker ->
                            val isSelected = worker.id == activeWorkerId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectWorker(worker.id) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = worker.fullName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "${worker.employeeCode} • ${worker.jobRole}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { isAddingNew = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.worker_card_register))
                    }
                }
            }
        },
        confirmButton = {
            if (isAddingNew) {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val newWorker = WorkerEntity(
                            id = UUID.randomUUID().toString(),
                            employeeCode = employeeCode.ifBlank { "EMP-${System.currentTimeMillis() % 10000}" },
                            fullName = fullName.ifBlank { "Miner ${System.currentTimeMillis() % 1000}" },
                            siteId = siteId.ifBlank { "SITE-1" },
                            jobRole = jobRole.ifBlank { "Operator" },
                            preferredLanguage = "en",
                            createdAt = now,
                            updatedAt = now,
                        )
                        onAddWorker(newWorker)
                    },
                    enabled = fullName.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.worker_button_save))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(text = "Close")
                }
            }
        },
        dismissButton = {
            if (isAddingNew) {
                TextButton(onClick = { isAddingNew = false }) {
                    Text(text = stringResource(R.string.certificate_back))
                }
            }
        },
    )
}

@Composable
private fun CompetencyCard(
    repository: TrainingRepository,
    workerId: String,
    onOpenPassport: () -> Unit = {},
) {
    val moduleResults by remember(repository, workerId) {
        repository.observeModuleResults(workerId)
    }.collectAsStateWithLifecycle(emptyList())

    val assessmentResults by remember(repository, workerId) {
        repository.observeResults(workerId)
    }.collectAsStateWithLifecycle(emptyList())

    val certificates by remember(repository, workerId) {
        repository.observeCertificates(workerId)
    }.collectAsStateWithLifecycle(emptyList())

    val now = remember(certificates) { System.currentTimeMillis() }
    val completedDrills = remember(moduleResults) {
        moduleResults.filter { it.passed }.map { it.moduleId }.distinct().size
    }
    val latestAssessment = remember(assessmentResults) {
        assessmentResults.maxByOrNull { it.submittedAt }
    }
    val activeCert = remember(certificates) {
        certificates.maxByOrNull { it.issuedDate }
    }
    val certExpired = remember(activeCert, now) {
        (activeCert != null) && com.minesafear.certificate.CertificatePolicy.isExpiredAt(activeCert.expiryDate, now)
    }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPassport)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_competency_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Surface(
                    color = when {
                        (activeCert != null) && !certExpired -> MaterialTheme.colorScheme.primaryContainer
                        certExpired -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = stringResource(
                            when {
                                (activeCert != null) && !certExpired -> R.string.home_competency_status_certified
                                certExpired -> R.string.home_competency_status_expired
                                else -> R.string.home_competency_status_uncertified
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            (activeCert != null) && !certExpired -> MaterialTheme.colorScheme.onPrimaryContainer
                            certExpired -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.home_competency_drills, completedDrills),
                style = MaterialTheme.typography.bodyMedium,
            )

            val assessmentText = when {
                latestAssessment == null -> stringResource(R.string.home_competency_no_test)
                latestAssessment.passed -> stringResource(R.string.home_competency_passed, latestAssessment.scorePercent)
                else -> stringResource(R.string.home_competency_failed, latestAssessment.scorePercent)
            }

            Text(
                text = stringResource(R.string.home_competency_assessment, assessmentText),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CertExpiryHomeBanner(daysRemaining: Int) {
    val isExpired = daysRemaining <= 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpired) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.cert_renewal_banner_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isExpired) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )

            Text(
                text = if (isExpired) {
                    stringResource(R.string.cert_renewal_banner_expired)
                } else {
                    stringResource(R.string.cert_renewal_banner_expiring, daysRemaining)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isExpired) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }
    }
}
