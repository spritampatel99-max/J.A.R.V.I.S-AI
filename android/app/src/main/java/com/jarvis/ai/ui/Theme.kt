package com.jarvis.ai.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.jarvis.ai.data.ThemeMode
import com.jarvis.ai.data.UiPrefs
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

/** The colours that change between the dark and the light look (and, with dynamic colour, take their accents from the wallpaper). */
class JarvisPalette(
    val dark: Boolean,
    val void: Color,
    val backdropMid: Color,
    val voiceDeep: Color,
    val ink: Color,
    val muted: Color,
    val subtle: Color,
    val cardTop: Color,
    val cardBottom: Color,
    /** The base of the thin edges and faint fills: white on dark, dark on light. */
    val edge: Color,
    val nav: Color,
    val switchOff: Color,
    val good: Color,
    val warning: Color,
    val cyan: Color = Color(0xFF00E5FF),
    val violet: Color = Color(0xFF8B25FA),
    val magenta: Color = Color(0xFFFF2A8D),
)

private val DarkPalette = JarvisPalette(
    dark = true,
    void = Color(0xFF070A14), backdropMid = Color(0xFF090E1C), voiceDeep = Color(0xFF04060E),
    ink = Color(0xFFF8FAFC), muted = Color(0xFF94A3B8), subtle = Color(0xFF64748B),
    cardTop = Color(0xFF141A2E), cardBottom = Color(0xFF0D1222), edge = Color.White,
    nav = Color(0xFF080D1E), switchOff = Color(0xFF1C243C),
    good = Color(0xFF3DDC97), warning = Color(0xFFFFC14D),
)

private val LightPalette = JarvisPalette(
    dark = false,
    void = Color(0xFFF4F6FD), backdropMid = Color(0xFFEDF0FB), voiceDeep = Color(0xFFE6EAF8),
    ink = Color(0xFF0E1322), muted = Color(0xFF475069), subtle = Color(0xFF64748B),
    cardTop = Color(0xFFFFFFFF), cardBottom = Color(0xFFF1F4FC), edge = Color(0xFF0E1322),
    nav = Color(0xFFFFFFFF), switchOff = Color(0xFFCBD3E6),
    good = Color(0xFF0F8F5B), warning = Color(0xFFB36B00),
    cyan = Color(0xFF007C91), violet = Color(0xFF6D1FD0), magenta = Color(0xFFC21A6B),
)

/**
 * The spectrum, plus the few colours the Material scheme has no slot for. The values come from the current [JarvisPalette]; they are
 * read through Compose state, so a screen that reads one is redrawn when the look changes.
 */
object JarvisColors {
    private var palette by mutableStateOf(DarkPalette)

    internal fun use(next: JarvisPalette) {
        if (palette !== next) palette = next
    }

    val Cyan: Color get() = palette.cyan
    val Violet: Color get() = palette.violet
    val Magenta: Color get() = palette.magenta
    val Coral = Color(0xFFFF6F59)
    val Blue = Color(0xFF3678FF)
    val Lilac = Color(0xFFC084FC)
    val Sky = Color(0xFF85D0F7)

    val Void: Color get() = palette.void
    val BackdropMid: Color get() = palette.backdropMid
    val VoiceDeep: Color get() = palette.voiceDeep
    val Ink: Color get() = palette.ink
    val Muted: Color get() = palette.muted
    val Subtle: Color get() = palette.subtle
    val CardTop: Color get() = palette.cardTop
    val CardBottom: Color get() = palette.cardBottom
    val Edge: Color get() = palette.edge
    val Nav: Color get() = palette.nav
    val SwitchOff: Color get() = palette.switchOff

    val Good: Color get() = palette.good
    val Warning: Color get() = palette.warning

    /** cyan -> violet -> magenta, used for the brand ring, primary buttons, active switches. */
    val Spectrum: List<Color> get() = listOf(Cyan, Violet, Magenta)

    fun spectrum(): Brush = Brush.linearGradient(Spectrum)
    fun spectrumHorizontal(): Brush = Brush.horizontalGradient(Spectrum)
}

private val DarkScheme = darkColorScheme(
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

private val LightScheme = lightColorScheme(
    primary = Color(0xFF007C91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBFEFF8),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF6D1FD0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9DBFF),
    onSecondaryContainer = Color(0xFF22005A),
    tertiary = Color(0xFFC21A6B),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FD),
    onBackground = Color(0xFF0E1322),
    surface = Color(0xFFF4F6FD),
    onSurface = Color(0xFF0E1322),
    surfaceVariant = Color(0xFFE2E7F5),
    onSurfaceVariant = Color(0xFF424A63),
    surfaceDim = Color(0xFFDDE2F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4FC),
    surfaceContainer = Color(0xFFEBEFFA),
    surfaceContainerHigh = Color(0xFFE5E9F6),
    surfaceContainerHighest = Color(0xFFDFE4F2),
    outline = Color(0xFF6C7490),
    outlineVariant = Color(0xFFC3C9DD),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Whether the app is drawn dark: the person's choice, or the phone's own setting when it is left on "System". */
@Composable
fun isJarvisDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * [dynamicColor] takes the accent colours from the phone's wallpaper (Material You) instead of the JARVIS cyan, violet and magenta.
 * The dark and light looks are a first pass that has not been checked on a phone: some screens may need contrast fixes.
 */
@Composable
fun JarvisTheme(prefs: UiPrefs = UiPrefs(), content: @Composable () -> Unit) {
    val dark = isJarvisDark(prefs.themeMode)
    val context = LocalContext.current
    val base = if (dark) DarkScheme else LightScheme
    val scheme = if (prefs.dynamicColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        base
    }
    val basePalette = if (dark) DarkPalette else LightPalette
    JarvisColors.use(
        if (prefs.dynamicColor) {
            JarvisPalette(
                dark = basePalette.dark, void = basePalette.void, backdropMid = basePalette.backdropMid, voiceDeep = basePalette.voiceDeep,
                ink = basePalette.ink, muted = basePalette.muted, subtle = basePalette.subtle, cardTop = basePalette.cardTop,
                cardBottom = basePalette.cardBottom, edge = basePalette.edge, nav = basePalette.nav, switchOff = basePalette.switchOff,
                good = basePalette.good, warning = basePalette.warning,
                cyan = scheme.primary, violet = scheme.secondary, magenta = scheme.tertiary,
            )
        } else {
            basePalette
        },
    )
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
