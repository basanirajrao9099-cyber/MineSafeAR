package com.minesafear.ui.certificates

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.minesafear.R
import com.minesafear.certificate.CertificateImage
import com.minesafear.certificate.QrCodeGenerator
import com.minesafear.certificate.toPayload
import com.minesafear.data.DatabaseProvider
import com.minesafear.data.entity.CertificateEntity
import com.minesafear.data.repository.TrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CertificateQrScreen"

/** Larger than the on-screen size so the QR stays crisp on a high-density display. */
private const val DISPLAY_QR_SIZE_PX = 720

/**
 * A certificate's QR code, sized for someone else's camera, with save and share.
 *
 * Everything a verifier needs is inside the code — see
 * [com.minesafear.certificate.CertificatePayload] — so this works with no signal on
 * either phone.
 */
@Composable
fun CertificateQrScreen(
    certId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = remember(context) { TrainingRepository(DatabaseProvider.get(context)) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Wrapped so "still reading the database" is distinguishable from "no such
    // certificate": a bare null would leave a deleted id showing a spinner forever.
    val load by remember(repository, certId) {
        repository.observeCertificate(certId)
            .map<CertificateEntity?, CertificateLoad> { CertificateLoad.Loaded(it) }
    }.collectAsStateWithLifecycle(CertificateLoad.Loading)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CertificateScreenHeader(
                title = stringResource(R.string.certificate_qr_title),
                onBack = onBack,
            )

            when (val state = load) {
                // No spinner: a local Room read resolves in a frame or two, and a
                // spinner that flashes reads as a glitch.
                CertificateLoad.Loading -> Unit

                is CertificateLoad.Loaded -> {
                    val certificate = state.certificate
                    if (certificate == null) {
                        Text(
                            text = stringResource(R.string.certificate_qr_missing),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        CertificateQrContent(
                            certificate = certificate,
                            snackbarHostState = snackbarHostState,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun CertificateQrContent(
    certificate: CertificateEntity,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val encoded = remember(certificate) { certificate.toPayload().encode() }
    val qr: Bitmap? = remember(encoded) {
        runCatching { QrCodeGenerator.encodeAsBitmap(encoded, sizePx = DISPLAY_QR_SIZE_PX) }
            .onFailure { Log.e(TAG, "Could not encode the certificate QR", it) }
            .getOrNull()
    }

    // Cached so tapping Share after Save does not write a second copy to the gallery.
    var savedUri by remember(certificate.certId) { mutableStateOf<Uri?>(null) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (qr == null) {
            Text(
                text = stringResource(R.string.certificate_qr_render_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            // Always white, never the theme surface: in dark mode a QR drawn on a
            // dark background loses the quiet zone that scanners key off.
            Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(
                        R.string.certificate_qr_content_description,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(12.dp),
                    // Nearest-neighbour. The default bilinear filter blurs module
                    // edges when the bitmap is scaled, which costs scan reliability.
                    filterQuality = FilterQuality.None,
                )
            }
        }

        Text(
            text = stringResource(R.string.certificate_qr_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CertificateDetailRow(
            label = stringResource(R.string.certificate_holder_label),
            value = certificate.userName,
        )
        CertificateDetailRow(
            label = stringResource(R.string.certificate_score_label),
            value = stringResource(R.string.certificate_score, certificate.score),
        )
        CertificateDetailRow(
            label = stringResource(R.string.certificate_expires_label),
            value = rememberFormattedDate(certificate.expiryDate),
        )
        CertificateDetailRow(
            label = stringResource(R.string.certificate_id_label),
            value = certificate.certId,
        )

        Button(
            onClick = {
                scope.launch {
                    val uri = savedUri ?: saveCertificateImage(context, certificate)
                        ?.also { savedUri = it }
                    snackbarHostState.showSnackbar(
                        context.getString(
                            if (uri != null) R.string.certificate_card_saved
                            else R.string.certificate_card_failed,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.certificate_card_export_button))
        }

        Button(
            onClick = {
                scope.launch {
                    val uri = savedUri ?: saveCertificateImage(context, certificate)
                        ?.also { savedUri = it }
                    snackbarHostState.showSnackbar(
                        context.getString(
                            if (uri != null) R.string.certificate_qr_saved
                            else R.string.certificate_qr_save_failed,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.certificate_qr_save))
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    // Sharing needs a Uri, so it saves first if Save has not been
                    // tapped. The gallery copy is the artefact either way.
                    val uri = savedUri ?: saveCertificateImage(context, certificate)
                        ?.also { savedUri = it }
                    if (uri == null) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.certificate_qr_save_failed),
                        )
                    } else {
                        val subject = context.getString(
                            R.string.certificate_qr_share_subject,
                            certificate.userName,
                        )
                        context.startActivity(
                            Intent.createChooser(
                                CertificateImage.shareIntent(uri, subject),
                                null,
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.certificate_qr_share))
        }
    }
}

/** Renders and writes the PNG off the main thread; null when either step failed. */
private suspend fun saveCertificateImage(
    context: Context,
    certificate: CertificateEntity,
): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap = CertificateImage.render(context, certificate)
        CertificateImage.saveToPictures(
            context = context,
            bitmap = bitmap,
            fileName = CertificateImage.fileNameFor(certificate),
        )
    }.onFailure { Log.e(TAG, "Could not save the certificate PNG", it) }.getOrNull()
}

private sealed interface CertificateLoad {
    data object Loading : CertificateLoad

    /** [certificate] is null when no row has that id. */
    data class Loaded(val certificate: CertificateEntity?) : CertificateLoad
}
