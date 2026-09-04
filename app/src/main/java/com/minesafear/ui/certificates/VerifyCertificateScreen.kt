package com.minesafear.ui.certificates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.minesafear.R
import com.minesafear.certificate.CertificatePayload
import com.minesafear.certificate.CertificateVerification
import com.minesafear.certificate.CertificateVerifier
import com.minesafear.certificate.QrCodeScanner
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.repository.TrainingRepository

/**
 * Supervisor-facing screen: scan a worker's certificate QR code and get a
 * VALID / INVALID / EXPIRED verdict with no network.
 *
 * The verdict comes from [CertificateVerifier], which recomputes the signature from
 * the scanned fields. The local database is consulted only to put a name to a
 * certificate that happens to have been issued on this device — it is never what
 * decides the verdict, because the point is verifying somebody else's card.
 */
@Composable
fun VerifyCertificateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }

    var scanState by remember { mutableStateOf<ScanState>(ScanState.Idle) }

    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        scanState = if (contents == null) {
            // Null contents is a cancelled scan, not a failed one.
            ScanState.Cancelled
        } else {
            ScanState.Checked(CertificateVerifier.verify(contents, System.currentTimeMillis()))
        }
    }
    val scanPrompt = stringResource(R.string.verify_scan_prompt)

    val payload = (scanState as? ScanState.Checked)?.verification?.payload
    var localRecord by remember { mutableStateOf<CertificateEntity?>(null) }
    LaunchedEffect(payload?.certId) {
        val certId = payload?.certId
        localRecord = if (certId == null) {
            null
        } else {
            runCatching { repository.findCertificate(certId) }.getOrNull()
        }
    }

    LaunchedEffect(scanState) {
        val checked = scanState as? ScanState.Checked
        if (checked != null) {
            val certId = checked.verification.payload?.certId ?: "UNKNOWN"
            val verdict = when (checked.verification) {
                is CertificateVerification.Valid -> "VALID"
                is CertificateVerification.Expired -> "EXPIRED"
                is CertificateVerification.Invalid -> "INVALID"
            }
            com.minesafear.data.InspectionAuditLogStore.logInspection(certId, verdict)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CertificateScreenHeader(
            title = stringResource(R.string.verify_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.verify_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { launcher.launch(QrCodeScanner.options(scanPrompt)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (scanState == ScanState.Idle) R.string.verify_scan_button
                        else R.string.verify_scan_again,
                    ),
                )
            }

            when (val state = scanState) {
                ScanState.Idle -> Unit

                ScanState.Cancelled -> Text(
                    text = stringResource(R.string.verify_cancelled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is ScanState.Checked -> {
                    VerdictBadge(state.verification)
                    VerdictExplanation(state.verification)
                    state.verification.payload?.let { scanned ->
                        ScannedDetails(payload = scanned, localRecord = localRecord)
                    }
                }
            }

            InspectionAuditSection()
        }
    }
}

@Composable
private fun VerdictBadge(verification: CertificateVerification) {
    val labelRes: Int
    val container: Color
    val onContainer: Color
    when (verification) {
        is CertificateVerification.Valid -> {
            labelRes = R.string.verify_result_valid
            container = MaterialTheme.colorScheme.primaryContainer
            onContainer = MaterialTheme.colorScheme.onPrimaryContainer
        }

        is CertificateVerification.Expired -> {
            labelRes = R.string.verify_result_expired
            container = MaterialTheme.colorScheme.tertiaryContainer
            onContainer = MaterialTheme.colorScheme.onTertiaryContainer
        }

        is CertificateVerification.Invalid -> {
            labelRes = R.string.verify_result_invalid
            container = MaterialTheme.colorScheme.errorContainer
            onContainer = MaterialTheme.colorScheme.onErrorContainer
        }
    }

    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = onContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
        )
    }
}

