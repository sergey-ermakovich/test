package com.zeekrprobe.journal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// "Космический" тёмный набор в духе Zeekr.
val Bg = Color(0xFF0B0E13)
val Surface1 = Color(0xFF151A23)
val Surface2 = Color(0xFF1D2430)
val Energy = Color(0xFF27E0C4)       // бирюза "энергии"
val EnergyDim = Color(0xFF0E5A52)
val Amber = Color(0xFFFFB74D)        // деньги / стоимость
val Violet = Color(0xFF8B7CFF)       // акцент для км
val TextHi = Color(0xFFEAEEF5)
val TextMid = Color(0xFF9AA4B4)
val TextLow = Color(0xFF6B7280)
val Good = Color(0xFF46D17F)
val Bad = Color(0xFFFF5C6C)

private val Scheme = darkColorScheme(
    primary = Energy,
    onPrimary = Color(0xFF042019),
    secondary = Amber,
    onSecondary = Color(0xFF2A1800),
    tertiary = Violet,
    background = Bg,
    onBackground = TextHi,
    surface = Surface1,
    onSurface = TextHi,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    error = Bad,
    outline = Color(0xFF2B3340),
)

private val AppType = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 40.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun JournalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppType, content = content)
}
