package com.aerospring.arworld.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ArWorldDark = darkColorScheme(
    primary = Color(0xFF7FB3FF),
    secondary = Color(0xFFB0C6E0),
    background = Color(0xFF10141A),
    surface = Color(0xFF1A1F27)
)

private val ArWorldLight = lightColorScheme(
    primary = Color(0xFF2D6CDF),
    secondary = Color(0xFF4C6C9A),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun ArWorldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) ArWorldDark else ArWorldLight
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}