package com.nerfherder.walkthor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object BookColors {
    val Leather = Color(0xFF2A1A10)
    val LeatherLight = Color(0xFF4A2C1A)
    val Paper = Color(0xFFF4ECD8)
    val PaperShade = Color(0xFFE6DABD)
    val Ink = Color(0xFF2B2118)
    val InkFaint = Color(0xFF8A7458)
    val Gold = Color(0xFFD9A441)
    val Highlight = Color(0xFFFFD54F)
}

private val scheme = darkColorScheme(
    primary = BookColors.Gold,
    onPrimary = Color(0xFF2A1A10),
    secondary = Color(0xFFC98B5B),
    background = BookColors.Leather,
    onBackground = Color(0xFFF1E6D2),
    surface = Color(0xFF34211A),
    onSurface = Color(0xFFF1E6D2),
    surfaceVariant = Color(0xFF4A3127),
    onSurfaceVariant = Color(0xFFD8C4AC),
    surfaceContainerHigh = Color(0xFF3D271F),
    surfaceContainer = Color(0xFF38241C),
    error = Color(0xFFFF8A80),
)

@Composable
fun WalkThorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
