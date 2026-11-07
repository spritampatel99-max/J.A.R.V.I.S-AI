package com.jarvis.ai.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Phase 1 uses one dark theme (deep navy + cyan). Theme switching comes with the settings phase. */
private val JarvisColors = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00212A),
    secondary = Color(0xFF2979FF),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFF05080F),
    onBackground = Color(0xFFE6F1FF),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE6F1FF),
    surfaceVariant = Color(0xFF111B2E),
    onSurfaceVariant = Color(0xFF9FB3CC),
    error = Color(0xFFFF5370),
    onError = Color(0xFF2A0008),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = JarvisColors, content = content)
}