@Composable
private fun VerdictExplanation(verification: CertificateVerification) {
    val text = when (verification) {
        is CertificateVerification.Valid -> stringResource(R.string.verify_valid_detail)

        is CertificateVerification.Expired -> stringResource(
            R.string.verify_expired_detail,
            rememberFormattedDate(verification.payload.expiryDate),
        )

        is CertificateVerification.Invalid -> stringResource(
            when (verification.reason) {
                CertificateVerification.Invalid.Reason.NOT_A_CERTIFICATE ->
                    R.string.verify_invalid_not_certificate

                CertificateVerification.Invalid.Reason.SIGNATURE_MISMATCH ->
                    R.string.verify_invalid_signature

                CertificateVerification.Invalid.Reason.EXPIRY_ALTERED ->
                    R.string.verify_invalid_expiry
            },
        )
    }

    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * What the scanned code claims. Headed as *scanned* rather than presented as fact,
 * because on an INVALID card these values are exactly what cannot be trusted.
 */
@Composable
private fun ScannedDetails(
    payload: CertificatePayload,
    localRecord: CertificateEntity?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.verify_scanned_details),
                style = MaterialTheme.typography.titleSmall,
            )

            CertificateDetailRow(
                label = stringResource(R.string.certificate_holder_label),
                value = localRecord?.userName
                    ?: stringResource(R.string.verify_no_local_record),
            )
            CertificateDetailRow(
                label = stringResource(R.string.certificate_score_label),
                value = stringResource(R.string.certificate_score, payload.score),
            )
            CertificateDetailRow(
                label = stringResource(R.string.certificate_issued_label),
                value = rememberFormattedDate(payload.issuedDate),
            )
            CertificateDetailRow(
                label = stringResource(R.string.certificate_expires_label),
                value = rememberFormattedDate(payload.expiryDate),
            )
            CertificateDetailRow(
                label = stringResource(R.string.certificate_id_label),
                value = payload.certId,
            )

            val context = LocalContext.current
            var clearanceMsg by remember { mutableStateOf<String?>(null) }

            Button(
                onClick = {
                    try {
                        val pdfDocument = android.graphics.pdf.PdfDocument()
                        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 420, 1).create()
                        val page = pdfDocument.startPage(pageInfo)
                        val canvas = page.canvas
                        val paint = android.graphics.Paint()

                        paint.color = android.graphics.Color.BLACK
                        paint.textSize = 16f
                        paint.isFakeBoldText = true

                        canvas.drawText("MINESAFEAR SAFETY INSPECTION CLEARANCE SLIP", 30f, 40f, paint)

                        paint.textSize = 12f
                        paint.isFakeBoldText = false
                        val dateStr = java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                        canvas.drawText("Inspection Date: $dateStr", 30f, 65f, paint)

                        canvas.drawLine(30f, 75f, 565f, 75f, paint)

                        canvas.drawText("Holder: ${localRecord?.userName ?: "Verified Miner"}", 30f, 100f, paint)
                        canvas.drawText("Certificate ID: ${payload.certId}", 30f, 125f, paint)
                        canvas.drawText("Competency Score: ${payload.score}%", 30f, 150f, paint)
                        canvas.drawText("Verification Verdict: VALIDATED & CLEARED", 30f, 175f, paint)

                        pdfDocument.finishPage(page)

                        val file = java.io.File(
                            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                            "MineSafeAR_Inspection_Clearance.pdf",
                        )
                        val outputStream = java.io.FileOutputStream(file)
                        pdfDocument.writeTo(outputStream)
                        pdfDocument.close()
                        outputStream.close()

                        clearanceMsg = "Clearance slip exported to Downloads/MineSafeAR_Inspection_Clearance.pdf"
                    } catch (_: Exception) {
                        clearanceMsg = "Failed to export clearance slip."
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(text = stringResource(R.string.clearance_slip_button))
            }

            clearanceMsg?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun InspectionAuditSection() {
    val logs by com.minesafear.data.InspectionAuditLogStore.logs.collectAsStateWithLifecycle()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.verify_audit_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.verify_audit_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.forEach { log ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Cert: ${log.certId.take(8)}...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = log.verdict,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = when (log.verdict) {
                                "VALID" -> MaterialTheme.colorScheme.primary
                                "EXPIRED" -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface ScanState {
    data object Idle : ScanState
    data object Cancelled : ScanState
    data class Checked(val verification: CertificateVerification) : ScanState
}
