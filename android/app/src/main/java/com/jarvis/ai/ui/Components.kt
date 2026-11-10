package com.jarvis.ai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.SolidColor
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---- Background -------------------------------------------------------------------------------------------------

/** Visual preferences every screen reads: whether the ambient layer and motion are on, and how bright the orb glows. */
data class Look(val ambient: Boolean = true, val animate: Boolean = true, val flare: Float = 0.85f)

val LocalLook = staticCompositionLocalOf { Look() }

/**
 * The app's backdrop from the Stitch screens: a near-black blue gradient with three slow-drifting pools of cyan, violet
 * and magenta light, and (when ambient effects are on) a very faint dot grid. With motion off it stays still.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val look = LocalLook.current
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(look.animate) {
        if (look.animate) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                t += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(JarvisColors.Void, Color(0xFF090E1C), JarvisColors.VoiceDeep))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val phase = t / 14f
            fun plume(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
                val c = Offset(cx, cy)
                drawCircle(
                    brush = Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = c, radius = r),
                    radius = r,
                    center = c,
                )
            }
            plume(w * (0.18f + 0.10f * sin(phase)), h * 0.10f, w * 0.95f, JarvisColors.Cyan, 0.16f)
            plume(w * (0.85f - 0.10f * sin(phase * 0.8f)), h * 0.45f, w * 0.85f, JarvisColors.Violet, 0.17f)
            plume(w * 0.2f, h * (0.92f + 0.03f * cos(phase)), w * 0.85f, JarvisColors.Magenta, 0.12f)
        }
        if (look.ambient) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 24.dp.toPx()
                val dot = 1.dp.toPx()
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(Color.White.copy(alpha = 0.04f), radius = dot, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
        }
        content()
    }
}

// ---- The orb ----------------------------------------------------------------------------------------------------

/**
 * J.A.R.V.I.S.'s signature: a glowing core inside two counter-rotating rings. It idles slowly and spins up while
 * [active] (the assistant is working). Purely decorative: it carries no information that is not also in text.
 */
@Composable
fun PulseOrb(active: Boolean, modifier: Modifier = Modifier, diameter: Dp = 56.dp) {
    val colors = MaterialTheme.colorScheme
    val cyan = colors.primary
    val violet = colors.secondary

    val speed by animateFloatAsState(if (active) 150f else 26f, tween(800), label = "orbSpeed")
    val speedNow = rememberUpdatedState(speed)
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            angle = (angle + (now - last) / 1_000_000_000f * speedNow.value) % 360f
            last = now
        }
    }
    val pulse by rememberInfiniteTransition(label = "orbPulse").animateFloat(
        initialValue = 0.86f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Canvas(modifier.size(diameter)) {
        val c = center
        val r = this.size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(listOf(cyan.copy(alpha = 0.5f * pulse), Color.Transparent), center = c, radius = r),
            radius = r,
            center = c,
        )
        val coreRadius = r * 0.46f * (0.86f + 0.14f * pulse)
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, cyan, cyan.copy(alpha = 0f)), center = c, radius = coreRadius),
            radius = coreRadius,
            center = c,
        )
        val stroke = Stroke(width = r * 0.07f, cap = StrokeCap.Round)
        val outer = r * 0.9f
        val inner = r * 0.68f
        rotate(angle, pivot = c) {
            drawArc(cyan, 0f, 110f, false, Offset(c.x - outer, c.y - outer), Size(outer * 2, outer * 2), style = stroke)
            drawArc(cyan.copy(alpha = 0.55f), 180f, 70f, false, Offset(c.x - outer, c.y - outer), Size(outer * 2, outer * 2), style = stroke)
        }
        rotate(-angle * 1.4f, pivot = c) {
            drawArc(violet, 40f, 80f, false, Offset(c.x - inner, c.y - inner), Size(inner * 2, inner * 2), style = stroke)
            drawArc(violet.copy(alpha = 0.55f), 220f, 60f, false, Offset(c.x - inner, c.y - inner), Size(inner * 2, inner * 2), style = stroke)
        }
    }
}

// ---- Small motion helpers ----------------------------------------------------------------------------------------

/** Three bouncing dots, shown while the assistant is working. */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val color = MaterialTheme.colorScheme.primary
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1100
                        1f at 250 using FastOutSlowInEasing
                        0f at 500
                    },
                    initialStartOffset = StartOffset(index * 140),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        translationY = -6.dp.toPx() * lift
                        alpha = 0.45f + 0.55f * lift
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

/** Fades and slides its content in the first time it appears. [delayMillis] staggers a list of these. */
@Composable
fun AppearIn(modifier: Modifier = Modifier, delayMillis: Int = 0, content: @Composable () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible,
        modifier = modifier,
        enter = fadeIn(tween(380, delayMillis)) +
            slideInVertically(tween(380, delayMillis, FastOutSlowInEasing)) { it / 7 },
    ) {
        content()
    }
}

