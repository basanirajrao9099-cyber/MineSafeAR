package com.minesafear.certificate

import com.journeyapps.barcodescanner.ScanOptions

/**
 * Scanning side of the QR flow, used by a supervisor to verify a worker's
 * certificate card.
 *
 * The scan itself is launched from a composable with
 * `rememberLauncherForActivityResult(ScanContract())` and [options]; this object
 * only owns the configuration so it is not duplicated at each call site. The
 * scanned string goes to [CertificateVerifier.verify], which is the single place
 * that decides what a code means — parsing it anywhere else would create a second
 * path that can accept a card the verifier would reject.
 */
object QrCodeScanner {

    fun options(prompt: String): ScanOptions = ScanOptions()
        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        .setPrompt(prompt)
        // A beep is useless in ear-protection and startling in a quiet office.
        .setBeepEnabled(false)
        .setOrientationLocked(false)
}
