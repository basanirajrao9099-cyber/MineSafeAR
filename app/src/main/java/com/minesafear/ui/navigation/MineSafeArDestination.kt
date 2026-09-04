package com.minesafear.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.minesafear.R

/**
 * Every top-level destination reachable from the bottom navigation bar.
 *
 * Routes are plain strings for now; they can move to type-safe navigation
 * once the screens start taking arguments.
 */
enum class MineSafeArDestination(
    val route: String,
    @StringRes val titleRes: Int,
    @StringRes val navLabelRes: Int,
    val icon: ImageVector,
) {
    HOME(
        route = "home",
        titleRes = R.string.title_home,
        navLabelRes = R.string.nav_home,
        icon = Icons.Filled.Home,
    ),
    TRAINING_MODULES(
        route = "training_modules",
        titleRes = R.string.title_modules,
        navLabelRes = R.string.nav_modules,
        icon = Icons.Filled.List,
    ),
    ASSESSMENT(
        route = "assessment",
        titleRes = R.string.title_assessment,
        navLabelRes = R.string.nav_assessment,
        icon = Icons.Filled.CheckCircle,
    ),
    CERTIFICATES(
        route = "certificates",
        titleRes = R.string.title_certificates,
        navLabelRes = R.string.nav_certificates,
        icon = Icons.Filled.Star,
    ),
    SETTINGS(
        route = "settings",
        titleRes = R.string.title_settings,
        navLabelRes = R.string.nav_settings,
        icon = Icons.Filled.Settings,
    ),
    ;

    companion object {
        val START: MineSafeArDestination = HOME

        fun fromRoute(route: String?): MineSafeArDestination? =
            entries.firstOrNull { it.route == route }
    }
}
