package com.choplab.sampler.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB15C),
    onPrimary = Color(0xFF2B1700),
    secondary = Color(0xFF8FD4C7),
    tertiary = Color(0xFFFF8A80),
    background = Color(0xFF111315),
    surface = Color(0xFF1B1E21),
    surfaceVariant = Color(0xFF292D31),
    outline = Color(0xFF777C82),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8B4C00),
    secondary = Color(0xFF006B5E),
    tertiary = Color(0xFF9B2D24),
    background = Color(0xFFF8F7F4),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9E5DF),
)

@Composable
fun ChopLabTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
