package com.minesafear.ui.navigation

import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.minesafear.ui.assessment.AssessmentScreen
import com.minesafear.ui.certificates.CertificateQrScreen
import com.minesafear.ui.certificates.CertificateRoutes
import com.minesafear.ui.certificates.CertificatesScreen
import com.minesafear.ui.certificates.VerifyCertificateScreen
import com.minesafear.ui.home.HomeScreen
import com.minesafear.ui.settings.SettingsScreen

/**
 * Navigation destinations owned by Part 2: Certification, Sync, Storage & Settings (Laptop 2 / Dev B).
 */
fun NavGraphBuilder.addManagementGraph(
    navController: NavHostController,
    insetModifier: Modifier,
) {
    composable(MineSafeArDestination.HOME.route) {
        HomeScreen(
            onOpenPassport = { navController.navigate("passport") },
            modifier = insetModifier,
        )
    }
    composable(MineSafeArDestination.ASSESSMENT.route) {
        AssessmentScreen(modifier = insetModifier)
    }
    composable(MineSafeArDestination.CERTIFICATES.route) {
        CertificatesScreen(
            onOpenCertificate = { certId ->
                navController.navigate(CertificateRoutes.qr(certId))
            },
            onVerifyCertificate = { navController.navigate(CertificateRoutes.VERIFY) },
            modifier = insetModifier,
        )
    }
    composable(MineSafeArDestination.SETTINGS.route) {
        SettingsScreen(modifier = insetModifier)
    }

    // --- Certificates -------------------------------------------------

    composable(
        route = CertificateRoutes.QR,
        arguments = CertificateRoutes.qrArguments,
    ) { entry ->
        CertificateQrScreen(
            certId = entry.arguments?.getString(CertificateRoutes.ARG_CERT_ID) ?: "",
            onBack = { navController.popBackStack() },
            modifier = insetModifier,
        )
    }
    composable(CertificateRoutes.VERIFY) {
        VerifyCertificateScreen(
            onBack = { navController.popBackStack() },
            modifier = insetModifier,
        )
    }

    composable("passport") {
        com.minesafear.ui.home.WorkerPassportScreen(
            onBack = { navController.popBackStack() },
            modifier = insetModifier,
        )
    }
}
