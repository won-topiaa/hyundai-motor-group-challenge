package com.noisetrace.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Hyundai Motor CI colours (hyundai.com, CI 전용색상) on a night-drive screen: Hyundai Blue for the background glow and
 * the main button, Active Blue for noises and actions, Sand for the car's own data, Active Red for warnings.
 */
object Palette {
    // CI colours.
    val HyundaiBlue = Color(0xFF002C5F)
    val ActiveBlue = Color(0xFF00AAD2)
    val SkyBlue = Color(0xFFAACAE6)
    val Sand = Color(0xFFE4DCD3)
    val LightSand = Color(0xFFF6F3F2)
    val ActiveRed = Color(0xFFE63312)

    // Roles.
    /** Hyundai Blue taken down to a night background. */
    val Navy = Color(0xFF010A17)
    /** Noises, actions and highlights. */
    val Accent = ActiveBlue
    /** The car's own data, and the second series in charts. */
    val Data = Sand
    val Glass = Color(0xB30A1628)
    val GlassStrong = Color(0xE60C1B30)
    val Stroke = Color(0x26FFFFFF)
    val Text = LightSand
    val TextMuted = Color(0xFF94A3B8)
    val Warning = ActiveRed
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0x4000AAD2),
    onPrimaryContainer = Color(0xFFD9F4FB),
    secondary = Palette.Data,
    onSecondary = Color(0xFF2A241D),
    secondaryContainer = Color(0x33E4DCD3),
    onSecondaryContainer = Palette.LightSand,
    tertiary = Palette.SkyBlue,
    background = Palette.Navy,
    onBackground = Palette.Text,
    surface = Palette.Glass,
    onSurface = Palette.Text,
    surfaceVariant = Color(0xFF13233A),
    onSurfaceVariant = Palette.TextMuted,
    outline = Color(0xFF34465E),
    error = Palette.Warning,
    errorContainer = Color(0x55E63312),
    onErrorContainer = Color(0xFFFFE1DA),
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
