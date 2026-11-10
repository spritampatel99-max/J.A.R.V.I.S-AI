package com.jarvis.ai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jarvis.ai.R

/*
 * Design: the "Prismatic Spectral Core" look from the Stitch screens (Home, Chat, Tools, Logs, Prefs): a near-black blue
 * void, cyan / violet / magenta light, glass cards with a thin white edge, Inter for reading and Space Grotesk for the
 * small uppercase labels. Colours, type sizes and radii below are copied from the Tailwind config in those screens.
 *
 * Fonts are bundled in the app (res/font) so they work offline: Inter and Space Grotesk, both SIL Open Font License 1.1
 * (licence texts in docs/legal). Material Symbols are not used; icons are drawn in code (see Glyphs.kt).
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

/** The spectrum, plus the few colours the Material scheme has no slot for. */
object JarvisColors {
    val Cyan = Color(0xFF00E5FF)
    val Violet = Color(0xFF8B25FA)
    val Magenta = Color(0xFFFF2A8D)
    val Coral = Color(0xFFFF6F59)
    val Blue = Color(0xFF3678FF)
    val Lilac = Color(0xFFC084FC)
    val Sky = Color(0xFF85D0F7)

    val Void = Color(0xFF070A14)
    val VoiceDeep = Color(0xFF04060E)
    val Ink = Color(0xFFF8FAFC)
    val Muted = Color(0xFF94A3B8)
    val Subtle = Color(0xFF64748B)

    val Good = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFC14D)

    /** cyan -> violet -> magenta, used for the brand ring, primary buttons, active switches. */
    val Spectrum: List<Color> = listOf(Cyan, Violet, Magenta)

    fun spectrum(): Brush = Brush.linearGradient(Spectrum)
    fun spectrumHorizontal(): Brush = Brush.horizontalGradient(Spectrum)
}

private val Scheme = darkColorScheme(
    primary = JarvisColors.Cyan,
    onPrimary = Color(0xFF003642),
    primaryContainer = Color(0xFF0B3A4A),
    onPrimaryContainer = Color(0xFFB2EBFF),
    secondary = JarvisColors.Violet,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF301561),
    onSecondaryContainer = Color(0xFFE6D6FF),
    tertiary = JarvisColors.Magenta,
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFF070A14),
    onBackground = Color(0xFFDFE1F5),
    surface = Color(0xFF070A14),
    onSurface = Color(0xFFDFE1F5),
    surfaceVariant = Color(0xFF182038),
    onSurfaceVariant = Color(0xFFC2C6D7),
    surfaceDim = Color(0xFF070A14),
    surfaceBright = Color(0xFF353948),
    surfaceContainerLowest = Color(0xFF05070F),
    surfaceContainerLow = Color(0xFF0D1222),
    surfaceContainer = Color(0xFF11172A),
    surfaceContainerHigh = Color(0xFF182038),
    surfaceContainerHighest = Color(0xFF222C4D),
    outline = Color(0xFF8C90A0),
    outlineVariant = Color(0xFF424655),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-0.03).em),
    displayMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em),
    displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em),
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.015).em),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.005).em),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = (-0.005).em),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.005.em),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.01.em),
    labelLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.06.em),
    labelMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.08.em),
    // The design uses 9 px here; 10 sp keeps the same look but is easier to read.
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.1.em),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
