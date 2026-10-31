package com.zeekrprobe.journal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Accent colours — shared by both themes, chosen to stay readable on dark AND light.
val Energy = Color(0xFF16A98D)   // "энергия" / основной акцент
val Amber = Color(0xFFE59400)    // деньги / стоимость
val Violet = Color(0xFF6D5CE0)   // км
val Good = Color(0xFF2FA85A)
val Bad = Color(0xFFE5484D)

/** Neutral colours that flip between day and night. */
data class Palette(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val textHi: Color,
    val textMid: Color,
    val textLow: Color,
    val energyDim: Color,
)

private val DarkPalette = Palette(
    bg = Color(0xFF0B0E13),
    surface1 = Color(0xFF151A23),
    surface2 = Color(0xFF1D2430),
    textHi = Color(0xFFEAEEF5),
    textMid = Color(0xFF9AA4B4),
    textLow = Color(0xFF6B7280),
    energyDim = Color(0xFF0E5A52),
)

private val LightPalette = Palette(
    bg = Color(0xFFF2F5F9),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE6EBF2),
    textHi = Color(0xFF0E1726),
    textMid = Color(0xFF51606F),
    textLow = Color(0xFF7A8696),
    energyDim = Color(0xFFBFF0E8),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// Named neutrals read the active palette, so all existing call sites switch day/night for free.
val Bg: Color @Composable get() = LocalPalette.current.bg
val Surface1: Color @Composable get() = LocalPalette.current.surface1
val Surface2: Color @Composable get() = LocalPalette.current.surface2
val TextHi: Color @Composable get() = LocalPalette.current.textHi
val TextMid: Color @Composable get() = LocalPalette.current.textMid
val TextLow: Color @Composable get() = LocalPalette.current.textLow
val EnergyDim: Color @Composable get() = LocalPalette.current.energyDim

// Non-composable fallback neutral (for draw code / plain functions).
val NeutralGray = Color(0xFF8A94A3)

private val DarkScheme = darkColorScheme(
    primary = Energy, onPrimary = Color(0xFF042019),
    secondary = Amber, tertiary = Violet,
    background = Color(0xFF0B0E13), onBackground = Color(0xFFEAEEF5),
    surface = Color(0xFF151A23), onSurface = Color(0xFFEAEEF5),
    surfaceVariant = Color(0xFF1D2430), onSurfaceVariant = Color(0xFF9AA4B4),
    error = Bad, outline = Color(0xFF2B3340),
)

private val LightScheme = lightColorScheme(
    primary = Energy, onPrimary = Color(0xFFFFFFFF),
    secondary = Amber, tertiary = Violet,
    background = Color(0xFFF2F5F9), onBackground = Color(0xFF0E1726),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0E1726),
    surfaceVariant = Color(0xFFE6EBF2), onSurfaceVariant = Color(0xFF51606F),
    error = Bad, outline = Color(0xFFCAD2DD),
)

// Larger type for a screen read at arm's length, often in glare.
private val AppType = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp),
)

@Composable
fun JournalTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = AppType,
            content = content,
        )
    }
}
