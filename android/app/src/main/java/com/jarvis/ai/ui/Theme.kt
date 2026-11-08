package com.jarvis.ai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.ai.R

/*
 * Fonts. All three are bundled in the app (res/font), so they work offline and need no download:
 *  - Orbitron:       the display face, for the wordmark and screen titles (SIL Open Font License 1.1)
 *  - Manrope:        the text face for everything you read (SIL OFL 1.1)
 *  - JetBrains Mono: small technical labels, sizes and status tags (SIL OFL 1.1)
 * Licence texts are in docs/legal. Commercial typefaces such as Gotham or Proxima Nova cannot be bundled
 * without buying a licence, so these free, professionally designed families are used instead.
 */
val Orbitron = FontFamily(
    Font(R.font.orbitron_medium, FontWeight.Medium),
    Font(R.font.orbitron_bold, FontWeight.Bold),
)

val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
)

/** Colours the Material scheme has no slot for. */
object JarvisColors {
    val Good = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFC14D)
}

private val Scheme = darkColorScheme(
    primary = Color(0xFF2EE6FF),
    onPrimary = Color(0xFF00212A),
    primaryContainer = Color(0xFF0A3F4C),
    onPrimaryContainer = Color(0xFFBFF6FF),
    secondary = Color(0xFF8B7CFF),
    onSecondary = Color(0xFF120A3A),
    secondaryContainer = Color(0xFF2B2466),
    onSecondaryContainer = Color(0xFFE3DEFF),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF3B2100),
    background = Color(0xFF03050B),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0A101C),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF121A2B),
    onSurfaceVariant = Color(0xFFA3B4CF),
    surfaceDim = Color(0xFF03050B),
    surfaceBright = Color(0xFF1B2740),
    surfaceContainerLowest = Color(0xFF02040A),
    surfaceContainerLow = Color(0xFF080D18),
    surfaceContainer = Color(0xFF0C1322),
    surfaceContainerHigh = Color(0xFF111A2C),
    surfaceContainerHighest = Color(0xFF18233A),
    outline = Color(0xFF2A3A57),
    outlineVariant = Color(0xFF1A2640),
    error = Color(0xFFFF5C7A),
    onError = Color(0xFF2A0008),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = 1.sp),
    displayMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = 1.sp),
    displaySmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = 0.8.sp),
    headlineLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.8.sp),
    headlineMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = 0.8.sp),
    headlineSmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
