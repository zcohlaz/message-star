package com.messagestar.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF201A62),
    secondary = Color(0xFF0F766E),
    tertiary = Color(0xFF176B4A),
    tertiaryContainer = Color(0xFFD7F3E4),
    onTertiaryContainer = Color(0xFF0B5233),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1B1B23),
    surface = Color.White,
    onSurface = Color(0xFF1B1B23),
    surfaceVariant = Color(0xFFECEBF4),
    onSurfaceVariant = Color(0xFF484653),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFFE7E4),
    onErrorContainer = Color(0xFF6E1E19)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B6FF),
    onPrimary = Color(0xFF25206D),
    primaryContainer = Color(0xFF37318A),
    onPrimaryContainer = Color(0xFFE3E1FF),
    secondary = Color(0xFF78D6C8),
    tertiary = Color(0xFF84DBAA),
    tertiaryContainer = Color(0xFF145C3D),
    onTertiaryContainer = Color(0xFFD7F3E4),
    background = Color(0xFF12131B),
    onBackground = Color(0xFFE5E4EF),
    surface = Color(0xFF1B1C26),
    onSurface = Color(0xFFE5E4EF),
    surfaceVariant = Color(0xFF42434E),
    onSurfaceVariant = Color(0xFFC4C5D1),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF7E2923),
    onErrorContainer = Color(0xFFFFE7E4)
)

@Composable
fun MessageStarTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
