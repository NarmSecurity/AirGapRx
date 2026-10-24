package com.airgaprx.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airgaprx.app.R

/*
 * "Dispensary label" design. The shelf reads like the labels a pharmacy prints, and severity uses the
 * coloured warning stickers pharmacists put on bottles. The stickers are the one bold element; the rest
 * stays paper, ink and the brand pine.
 */

private val Pine = Color(0xFF0F3D3E)
private val Mint = Color(0xFF7CCFB8)
private val Paper = Color(0xFFF3F5F2)
private val Ink = Color(0xFF17201F)

private val Light = lightColorScheme(
    primary = Pine, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBE7), onPrimaryContainer = Pine,
    secondary = Color(0xFF3F5A56), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EAE8), onSecondaryContainer = Ink,
    tertiary = Color(0xFF6B5900), onTertiary = Color.White,
    // "Needs your decision" rows on the review screen.
    tertiaryContainer = Color(0xFFFFF1C2), onTertiaryContainer = Color(0xFF3A2E00),
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = Color(0xFFE2E7E4), onSurfaceVariant = Color(0xFF4A5654),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9F7),
    surfaceContainer = Color(0xFFEEF1EE),
    surfaceContainerHigh = Color(0xFFE8ECE9),
    surfaceContainerHighest = Color(0xFFE2E7E4),
    outline = Color(0xFF7D8986), outlineVariant = Color(0xFFC9D1CE),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF00201B),
    primaryContainer = Color(0xFF1F4A45), onPrimaryContainer = Color(0xFFCDEBE3),
    secondary = Color(0xFFA9C9C2), onSecondary = Color(0xFF13302B),
    secondaryContainer = Color(0xFF24332F), onSecondaryContainer = Color(0xFFDCE5E2),
    tertiary = Color(0xFFE9C449), onTertiary = Color(0xFF3A2E00),
    tertiaryContainer = Color(0xFF3D3300), onTertiaryContainer = Color(0xFFFFE58A),
    background = Color(0xFF0E1716), onBackground = Color(0xFFE6ECEA),
    surface = Color(0xFF0E1716), onSurface = Color(0xFFE6ECEA),
    surfaceVariant = Color(0xFF2A3835), onSurfaceVariant = Color(0xFFA9B6B3),
    surfaceContainerLowest = Color(0xFF142120),
    surfaceContainerLow = Color(0xFF18251F),
    surfaceContainer = Color(0xFF1B2826),
    surfaceContainerHigh = Color(0xFF22302D),
    surfaceContainerHighest = Color(0xFF2A3835),
    outline = Color(0xFF74827F), outlineVariant = Color(0xFF33423F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Warning-sticker colours, the same in light and dark themes, like a printed sticker. Colour is never
 * the only signal: every severity also has an icon and a word (WCAG 2.1 SC 1.4.1, use of colour).
 * Text on each sticker meets 4.5:1 contrast.
 */
object SeverityColors {
    val major = Color(0xFFC8102E)
    val onMajor = Color.White
    val moderate = Color(0xFFF5C400)
    val onModerate = Color(0xFF1C1B14)
    val minor = Color(0xFFD6E6F2)
    val onMinor = Color(0xFF0E3A5B)
    val unknown = Color(0xFFE4E7E6)
    val onUnknown = Color(0xFF2E3836)
}

/**
 * Atkinson Hyperlegible (Braille Institute, SIL OFL 1.1; licence in assets/licences) keeps l, I and 1,
 * and O and 0, distinct, which matters for medicine names read off a label.
 */
private val Atkinson = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

/** Bold condensed sans for the warning stickers, as printed on pharmacy auxiliary labels (system font). */
val StickerFont = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(fontFamily = Atkinson, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

// A 16 sp body with an 18 sp reading size: many users are older and read medicine names closely.
private val AirGapType = Typography(
    displaySmall = style(34, 40, FontWeight.Bold, -0.5),
    headlineMedium = style(28, 34, FontWeight.Bold, -0.25),
    headlineSmall = style(24, 30, FontWeight.Bold),
    titleLarge = style(22, 28, FontWeight.Bold),
    titleMedium = style(18, 24, FontWeight.Bold),
    titleSmall = style(16, 22, FontWeight.Bold),
    bodyLarge = style(18, 27),
    bodyMedium = style(16, 24),
    bodySmall = style(14, 20),
    labelLarge = style(16, 20, FontWeight.Bold),
    labelMedium = style(14, 18, FontWeight.Bold),
    labelSmall = style(13, 16),
)

private val AirGapShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun AirGapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AirGapType,
        shapes = AirGapShapes,
        content = content,
    )
}
