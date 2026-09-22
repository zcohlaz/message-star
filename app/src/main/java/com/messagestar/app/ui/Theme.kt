package com.messagestar.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF8F7FC),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B6FF),
    secondary = Color(0xFF6EE7D2)
)

@Composable
fun MessageStarTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
