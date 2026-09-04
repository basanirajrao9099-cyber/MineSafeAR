package com.minesafear.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = SafetyGreen,
    onPrimary = Color.White,
    primaryContainer = SafetyGreenLight,
    onPrimaryContainer = SafetyGreenDark,
    secondary = HazardAmberDark,
    onSecondary = Color.White,
    secondaryContainer = HazardAmberLight,
    onSecondaryContainer = HazardAmberDark,
    background = SurfaceLight,
    surface = SurfaceLight,
    error = ErrorRed,
)

private val DarkColorScheme = darkColorScheme(
    primary = SafetyGreenLight,
    onPrimary = SafetyGreenDark,
    primaryContainer = SafetyGreen,
    onPrimaryContainer = SafetyGreenLight,
    secondary = HazardAmber,
    onSecondary = HazardAmberDark,
    secondaryContainer = HazardAmberDark,
    onSecondaryContainer = HazardAmberLight,
    background = SurfaceDark,
    surface = SurfaceDark,
    error = ErrorRed,
)

/**
 * Fixed brand palette rather than dynamic colour: safety signalling needs to
 * look the same on every device and in printed certificates.
 */
@Composable
fun MineSafeArTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = MineSafeArTypography,
        content = content,
    )
}
