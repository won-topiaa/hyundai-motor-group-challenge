package com.noisetrace.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** Dark, high-contrast theme for the in-car display. Amber marks noises; teal marks the car's own data. */
private val colors = darkColorScheme(
    primary = Color(0xFFFFB86B),
    onPrimary = Color(0xFF3A2200),
    primaryContainer = Color(0xFF4A3214),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFF7FC8C2),
    onSecondary = Color(0xFF00201E),
    secondaryContainer = Color(0xFF1E4D4A),
    onSecondaryContainer = Color(0xFFCDEDEA),
    tertiary = Color(0xFFB4C5FF),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE4E2DE),
    surface = Color(0xFF1A1C1F),
    onSurface = Color(0xFFE4E2DE),
    surfaceVariant = Color(0xFF2A2D31),
    onSurfaceVariant = Color(0xFFBFC3C8),
    outline = Color(0xFF5A5F66),
    error = Color(0xFFFFB4AB),
)

// Large type: read at a glance on a car display, at arm's length.
private val type = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontSize = 34.sp),
        titleLarge = titleLarge.copy(fontSize = 28.sp),
        titleMedium = titleMedium.copy(fontSize = 22.sp),
        bodyLarge = bodyLarge.copy(fontSize = 22.sp),
        bodyMedium = bodyMedium.copy(fontSize = 20.sp),
        labelLarge = labelLarge.copy(fontSize = 18.sp),
    )
}

@Composable
fun NoiseTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
