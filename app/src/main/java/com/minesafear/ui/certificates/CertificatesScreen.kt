package com.minesafear.ui.certificates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import com.minesafear.R
import com.minesafear.certificate.CertificateIssuer
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.sync.SyncScheduler
import kotlinx.coroutines.launch

/**
 * Every certificate held on this device, newest first, with valid/expired legible
 * without opening anything.
 *
 * ## Why the issue button lives here
 *
 * "Generate certificate" belongs on an Assessment Summary screen, next to the
 * per-module breakdown that justifies it. That screen needs `AssessmentEngine`,
 * which does not exist yet, so the button sits here for now and calls the same
 * [CertificateIssuer] entry point it will call from there — moving it later is a
 * matter of deleting this card, not rewriting the issue path.
 */
@Composable
fun CertificatesScreen(
    onOpenCertificate: (String) -> Unit,
    onVerifyCertificate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }
    val scope = rememberCoroutineScope()
    val userId = remember(context) { com.minesafear.data.ActiveWorkerPreference.getActiveWorkerId(context) }

    val certificates by remember(repository, userId) { repository.observeCertificates(userId) }
        .collectAsStateWithLifecycle(emptyList())
    val results by remember(repository, userId) { repository.observeModuleResults(userId) }
        .collectAsStateWithLifecycle(emptyList())
    val worker by remember(repository, userId) { repository.observeWorker(userId) }
        .collectAsStateWithLifecycle(null)

    val snapshot = remember(results) { CertificateIssuer.snapshot(results, userId) }
    val holderName = worker?.fullName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.certificate_unknown_holder)

    // Sampled once per data change rather than per frame: expiry is a date, so a
    // ticking clock would buy nothing and cost a recomposition a second.
    val now = remember(certificates) { System.currentTimeMillis() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.title_certificates),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        item {
            IssueCard(
                snapshot = snapshot,
                onIssue = {
                    scope.launch {
                        val certificate = CertificateIssuer.issueAndSave(
                            repository = repository,
                            userId = userId,
                            userName = holderName,
                            snapshot = snapshot,
                            nowMillis = System.currentTimeMillis(),
                        )
                        // Null means ineligible or a failed write; the list simply
                        // does not gain a row, and no id is handed to navigation
                        // that has nothing behind it.
                        if (certificate != null) {
                            // Queued from here rather than from CertificateIssuer,
                            // which takes no Context. Waits on its network
                            // constraint, so it is safe to call underground.
                            SyncScheduler.requestSyncNow(context)
                            onOpenCertificate(certificate.certId)
                        }
                    }
                },
                onVerify = onVerifyCertificate,
            )
        }

        if (certificates.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.certificates_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            items(items = certificates, key = { it.certId }) { certificate ->
                CertificateCard(
                    certificate = certificate,
                    nowMillis = now,
                    onClick = { onOpenCertificate(certificate.certId) },
                )
            }
        }
    }
}

@Composable
private fun IssueCard(
    snapshot: CertificateIssuer.CertificationSnapshot,
    onIssue: () -> Unit,
    onVerify: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val summary = when {
                snapshot.moduleScores.isEmpty() ->
                    stringResource(R.string.certificates_no_results)

                snapshot.eligible ->
                    stringResource(R.string.certificates_eligible, snapshot.averageScore)

                else -> stringResource(
                    R.string.certificates_not_eligible,
                    snapshot.averageScore,
                    CertificateIssuer.ELIGIBILITY_THRESHOLD_PERCENT,
                )
            }
            Text(text = summary, style = MaterialTheme.typography.bodyLarge)

            if (snapshot.moduleScores.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.certificates_modules_counted,
                        snapshot.moduleScores.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = onIssue,
                enabled = snapshot.eligible,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.certificates_issue_button))
            }
            OutlinedButton(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.certificates_verify_button))
            }
        }
    }
}

@Composable
private fun CertificateCard(
    certificate: CertificateEntity,
    nowMillis: Long,
    onClick: () -> Unit,
) {
    val expired = certificate.isExpiredAt(nowMillis)

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = certificate.userName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(expired = expired)
            }

            CertificateDetailRow(
                label = stringResource(R.string.certificate_score_label),
                value = stringResource(R.string.certificate_score, certificate.score),
            )
            CertificateDetailRow(
                label = stringResource(R.string.certificate_issued_label),
                value = rememberFormattedDate(certificate.issuedDate),
            )
            CertificateDetailRow(
                label = stringResource(
                    if (expired) R.string.certificate_expired_label
                    else R.string.certificate_expires_label,
                ),
                value = rememberFormattedDate(certificate.expiryDate),
            )

            Text(
                text = stringResource(R.string.certificate_open),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StatusChip(expired: Boolean) {
    Surface(
        color = if (expired) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(
                if (expired) R.string.certificate_status_expired
                else R.string.certificate_status_valid,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (expired) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
