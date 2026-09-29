package com.ping.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Brand palette, shared with the launcher icon: indigo depth, violet body, magenta glow, cyan beacon.
val PingViolet = Color(0xFF8B5CF6)
val PingIndigo = Color(0xFF2A1B8F)
val PingMagenta = Color(0xFFD946EF)
val PingCyan = Color(0xFF22E5FF)
val PingCyanDark = Color(0xFF0891B2)

val Ink = Color(0xFF0D0A1F)
val Card = Color(0xFF1A1636)
val CardHigh = Color(0xFF241F47)
val Muted = Color(0xFFA9A5C9)
val ErrorRed = Color(0xFFFF6B8A)
val SuccessGreen = Color(0xFF34D399)

/** Diagonal brand gradient used for hero buttons and avatars. */
val BrandBrush: Brush = Brush.linearGradient(listOf(PingIndigo, PingViolet, PingMagenta))

private val PingDarkColorScheme = darkColorScheme(
    primary = PingViolet,
    onPrimary = Color.White,
    primaryContainer = PingIndigo,
    onPrimaryContainer = Color(0xFFE4DBFF),
    secondary = PingCyan,
    onSecondary = Color(0xFF00363D),
    secondaryContainer = Color(0xFF3B2F7A),
    onSecondaryContainer = Color.White,
    tertiary = PingMagenta,
    background = Ink,
    onBackground = Color(0xFFF3F0FF),
    surface = Ink,
    onSurface = Color(0xFFF3F0FF),
    surfaceVariant = Card,
    onSurfaceVariant = Muted,
    surfaceContainer = Card,
    surfaceContainerHigh = CardHigh,
    outline = Color(0xFF4B4577),
    outlineVariant = Color(0xFF322D5A),
    error = ErrorRed,
    onError = Color(0xFF3B0014),
)

private val PingShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val PingTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

@Composable
fun PingTheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        LaunchedEffect(Unit) {
            val window = (view.context as Activity).window
            window.statusBarColor = Ink.toArgb()
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = PingDarkColorScheme,
        shapes = PingShapes,
        typography = PingTypography,
        content = content,
    )
}
