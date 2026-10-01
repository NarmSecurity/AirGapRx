package com.airgaprx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F3D3E)
private val Mint = Color(0xFF5FBFA6)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = Color(0xFFD5ECE6), onPrimaryContainer = Teal,
    secondary = Color(0xFF3B6862), tertiary = Mint,
    background = Color(0xFFF8FAF9), surface = Color.White,
)

private val Dark = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF1E4F4A), onPrimaryContainer = Color(0xFFD5ECE6),
    secondary = Color(0xFF9FCEC5), tertiary = Mint,
)

/**
 * Severity colours. Colour is never the only signal: every severity is also shown with an icon
 * and a word (WCAG 2.1 SC 1.4.1, use of colour).
 */
object SeverityColors {
    val major = Color(0xFFB3261E)
    val majorContainer = Color(0xFFF9DEDC)
    val moderate = Color(0xFF8A5300)
    val moderateContainer = Color(0xFFFFE0B2)
    val minor = Color(0xFF41484D)
    val minorContainer = Color(0xFFE1E3E5)
}

@Composable
fun AirGapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