// ---- Surfaces and buttons ----------------------------------------------------------------------------------------

/** A glass card: dark gradient fill, thin white edge (or a tinted one when [accent] is given). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF141A2E).copy(alpha = 0.92f), Color(0xFF0D1222).copy(alpha = 0.94f))))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        (accent ?: Color.White).copy(alpha = if (accent != null) 0.42f else 0.12f),
                        Color.White.copy(alpha = 0.06f),
                    ),
                ),
                shape,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** The main button: the cyan-violet-magenta spectrum as a pill that dips when pressed. [subtle] is the glass variant. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtle: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(shape)
            .background(if (subtle) SolidColor(Color.White.copy(alpha = 0.06f)) else JarvisColors.spectrumHorizontal())
            .then(if (subtle) Modifier.border(1.dp, Color.White.copy(alpha = 0.14f), shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (subtle) MaterialTheme.colorScheme.onSurface else Color.White,
        )
    }
}

/** A small rounded status tag such as "Ready" or "Installed". */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

// ---- Navigation --------------------------------------------------------------------------------------------------

enum class AppTab(val title: String, val glyph: Glyph) {
    HOME("Home", Glyph.HOME),
    CHAT("Chat", Glyph.CHAT),
    TOOLS("Tools", Glyph.TOOLS),
    LOGS("Logs", Glyph.LOGS),
    PREFS("Prefs", Glyph.PREFS),
}

/** The floating pill navigation from the Stitch screens: the active tab glows in the spectrum. */
@Composable
fun FloatingNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = 384.dp)
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Color(0xFF080D1E).copy(alpha = 0.94f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEachIndexed { index, tab ->
                NavItem(tab, active = index == selected, onClick = { onSelect(index) })
            }
        }
    }
}

@Composable
private fun NavItem(tab: AppTab, active: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) JarvisColors.Cyan else JarvisColors.Muted, tween(250), label = "navTint")
    val glow by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "navGlow")
    Column(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        JarvisColors.Cyan.copy(alpha = 0.22f * glow),
                        JarvisColors.Violet.copy(alpha = 0.22f * glow),
                        JarvisColors.Magenta.copy(alpha = 0.18f * glow),
                    ),
                ),
            )
            .border(1.dp, JarvisColors.Cyan.copy(alpha = 0.45f * glow), CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(tab.glyph, tint, size = 20.dp)
        Text(tab.title, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

// ---- Small shared pieces -----------------------------------------------------------------------------------------

/** A section heading as in the Prefs screen: a cyan icon, an uppercase title, and a short note on the right. */
@Composable
fun SectionTitle(glyph: Glyph, title: String, note: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, JarvisColors.Cyan, size = 18.dp)
        Text(
            title.uppercase(),
            Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = JarvisColors.Cyan,
            fontWeight = FontWeight.SemiBold,
        )
        if (note != null) Text(note.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** The spectrum switch from the Stitch screens. */
@Composable
fun SpectrumSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, tween(250), label = "knob")
    Box(
        modifier
            .size(width = 48.dp, height = 28.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape)
            .background(if (checked) JarvisColors.spectrumHorizontal() else SolidColor(Color(0xFF1C243C)))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(2.dp),
    ) {
        Box(Modifier.offset(x = knob).size(24.dp).background(Color.White, CircleShape))
    }
}

/** A round glass button holding one icon. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 48.dp,
    description: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint, size = size * 0.46f)
    }
}

/** The top bar: the brand mark with its spectrum ring, the product name, and a profile ring that opens Prefs. */
@Composable
fun AppHeader(onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(JarvisColors.Void.copy(alpha = 0.85f))
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(JarvisColors.spectrum()).padding(1.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(JarvisColors.Void), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.HUB, JarvisColors.Cyan, size = 20.dp)
            }
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text("JARVIS 2.0", style = MaterialTheme.typography.labelLarge, color = JarvisColors.Ink, fontWeight = FontWeight.Bold)
            Text(
                "07 // PRISMATIC SPECTRAL CORE",
                style = MaterialTheme.typography.labelSmall.copy(brush = JarvisColors.spectrumHorizontal()),
            )
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(JarvisColors.spectrum())
                .padding(1.5.dp)
                .clickable(role = Role.Button, onClick = onProfile)
                .semantics { contentDescription = "Open preferences" },
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(JarvisColors.Void), contentAlignment = Alignment.Center) {
                GlyphIcon(Glyph.PERSON, JarvisColors.Cyan, size = 18.dp)
            }
        }
    }
}

/** A small arrow used by the send button. */
fun DrawScope.drawSendArrow(color: Color) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = 2.2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val path = Path().apply {
        moveTo(12f * s, 19f * s)
        lineTo(12f * s, 5f * s)
        moveTo(6f * s, 11f * s)
        lineTo(12f * s, 5f * s)
        lineTo(18f * s, 11f * s)
    }
    drawPath(path, color, style = stroke)
}
