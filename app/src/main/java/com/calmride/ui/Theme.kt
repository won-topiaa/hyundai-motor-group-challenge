package com.calmride.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** Dark, high-contrast theme for the in-car display. */
private val colors = darkColorScheme(
    primary = Color(0xFF7FC8C2),
    onPrimary = Color(0xFF00201E),
    primaryContainer = Color(0xFF1E4D4A),
    onPrimaryContainer = Color(0xFFCDEDEA),
    secondary = Color(0xFFB5C9C6),
    background = Color(0xFF0F1414),
    onBackground = Color(0xFFE2E8E7),
    surface = Color(0xFF171D1D),
    onSurface = Color(0xFFE2E8E7),
    surfaceVariant = Color(0xFF243030),
    onSurfaceVariant = Color(0xFFB9C7C5),
    error = Color(0xFFFFB4AB),
)

// Large type: most taxi drivers are in their 60s or older and read the screen at a glance.
private val type = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 34.sp),
        titleLarge = titleLarge.copy(fontSize = 28.sp),
        titleMedium = titleMedium.copy(fontSize = 22.sp),
        bodyLarge = bodyLarge.copy(fontSize = 22.sp),
        bodyMedium = bodyMedium.copy(fontSize = 20.sp),
    )
}

@Composable
fun CalmRideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
