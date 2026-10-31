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

// One confident accent + restrained semantics (readable on dark AND light).
val Energy = Color(0xFF12A594)   // primary accent
val Amber = Color(0xFFBE8A2B)    // money, used sparingly
val Violet = Color(0xFF5E6B86)   // muted slate for secondary figures (not purple)
val Good = Color(0xFF1F9D57)
val Bad = Color(0xFFD64550)

/** Neutral colours that flip between day and night. */
data class Palette(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val outline: Color,
    val textHi: Color,
    val textMid: Color,
    val textLow: Color,
    val energyDim: Color,
)

private val DarkPalette = Palette(
    bg = Color(0xFF0A0C10),
    surface1 = Color(0xFF14171D),
    surface2 = Color(0xFF1E222B),
    outline = Color(0xFF282D38),
    textHi = Color(0xFFF1F3F7),
    textMid = Color(0xFF8E96A4),
    textLow = Color(0xFF59636F),
    energyDim = Color(0xFF0C3F3A),
)

private val LightPalette = Palette(
    bg = Color(0xFFF4F6F8),
    surface1 = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEDF0F4),
    outline = Color(0xFFE2E6EC),
    textHi = Color(0xFF0F141C),
    textMid = Color(0xFF57616E),
    textLow = Color(0xFF8A93A1),
    energyDim = Color(0xFFD7EFEB),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

// Named neutrals read the active palette, so all call sites switch day/night for free.
val Bg: Color @Composable get() = LocalPalette.current.bg
val Surface1: Color @Composable get() = LocalPalette.current.surface1
val Surface2: Color @Composable get() = LocalPalette.current.surface2
val Outline: Color @Composable get() = LocalPalette.current.outline
val TextHi: Color @Composable get() = LocalPalette.current.textHi
val TextMid: Color @Composable get() = LocalPalette.current.textMid
val TextLow: Color @Composable get() = LocalPalette.current.textLow
val EnergyDim: Color @Composable get() = LocalPalette.current.energyDim

// Non-composable fallback neutral (for draw code / plain functions).
val NeutralGray = Color(0xFF8A94A3)

private val DarkScheme = darkColorScheme(
    primary = Energy, onPrimary = Color(0xFF04201C),
    secondary = Amber, tertiary = Violet,
    background = Color(0xFF0A0C10), onBackground = Color(0xFFF1F3F7),
    surface = Color(0xFF14171D), onSurface = Color(0xFFF1F3F7),
    surfaceVariant = Color(0xFF1E222B), onSurfaceVariant = Color(0xFF8E96A4),
    error = Bad, outline = Color(0xFF282D38),
)

private val LightScheme = lightColorScheme(
    primary = Energy, onPrimary = Color(0xFFFFFFFF),
    secondary = Amber, tertiary = Violet,
    background = Color(0xFFF4F6F8), onBackground = Color(0xFF0F141C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0F141C),
    surfaceVariant = Color(0xFFEDF0F4), onSurfaceVariant = Color(0xFF57616E),
    error = Bad, outline = Color(0xFFE2E6EC),
)

// Type scale for a display read at arm's length, often in glare.
private val AppType = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
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
