package com.fitcoach.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** FitCoach design tokens: a dark, premium look with one vivid accent. Every screen reads colours from here. */
object Fc {
    val Bg = Color(0xFF0A0C0F)
    val Surface = Color(0xFF13161B)
    val SurfaceHigh = Color(0xFF1B1F25)
    val SurfaceHigher = Color(0xFF242930)
    val Outline = Color(0xFF2C323A)
    val Text = Color(0xFFEEF1F4)
    val TextMuted = Color(0xFF8C96A2)
    val TextFaint = Color(0xFF5B6470)

    val Accent = Color(0xFFC8F560) // lime: primary actions, progress
    val OnAccent = Color(0xFF0A0C0F)
    val Cyan = Color(0xFF5BD6F5) // steps / activity
    val Orange = Color(0xFFFF9F5A) // food / energy
    val Violet = Color(0xFFA48BFF) // coach / memory
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFFF6B6B)

    val AccentGlow = Brush.linearGradient(listOf(Color(0xFFC8F560), Color(0xFF5BD6F5)))
    val HeroGradient = Brush.verticalGradient(listOf(Color(0xFF1A2416), Color(0xFF13161B)))
}

private val scheme = darkColorScheme(
    primary = Fc.Accent, onPrimary = Fc.OnAccent,
    primaryContainer = Color(0xFF2B3A12), onPrimaryContainer = Fc.Accent,
    secondary = Fc.Cyan, onSecondary = Fc.OnAccent,
    tertiary = Fc.Orange,
    background = Fc.Bg, onBackground = Fc.Text,
    surface = Fc.Surface, onSurface = Fc.Text,
    surfaceVariant = Fc.SurfaceHigh, onSurfaceVariant = Fc.TextMuted,
    surfaceContainerLowest = Fc.Bg, surfaceContainerLow = Fc.Surface, surfaceContainer = Fc.Surface,
    surfaceContainerHigh = Fc.SurfaceHigh, surfaceContainerHighest = Fc.SurfaceHigher,
    outline = Fc.Outline, outlineVariant = Fc.Outline,
    error = Fc.Bad, onError = Fc.OnAccent,
)

private val typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge, bodyMedium = t.bodyMedium, bodySmall = t.bodySmall.copy(color = Fc.TextMuted),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    )
}

/** Big numbers on tiles and rings. */
val MetricStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = Fc.Text)

@Composable
fun FitCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
