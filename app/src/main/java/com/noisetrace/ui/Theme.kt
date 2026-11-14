package com.noisetrace.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Night-drive palette: deep navy with blue and magenta light, glassy dark panels, cyan for live car data.
 * Magenta marks noises and actions; blue and cyan mark the car's own data.
 */
object Palette {
    val Navy = Color(0xFF05061A)
    val NavyDeep = Color(0xFF020210)
    val Magenta = Color(0xFFB300CD)
    val MagentaBright = Color(0xFFD25CF5)
    val Blue = Color(0xFF0019FF)
    val BlueBright = Color(0xFF5B7BFF)
    val Cyan = Color(0xFF2FE3D3)
    val Glass = Color(0xB3121220)
    val GlassStrong = Color(0xE6141424)
    val Stroke = Color(0x26FFFFFF)
    val Text = Color(0xFFF3F2F8)
    val TextMuted = Color(0xFF9C9BB5)
    val Warning = Color(0xFFFF7A9A)
}

private val colors = darkColorScheme(
    primary = Palette.MagentaBright,
    onPrimary = Color.White,
    primaryContainer = Color(0x40B300CD),
    onPrimaryContainer = Color(0xFFF6DDFF),
    secondary = Palette.Cyan,
    onSecondary = Color(0xFF00201D),
    secondaryContainer = Color(0x332FE3D3),
    onSecondaryContainer = Color(0xFFD7FFFA),
    tertiary = Palette.BlueBright,
    background = Palette.Navy,
    onBackground = Palette.Text,
    surface = Palette.Glass,
    onSurface = Palette.Text,
    surfaceVariant = Color(0xFF1C1C2E),
    onSurfaceVariant = Palette.TextMuted,
    outline = Color(0xFF3B3A55),
    error = Palette.Warning,
    errorContainer = Color(0x55B3264A),
    onErrorContainer = Color(0xFFFFDDE4),
)

// Large, light type: read at a glance on a car display, at arm's length.
private val type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 46.sp, fontWeight = FontWeight.Light),
        headlineMedium = headlineMedium.copy(fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp),
        titleLarge = titleLarge.copy(fontSize = 28.sp, fontWeight = FontWeight.Normal),
        titleMedium = titleMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontSize = 21.sp),
        bodyMedium = bodyMedium.copy(fontSize = 19.sp),
        labelLarge = labelLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
    )
}

@Composable
fun NoiseTraceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
