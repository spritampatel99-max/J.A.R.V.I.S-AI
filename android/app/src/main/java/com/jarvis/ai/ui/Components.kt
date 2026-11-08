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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

/**
 * The app's backdrop: two slow-drifting pools of colour over a very faint dot grid. The grid is a separate layer that
 * never changes, so only the two colour pools are redrawn as they move.
 */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "aurora")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    val cyan = colors.primary
    val violet = colors.secondary

    Box(modifier.fillMaxSize().background(colors.background)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val radius = maxOf(w, h) * 0.8f
            val first = Offset(w * (0.05f + 0.6f * drift), h * (0.05f + 0.15f * drift))
            drawCircle(
                brush = Brush.radialGradient(listOf(cyan.copy(alpha = 0.17f), Color.Transparent), center = first, radius = radius),
                radius = radius,
                center = first,
            )
            val second = Offset(w * (0.95f - 0.6f * drift), h * (0.95f - 0.2f * drift))
            drawCircle(
                brush = Brush.radialGradient(listOf(violet.copy(alpha = 0.15f), Color.Transparent), center = second, radius = radius),
                radius = radius,
                center = second,
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val step = 28.dp.toPx()
            val dot = 1.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) {
                    drawCircle(Color.White.copy(alpha = 0.045f), radius = dot, center = Offset(x, y))
                    x += step
                }
                y += step
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

/** A card with a soft gradient fill and a thin glowing edge. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh.copy(alpha = 0.94f), colors.surfaceContainer.copy(alpha = 0.94f))))
            .border(
                1.dp,
                Brush.linearGradient(listOf((accent ?: colors.primary).copy(alpha = 0.38f), colors.outline.copy(alpha = 0.25f))),
                shape,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** The app's main button: a cyan-to-violet gradient that dips slightly when pressed. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtle: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val shape = RoundedCornerShape(14.dp)
    val fill = if (subtle) {
        Brush.horizontalGradient(listOf(colors.surfaceContainerHighest, colors.surfaceContainerHighest))
    } else {
        Brush.horizontalGradient(listOf(colors.primary, colors.secondary))
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .clip(shape)
            .background(fill)
            .then(if (subtle) Modifier.border(1.dp, colors.outline, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (subtle) colors.onSurface else colors.onPrimary,
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

// ---- Tab bar -----------------------------------------------------------------------------------------------------

enum class AppTab(val title: String) {
    CHAT("Chat"),
    MODELS("Models"),
    CLOUD("Cloud"),
    ACCESS("Access"),
    LOG("Log"),
}

/** Bottom navigation with a sliding indicator and icons that lift when selected. */
@Composable
fun JarvisTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tabs = AppTab.entries
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow)
            .navigationBarsPadding(),
    ) {
        val tabWidth = maxWidth / tabs.size
        val indicatorX by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "indicator",
        )
        Box(
            Modifier
                .offset(x = indicatorX + tabWidth * 0.25f)
                .width(tabWidth * 0.5f)
                .height(3.dp)
                .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                .background(Brush.horizontalGradient(listOf(colors.primary, colors.secondary))),
        )
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            tabs.forEachIndexed { index, tab ->
                TabItem(tab, selected = index == selected, onClick = { onSelect(index) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabItem(tab: AppTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
        animationSpec = tween(250),
        label = "tabTint",
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) -3f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "tabLift",
    )
    Column(
        modifier
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
            .padding(top = 9.dp, bottom = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Canvas(
            Modifier
                .size(24.dp)
                .graphicsLayer { translationY = lift.dp.toPx() },
        ) {
            drawGlyph(tab, tint)
        }
        Text(tab.title, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** Hand-drawn line icons on a 24 x 24 grid, so the app needs no icon library. */
private fun DrawScope.drawGlyph(tab: AppTab, color: Color) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)

    when (tab) {
        AppTab.CHAT -> {
            drawRoundRect(color, p(3f, 4f), Size(18f * s, 12f * s), CornerRadius(4f * s), style = stroke)
            val tail = Path().apply {
                moveTo(8f * s, 16f * s)
                lineTo(8f * s, 20.5f * s)
                lineTo(12.5f * s, 16f * s)
            }
            drawPath(tail, color, style = stroke)
        }
        AppTab.MODELS -> {
            drawRoundRect(color, p(6f, 6f), Size(12f * s, 12f * s), CornerRadius(2f * s), style = stroke)
            drawRoundRect(color, p(9.5f, 9.5f), Size(5f * s, 5f * s), CornerRadius(1f * s), style = stroke)
            for (x in listOf(9f, 12f, 15f)) {
                drawLine(color, p(x, 2.5f), p(x, 6f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, p(x, 18f), p(x, 21.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            for (y in listOf(9f, 12f, 15f)) {
                drawLine(color, p(2.5f, y), p(6f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, p(18f, y), p(21.5f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
        AppTab.CLOUD -> {
            for (top in listOf(3.5f, 9.75f, 16f)) {
                drawRoundRect(color, p(3f, top), Size(18f * s, 4.5f * s), CornerRadius(1.6f * s), style = stroke)
                drawCircle(color, radius = 0.9f * s, center = p(6.6f, top + 2.25f))
            }
        }
        AppTab.ACCESS -> {
            val shield = Path().apply {
                moveTo(12f * s, 2.5f * s)
                lineTo(20f * s, 5.5f * s)
                lineTo(20f * s, 11.5f * s)
                cubicTo(20f * s, 16.5f * s, 16.5f * s, 20f * s, 12f * s, 21.5f * s)
                cubicTo(7.5f * s, 20f * s, 4f * s, 16.5f * s, 4f * s, 11.5f * s)
                lineTo(4f * s, 5.5f * s)
                close()
            }
            drawPath(shield, color, style = stroke)
            val tick = Path().apply {
                moveTo(8.5f * s, 12f * s)
                lineTo(11f * s, 14.5f * s)
                lineTo(15.5f * s, 9.5f * s)
            }
            drawPath(tick, color, style = stroke)
        }
        AppTab.LOG -> {
            val pulse = Path().apply {
                moveTo(2f * s, 12f * s)
                lineTo(7f * s, 12f * s)
                lineTo(10f * s, 5f * s)
                lineTo(14f * s, 19f * s)
                lineTo(17f * s, 12f * s)
                lineTo(22f * s, 12f * s)
            }
            drawPath(pulse, color, style = stroke)
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
