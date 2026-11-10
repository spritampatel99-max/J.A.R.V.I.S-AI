package com.jarvis.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What the assistant is doing right now. Each state has its own colours, as in the Home screen. */
enum class VoiceState(val label: String, val colors: List<Color>) {
    IDLE("Ready", listOf(JarvisColors.Cyan, JarvisColors.Violet, JarvisColors.Magenta)),
    LISTENING("Listening", listOf(JarvisColors.Cyan, Color(0xFF1E50FF), JarvisColors.Magenta)),
    THINKING("Thinking", listOf(JarvisColors.Violet, Color(0xFF1E50FF), JarvisColors.Magenta)),
    SPEAKING("Speaking", listOf(JarvisColors.Magenta, JarvisColors.Coral, JarvisColors.Violet)),
}

/** Seconds since this composable appeared, updated every frame; stays 0 when motion is off. */
@Composable
private fun rememberClock(): androidx.compose.runtime.State<Float> {
    val look = LocalLook.current
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(look.animate) {
        if (look.animate) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                time.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    return time
}

/**
 * The living core from the Home screen: layered nebula glow, a breathing disc, gyroscopic dashed rings and twinkling
 * stars, all drawn in one canvas. It is decoration only: the state is also written in words next to it.
 *
 * [level] (0..1) is the microphone loudness while listening; the core swells and the glow brightens with it.
 */
@Composable
fun HeroOrb(state: VoiceState, level: Float, modifier: Modifier = Modifier, diameter: Dp = 300.dp) {
    val look = LocalLook.current
    val clock = rememberClock()
    val flare = look.flare.coerceIn(0.1f, 1f)

    Canvas(modifier.size(diameter)) {
        val t = clock.value
        val c = center
        val r = size.minDimension / 2f
        val palette = state.colors
        val breathe = 1f + 0.035f * sin(t * 2f * PI.toFloat() / 5f)
        val energy = when (state) {
            VoiceState.IDLE -> 0.55f
            VoiceState.LISTENING -> 0.75f + 0.25f * level.coerceIn(0f, 1f)
            VoiceState.THINKING -> 0.8f
            VoiceState.SPEAKING -> 0.85f + 0.1f * sin(t * 6f)
        }

        // 1. Nebula glow behind everything.
        fun glow(radius: Float, alpha: Float, first: Color, second: Color, third: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(first.copy(alpha = alpha), second.copy(alpha = alpha * 0.75f), third.copy(alpha = alpha * 0.45f), Color.Transparent),
                    center = c,
                    radius = radius,
                ),
                radius = radius,
                center = c,
            )
        }
        glow(r * 1.0f * breathe, 0.55f * flare * energy, palette[0], palette[1], palette[2])
        glow(r * 0.78f, 0.45f * flare * energy, Color.White, palette[0], palette[1])

        // 2. Frosted disc and breathing core.
        drawCircle(Color.White.copy(alpha = 0.05f), radius = r * 0.62f, center = c)
        drawCircle(Color.White.copy(alpha = 0.28f), radius = r * 0.62f, center = c, style = Stroke(width = 1.dp.toPx()))
        val core = r * 0.5f * breathe * (1f + 0.12f * level.coerceIn(0f, 1f))
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f), palette[0].copy(alpha = 0.9f), palette[1].copy(alpha = 0.7f), palette[2].copy(alpha = 0.0f)),
                center = c,
                radius = core,
            ),
            radius = core,
            center = c,
        )

        // 3. Rings.
        val dash1 = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 14.dp.toPx()), 0f)
        val dash2 = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 14.dp.toPx(), 2.dp.toPx(), 14.dp.toPx()), 0f)
        val dash3 = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 9.dp.toPx(), 4.dp.toPx(), 9.dp.toPx()), 0f)
        val speed = if (state == VoiceState.IDLE) 1f else 2.2f
        rotate(t * 360f / 32f * speed, pivot = c) {
            drawCircle(palette[0].copy(alpha = 0.6f), radius = r * 0.97f, center = c, style = Stroke(1.2.dp.toPx(), pathEffect = dash1))
        }
        rotate(-t * 360f / 24f * speed, pivot = c) {
            drawCircle(palette[0].copy(alpha = 0.7f), radius = r * 0.9f, center = c, style = Stroke(1.4.dp.toPx(), pathEffect = dash2))
        }
        rotate(t * 360f / 20f * speed, pivot = c) {
            ellipse(c, r * 0.83f, r * 0.39f, palette[2].copy(alpha = 0.8f), Stroke(1.5.dp.toPx(), pathEffect = dash3))
        }
        rotate(-t * 360f / 24f * speed, pivot = c) {
            ellipse(c, r * 0.46f, r * 0.8f, palette[0].copy(alpha = 0.85f), Stroke(1.3.dp.toPx(), pathEffect = dash2))
        }

        // 4. Ripples while listening or speaking.
        if (state == VoiceState.LISTENING || state == VoiceState.SPEAKING) {
            for (i in 0 until 2) {
                val phase = ((t / 2.4f) + i * 0.5f) % 1f
                drawCircle(
                    palette[if (i == 0) 0 else 1].copy(alpha = 0.8f * (1f - phase)),
                    radius = r * (0.65f + 0.35f * phase),
                    center = c,
                    style = Stroke(1.5.dp.toPx()),
                )
            }
        }

        // 5. Stars.
        val stars = listOf(
            Triple(-0.30f, -0.36f, Color.White), Triple(0.28f, -0.30f, palette[0]), Triple(0.10f, 0.52f, palette[2]),
            Triple(-0.26f, 0.40f, palette[0]), Triple(0.40f, 0.14f, Color.White), Triple(0.0f, -0.62f, JarvisColors.Coral),
            Triple(-0.56f, 0.0f, palette[0]), Triple(0.5f, -0.1f, palette[1]),
        )
        stars.forEachIndexed { i, (x, y, color) ->
            val twinkle = 0.35f + 0.65f * abs(sin(t * (0.9f + i * 0.23f) + i))
            drawCircle(color.copy(alpha = twinkle), radius = (1.6f + 0.9f * twinkle).dp.toPx(), center = Offset(c.x + x * r, c.y + y * r))
        }
    }
}

