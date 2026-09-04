package com.minesafear.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.minesafear.certificate.CertificatePolicy
import com.minesafear.data.ActiveWorkerPreference
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.repository.TrainingRepository
import com.minesafear.ui.certificates.rememberFormattedDate

/**
 * Detailed safety passport and complete competency transcript for a miner.
 */
@Composable
fun WorkerPassportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }
    val workerId = remember(context) { ActiveWorkerPreference.getActiveWorkerId(context) }

    val worker by remember(repository, workerId) { repository.observeWorker(workerId) }
        .collectAsStateWithLifecycle(null)
    val moduleResults by remember(repository, workerId) { repository.observeModuleResults(workerId) }
        .collectAsStateWithLifecycle(emptyList())
    val assessmentResults by remember(repository, workerId) { repository.observeResults(workerId) }
        .collectAsStateWithLifecycle(emptyList())
    val certificates by remember(repository, workerId) { repository.observeCertificates(workerId) }
        .collectAsStateWithLifecycle(emptyList())

    val now = remember(certificates) { System.currentTimeMillis() }
    val activeCert = remember(certificates) { certificates.maxByOrNull { it.issuedDate } }
    val isCertified = remember(activeCert, now) {
        (activeCert != null) && !CertificatePolicy.isExpiredAt(activeCert.expiryDate, now)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                text = stringResource(R.string.passport_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }

        // Passport Header Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = worker?.fullName?.ifBlank { stringResource(R.string.worker_unprovisioned_label) }
                            ?: stringResource(R.string.worker_unprovisioned_label),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )

                    Surface(
                        color = if (isCertified) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(if (isCertified) R.string.passport_badge_certified else R.string.passport_badge_uncertified),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isCertified) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                worker?.let { w ->
                    Text(
                        text = "Badge: ${w.employeeCode} • Role: ${w.jobRole}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Mine Site: ${w.siteId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                var pdfExportSuccess by remember { mutableStateOf<Boolean?>(null) }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        try {
                            val pdfDocument = android.graphics.pdf.PdfDocument()
                            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, 1).create()
                            val page = pdfDocument.startPage(pageInfo)
                            val canvas = page.canvas
                            val paint = android.graphics.Paint()
                            paint.color = android.graphics.Color.BLACK
                            paint.textSize = 16f

                            canvas.drawText("MineSafeAR Official Safety Compliance Report", 40f, 50f, paint)
                            canvas.drawText("Worker: ${worker?.fullName ?: "Local Worker"}", 40f, 90f, paint)
                            canvas.drawText("Badge Code: ${worker?.employeeCode ?: "EMP-LOCAL"}", 40f, 120f, paint)
                            canvas.drawText("Certification Status: ${if (isCertified) "CERTIFIED" else "TRAINING IN PROGRESS"}", 40f, 150f, paint)
                            canvas.drawText("Practical Drills Completed: ${moduleResults.size}", 40f, 180f, paint)
                            canvas.drawText("Written Assessments Taken: ${assessmentResults.size}", 40f, 210f, paint)

                            pdfDocument.finishPage(page)

                            val file = java.io.File(
                                context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                                "MineSafeAR_Compliance_Report.pdf",
                            )
                            val outputStream = java.io.FileOutputStream(file)
                            pdfDocument.writeTo(outputStream)
                            pdfDocument.close()
                            outputStream.close()

                            pdfExportSuccess = true
                        } catch (_: Exception) {
                            pdfExportSuccess = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.compliance_pdf_button))
                }

                pdfExportSuccess?.let { success ->
                    Text(
                        text = stringResource(
                            if (success) R.string.compliance_pdf_saved
                            else R.string.compliance_pdf_failed,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Practical AR Drills Section
            item {
                Text(
                    text = stringResource(R.string.passport_drills_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (moduleResults.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.passport_no_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(moduleResults, key = { it.id }) { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = result.moduleId.replace('_', ' ').uppercase(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (result.passed) "PASSED" else "FAILED",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = "Score: ${result.score}% • Duration: ${result.durationSeconds}s",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = rememberFormattedDate(result.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Written Assessments Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.passport_quizzes_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (assessmentResults.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.passport_no_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(assessmentResults, key = { it.id }) { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Quiz Attempt #${result.attemptNumber}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (result.passed) "PASSED" else "FAILED",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = "Score: ${result.scorePercent}% (${result.correctAnswers}/${result.totalQuestions} correct)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = rememberFormattedDate(result.submittedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
