package com.ping.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Brand colors from colors.xml ──────────────────────────────────────────
val PingPurple = Color(0xFF7C4DFF)
val PingPurpleDark = Color(0xFF512DA8)
val PingPurpleLight = Color(0xFFB388FF)
val PingCyan = Color(0xFF00E5FF)
val PingCyanDark = Color(0xFF00B8D4)

// ── Surface / text colors ─────────────────────────────────────────────────
val Surface = Color(0xFF121212)
val SurfaceVariant = Color(0xFF1E1E2E)
val OnSurface = Color(0xFFFFFFFF)
val OnSurfaceSecondary = Color(0xFFB0B0C0)
val ErrorRed = Color(0xFFCF6679)
val SuccessGreen = Color(0xFF4CAF50)

// ── Dark color scheme (Aura is dark-only) ─────────────────────────────────
private val PingDarkColorScheme = darkColorScheme(
    primary = PingPurple,
    onPrimary = Color.White,
    primaryContainer = PingPurpleDark,
    onPrimaryContainer = PingPurpleLight,
    secondary = PingCyan,
    onSecondary = Color.Black,
    secondaryContainer = PingCyanDark,
    onSecondaryContainer = Color.White,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceSecondary,
    error = ErrorRed,
    onError = Color.Black,
)

@Composable
fun PingTheme(content: @Composable () -> Unit) {
    val colorScheme = PingDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        LaunchedEffect(Unit) {
            val window = (view.context as Activity).window
            window.statusBarColor = PingPurpleDark.toArgb()
            window.navigationBarColor = Surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
