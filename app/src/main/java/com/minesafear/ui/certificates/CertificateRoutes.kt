package com.minesafear.ui.certificates

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument

/**
 * Routes pushed on top of the Certificates tab.
 *
 * The certificate id goes in the path unencoded, which is safe because it is always
 * a UUID — hex and dashes only. Any other id shape would need encoding at the call
 * site.
 */
object CertificateRoutes {

    const val ARG_CERT_ID = "certId"

    private const val QR_PREFIX = "certificate_qr"

    const val QR = "$QR_PREFIX/{$ARG_CERT_ID}"

    const val VERIFY = "certificate_verify"

    val qrArguments: List<NamedNavArgument> = listOf(
        navArgument(ARG_CERT_ID) { type = NavType.StringType },
    )

    fun qr(certId: String): String = "$QR_PREFIX/$certId"
}
