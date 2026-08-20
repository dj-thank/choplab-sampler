package com.choplab.sampler.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A3D00),
    onPrimary = Color(0xFFFFF6E3),
    primaryContainer = Color(0xFFFFB15E),
    onPrimaryContainer = Color(0xFF2A1500),
    secondary = Color(0xFF577D32),
    tertiary = Color(0xFFFF7A1A),
    background = Color(0xFF14110A),
    surface = Color(0xFFEFE6D0),
    surfaceVariant = Color(0xFFD8CCB0),
    onSurface = Color(0xFF241F14),
    onSurfaceVariant = Color(0xFF5C523A),
    outline = Color(0xFF766A4F),
)

@Composable
fun ChopLabTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