private fun DrawScope.ellipse(c: Offset, rx: Float, ry: Float, color: Color, stroke: Stroke) {
    drawOval(color, topLeft = Offset(c.x - rx, c.y - ry), size = Size(rx * 2f, ry * 2f), style = stroke)
}

/**
 * Seven small bars in the spectrum, as under the Home orb. They move with [level] while listening (the real microphone
 * loudness) and gently on their own while thinking or speaking; when [state] is IDLE they rest.
 */
@Composable
fun VoiceWave(state: VoiceState, level: Float, modifier: Modifier = Modifier) {
    val clock = rememberClock()
    val colors = listOf(JarvisColors.Cyan, Color(0xFF38BDF8), Color(0xFFA855F7), Color(0xFFF43F5E), Color(0xFFFB923C), JarvisColors.Cyan, JarvisColors.Lilac)
    Row(
        modifier
            .height(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEachIndexed { i, color ->
            val t = if (state == VoiceState.IDLE) 0f else clock.value
            val h = when (state) {
                VoiceState.IDLE -> if (i % 2 == 0) 6f else 10f
                VoiceState.LISTENING -> 5f + 17f * level.coerceIn(0f, 1f) * (0.5f + 0.5f * abs(sin(t * 7f + i)))
                VoiceState.THINKING -> 6f + 8f * abs(sin(t * 2.5f + i * 0.6f))
                VoiceState.SPEAKING -> 6f + 14f * abs(sin(t * 6f + i * 0.9f))
            }
            androidx.compose.foundation.layout.Box(Modifier.width(4.dp).height(h.dp).clip(CircleShape).background(color))
        }
    }
}
