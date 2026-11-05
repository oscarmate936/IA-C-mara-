package com.iacamara.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFB300)
val LevelGreen = Color(0xFF4ADE80)
val SoftWhite = Color(0xFFF5F5F5)

private val IaColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Amber,
    background = Color.Black,
    onBackground = SoftWhite,
    surface = Color(0xFF121212),
    onSurface = SoftWhite,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFBDBDBD),
)

@Composable
fun IaCamaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = IaColors, content = content)
}
