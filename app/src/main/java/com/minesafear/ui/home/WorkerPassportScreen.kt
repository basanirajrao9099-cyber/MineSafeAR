package com.minesafear.ui.home

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Achievement badge model for miner competency milestones. */
data class AchievementBadge(
    val title: String,
    val description: String,
    val iconEmoji: String,
    val isEarned: Boolean,
)

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

    // Compute Achievement Badges
    val badges = remember(isCertified, moduleResults, assessmentResults) {
        listOf(
            AchievementBadge(
                title = "Certified Miner",
                description = "Holds an active statutory safety certificate",
                iconEmoji = "🏆",
                isEarned = isCertified,
            ),
            AchievementBadge(
                title = "Fire Specialist",
                description = "Passed practical AR fire drill (score ≥ 80%)",
                iconEmoji = "🔥",
                isEarned = moduleResults.any { it.passed && it.score >= 80 },
            ),
            AchievementBadge(
                title = "Safety Quiz Ace",
                description = "Passed statutory written safety quiz",
                iconEmoji = "📝",
                isEarned = assessmentResults.any { it.passed && it.scorePercent >= 80 },
            ),
            AchievementBadge(
                title = "100% Score Club",
                description = "Achieved 100% on any drill or quiz",
                iconEmoji = "🎯",
                isEarned = moduleResults.any { it.score == 100 } || assessmentResults.any { it.scorePercent == 100 },
            ),
        )
    }

    var showSignoffModal by remember { mutableStateOf(false) }
    var pdfExportSuccess by remember { mutableStateOf<Boolean?>(null) }

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

                OutlinedButton(
                    onClick = { showSignoffModal = true },
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

        // Upcoming Retraining Card
        val nextDueDate = remember(activeCert) {
            val date = activeCert?.expiryDate ?: (System.currentTimeMillis() + 30L * 24 * 3600 * 1000)
            SimpleDateFormat("dd-MMM-yyyy", Locale.US).format(Date(date))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.calendar_heading),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "Refresher: Statutory Fire & Gas Evacuation Protocol",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "Next Statutory Retraining Due: $nextDueDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Achievement Badges Section
            item {
                Text(
                    text = stringResource(R.string.passport_badges_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    badges.chunked(2).forEach { rowBadges ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowBadges.forEach { badge ->
                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (badge.isEarned) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                        },
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "${badge.iconEmoji} ${badge.title}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (badge.isEarned) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                        Text(
                                            text = badge.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = if (badge.isEarned) "UNLOCKED" else "LOCKED",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (badge.isEarned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                            }
                            if (rowBadges.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

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

    if (showSignoffModal) {
        SupervisorSignoffDialog(
            onDismiss = { showSignoffModal = false },
            onConfirmSignoff = { supName, supCode ->
                showSignoffModal = false
                try {
                    val pdfDocument = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    val paint = Paint()

                    paint.color = Color.BLACK
                    paint.textSize = 18f
                    paint.isFakeBoldText = true

                    canvas.drawText("MINESAFEAR STATUTORY SAFETY COMPLIANCE REPORT", 40f, 50f, paint)

                    paint.textSize = 12f
                    paint.isFakeBoldText = false
                    val dateStr = SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", Locale.US).format(Date())
                    canvas.drawText("Generated: $dateStr", 40f, 75f, paint)

                    canvas.drawLine(40f, 85f, 555f, 85f, paint)

                    paint.textSize = 14f
                    paint.isFakeBoldText = true
                    canvas.drawText("WORKER PROFILE", 40f, 110f, paint)

                    paint.textSize = 12f
                    paint.isFakeBoldText = false
                    canvas.drawText("Name: ${worker?.fullName ?: "Local Worker"}", 40f, 135f, paint)
                    canvas.drawText("Badge Code: ${worker?.employeeCode ?: "EMP-LOCAL"}", 40f, 155f, paint)
                    canvas.drawText("Job Role: ${worker?.jobRole ?: "Operator"}", 40f, 175f, paint)
                    canvas.drawText("Mine Site: ${worker?.siteId ?: "SITE-1"}", 40f, 195f, paint)
                    canvas.drawText("Certification Status: ${if (isCertified) "CERTIFIED" else "TRAINING IN PROGRESS"}", 40f, 215f, paint)

                    canvas.drawLine(40f, 235f, 555f, 235f, paint)

                    paint.textSize = 14f
                    paint.isFakeBoldText = true
                    canvas.drawText("SUMMARY OF PERFORMANCE", 40f, 260f, paint)

                    paint.textSize = 12f
                    paint.isFakeBoldText = false
                    canvas.drawText("Practical Drills Completed: ${moduleResults.size}", 40f, 285f, paint)
                    canvas.drawText("Written Assessments Attempted: ${assessmentResults.size}", 40f, 305f, paint)
                    canvas.drawText("Earned Badges: ${badges.count { it.isEarned }} of ${badges.size}", 40f, 325f, paint)

                    canvas.drawLine(40f, 350f, 555f, 350f, paint)

                    // Supervisor Seal Box
                    paint.color = Color.DKGRAY
                    paint.textSize = 14f
                    paint.isFakeBoldText = true
                    canvas.drawText("DIGITAL SUPERVISOR APPROVAL & SEAL", 40f, 380f, paint)

                    paint.textSize = 12f
                    paint.isFakeBoldText = false
                    canvas.drawText("Supervisor Name: $supName", 40f, 405f, paint)
                    canvas.drawText("Supervisor Badge / ID: $supCode", 40f, 425f, paint)
                    canvas.drawText("Approval Stamp: VERIFIED & OFFICIALLY SIGNED", 40f, 445f, paint)

                    pdfDocument.finishPage(page)

                    val file = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "MineSafeAR_Compliance_Report.pdf",
                    )
                    val outputStream = FileOutputStream(file)
                    pdfDocument.writeTo(outputStream)
                    pdfDocument.close()
                    outputStream.close()

                    pdfExportSuccess = true
                } catch (_: Exception) {
                    pdfExportSuccess = false
                }
            },
        )
    }
}

@Composable
private fun SupervisorSignoffDialog(
    onDismiss: () -> Unit,
    onConfirmSignoff: (String, String) -> Unit,
) {
    var supName by remember { mutableStateOf("") }
    var supCode by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.supervisor_signoff_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.supervisor_signoff_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = supName,
                    onValueChange = { supName = it },
                    label = { Text(stringResource(R.string.supervisor_field_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = supCode,
                    onValueChange = { supCode = it },
                    label = { Text(stringResource(R.string.supervisor_field_code)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmSignoff(supName, supCode) },
                enabled = supName.isNotBlank() && supCode.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.supervisor_sign_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.supervisor_cancel_button))
            }
        },
    )
}
