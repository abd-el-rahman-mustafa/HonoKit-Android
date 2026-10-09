package com.aamus.powerpdf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aamus.powerpdf.engine.R as EngineR

/**
 * The desktop app's tokens (PowerPDF src/renderer/src/styles.css), so both apps look like one
 * product: neutral greys, blue as the only interaction colour, violet for the brand.
 */
@Immutable
data class PowerColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val sunken: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSoft: Color,
    val brand: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    val highlight: Color,
    val highlightCurrent: Color,
    val pageBackdrop: Color,
    val isDark: Boolean,
)

val LightColors = PowerColors(
    bg = Color(0xFFF3F3F3),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF5F5F5),
    sunken = Color(0xFFEBEBEB),
    ink = Color(0xFF1F1F1F),
    muted = Color(0xFF555C66),
    faint = Color(0xFF8A9099),
    line = Color(0xFFE1E1E1),
    accent = Color(0xFF1473E6),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE5F0FD),
    brand = Color(0xFF5A5FF0),
    ok = Color(0xFF1F8A4C),
    warn = Color(0xFFB7791F),
    danger = Color(0xFFC53030),
    highlight = Color(0x6BFFD600),
    highlightCurrent = Color(0x8CFF7800),
    pageBackdrop = Color(0xFFE8E8E8),
    isDark = false,
)

val DarkColors = PowerColors(
    bg = Color(0xFF1B1B1B),
    surface = Color(0xFF252525),
    surface2 = Color(0xFF2C2C2C),
    sunken = Color(0xFF161616),
    ink = Color(0xFFEBEBEB),
    muted = Color(0xFFB0B0B0),
    faint = Color(0xFF8A8A8A),
    line = Color(0xFF3A3A3A),
    accent = Color(0xFF4B9CF5),
    // Dark ink on the light dark-mode blue (5.4:1); white on it was 2.9:1, hard to read.
    accentInk = Color(0xFF0A2540),
    accentSoft = Color(0xFF1D3350),
    brand = Color(0xFF8F93FF),
    ok = Color(0xFF4CC27F),
    warn = Color(0xFFE0A84A),
    danger = Color(0xFFF06A6A),
    highlight = Color(0x59FFD600),
    highlightCurrent = Color(0x80FF8C00),
    pageBackdrop = Color(0xFF121212),
    isDark = true,
)

val LocalPowerColors = staticCompositionLocalOf { LightColors }

object Power {
    val colors: PowerColors
        @Composable get() = LocalPowerColors.current
}

private fun schemeOf(c: PowerColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.accentInk,
        primaryContainer = c.accentSoft,
        onPrimaryContainer = c.ink,
        secondary = c.brand,
        onSecondary = Color.White,
        secondaryContainer = c.accentSoft,
        onSecondaryContainer = c.ink,
        tertiary = c.brand,
        background = c.bg,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surface2,
        onSurfaceVariant = c.muted,
        surfaceTint = Color.Transparent,
        surfaceBright = c.surface,
        surfaceDim = c.sunken,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surface2,
        inverseSurface = if (c.isDark) Color(0xFFEBEBEB) else Color(0xFF2C2C2C),
        inverseOnSurface = if (c.isDark) Color(0xFF1F1F1F) else Color(0xFFF5F5F5),
        inversePrimary = if (c.isDark) Color(0xFF1473E6) else Color(0xFF8CC0FA),
        outline = c.faint,
        outlineVariant = c.line,
        error = c.danger,
        onError = Color.White,
        scrim = Color(0x99000000),
    )
}

/** IBM Plex Sans Arabic, the desktop's Arabic face (desktop decisions §18), for the Arabic interface. */
val PlexArabic = FontFamily(
    Font(EngineR.font.plex_arabic_regular, FontWeight.Normal),
    Font(EngineR.font.plex_arabic_regular, FontWeight.Medium),
    Font(EngineR.font.plex_arabic_bold, FontWeight.SemiBold),
    Font(EngineR.font.plex_arabic_bold, FontWeight.Bold),
)

private fun typographyOf(family: FontFamily): Typography {
    fun s(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
        TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)
    return Typography(
        displayLarge = s(40, 48, FontWeight.SemiBold),
        displayMedium = s(34, 42, FontWeight.SemiBold),
        displaySmall = s(28, 36, FontWeight.SemiBold),
        headlineLarge = s(26, 34, FontWeight.SemiBold),
        headlineMedium = s(22, 30, FontWeight.SemiBold),
        headlineSmall = s(20, 28, FontWeight.SemiBold),
        titleLarge = s(19, 26, FontWeight.SemiBold),
        titleMedium = s(16, 22, FontWeight.SemiBold),
        titleSmall = s(14, 20, FontWeight.SemiBold),
        bodyLarge = s(16, 24),
        bodyMedium = s(14, 20),
        bodySmall = s(12, 17),
        labelLarge = s(14, 20, FontWeight.Medium),
        labelMedium = s(12, 16, FontWeight.Medium),
        labelSmall = s(11, 14, FontWeight.Medium, 0.3),
    )
}

val PowerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun PowerTheme(dark: Boolean = isSystemInDarkTheme(), arabic: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val family = if (arabic) PlexArabic else FontFamily.Default
    CompositionLocalProvider(LocalPowerColors provides colors) {
        MaterialTheme(colorScheme = schemeOf(colors), typography = typographyOf(family), shapes = PowerShapes) {
            // No Surface wraps the screens, so icons without their own tint (the bar's Back, Undo,
            // Save) took Material's default black, even on the dark bar.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.ink, content = content)
        }
    }
}
