package com.pixelforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PfAccent = Color(0xFF6C5CE7)
val PfAccentDark = Color(0xFF4834D4)
val PfBackgroundDark = Color(0xFF121016)
val PfSurfaceDark = Color(0xFF1E1B24)
val PfCanvasCheckerA = Color(0xFF3A3742)
val PfCanvasCheckerB = Color(0xFF2A2731)

private val DarkColors = darkColorScheme(
    primary = PfAccent,
    secondary = PfAccentDark,
    background = PfBackgroundDark,
    surface = PfSurfaceDark,
    onPrimary = Color.White,
    onBackground = Color(0xFFEDEBF2),
    onSurface = Color(0xFFEDEBF2)
)

private val LightColors = lightColorScheme(
    primary = PfAccent,
    secondary = PfAccentDark,
    background = Color(0xFFF7F6FA),
    surface = Color.White
)

@Composable
fun PixelForgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
