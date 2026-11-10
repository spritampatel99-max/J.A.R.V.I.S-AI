package com.jarvis.ai.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Every icon in the app, drawn as line art on a 24 x 24 grid, so the app needs no icon library and no icon font.
 * They stand in for the Material Symbols used in the Stitch screens.
 */
enum class Glyph {
    HOME, CHAT, TOOLS, LOGS, PREFS,
    MIC, CALL, SEARCH, TUNE, LOCK, SHIELD, PERSON,
    CHEVRON_RIGHT, CHEVRON_DOWN, ARROW_LEFT, ARROW_UP, CLOSE, CHECK, CHECK_CIRCLE,
    CLOCK, GLOBE, CALENDAR, HUB, TRASH, STORAGE, BELL, DEVICE, BOLT, WAVE,
    VOLUME, VOLUME_OFF, STOP, WARNING, INFO, SPARK, CHIP, CLOUD, FLASHLIGHT, SEND_PLANE,
}

@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, strokeWidth: Float = 1.8f) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, tint, strokeWidth) }
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color, strokeWidth: Float = 1.8f) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = strokeWidth * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
    fun path(build: Path.() -> Unit, fill: Boolean = false) =
        drawPath(Path().apply(build), color, style = if (fill) Fill else stroke)
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(color, radius = r * s, center = p(cx, cy), style = stroke)
    fun dot(cx: Float, cy: Float, r: Float) = drawCircle(color, radius = r * s, center = p(cx, cy))
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        drawRoundRect(color, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = stroke)

    when (glyph) {
        Glyph.HOME -> {
            box(3.5f, 3.5f, 7f, 7f, 1.8f); box(13.5f, 3.5f, 7f, 7f, 1.8f)
            box(3.5f, 13.5f, 7f, 7f, 1.8f); box(13.5f, 13.5f, 7f, 7f, 1.8f)
        }
        Glyph.CHAT -> {
            box(3f, 4f, 18f, 12f, 4f)
            path({ moveTo(8f * s, 16f * s); lineTo(8f * s, 20.5f * s); lineTo(12.5f * s, 16f * s) })
            dot(8f, 10f, 1.1f); dot(12f, 10f, 1.1f); dot(16f, 10f, 1.1f)
        }
        Glyph.TOOLS -> {
            box(3f, 4f, 18f, 16f, 3f)
            path({ moveTo(7.5f * s, 9.5f * s); lineTo(11f * s, 12.5f * s); lineTo(7.5f * s, 15.5f * s) })
            line(13f, 15.5f, 17f, 15.5f)
        }
        Glyph.LOGS -> path({
            moveTo(2f * s, 12f * s); lineTo(7f * s, 12f * s); lineTo(10f * s, 5f * s)
            lineTo(14f * s, 19f * s); lineTo(17f * s, 12f * s); lineTo(22f * s, 12f * s)
        })
        Glyph.PREFS -> {
            ring(12f, 12f, 3.2f)
            ring(12f, 12f, 7f)
            for (i in 0 until 8) {
                val a = (i * 45.0) * PI / 180.0
                line(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(), 12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
            }
        }
        Glyph.MIC -> {
            box(9f, 3f, 6f, 11f, 3f)
            path({ moveTo(5.5f * s, 11f * s); cubicTo(5.5f * s, 16f * s, 18.5f * s, 16f * s, 18.5f * s, 11f * s) })
            line(12f, 16.5f, 12f, 20.5f); line(8.5f, 20.5f, 15.5f, 20.5f)
        }
        Glyph.CALL -> path({
            moveTo(5f * s, 4f * s); lineTo(9f * s, 4f * s); lineTo(10.5f * s, 8.5f * s); lineTo(8.5f * s, 10f * s)
            cubicTo(9.6f * s, 12.4f * s, 11.6f * s, 14.4f * s, 14f * s, 15.5f * s); lineTo(15.5f * s, 13.5f * s)
            lineTo(20f * s, 15f * s); lineTo(20f * s, 19f * s)
            cubicTo(20f * s, 19.6f * s, 19.6f * s, 20f * s, 19f * s, 20f * s)
            cubicTo(10.7f * s, 20f * s, 4f * s, 13.3f * s, 4f * s, 5f * s)
            cubicTo(4f * s, 4.4f * s, 4.4f * s, 4f * s, 5f * s, 4f * s); close()
        })
        Glyph.SEARCH -> { ring(10.5f, 10.5f, 6.5f); line(15.5f, 15.5f, 20.5f, 20.5f) }
        Glyph.TUNE -> {
            line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
            dot(9f, 7f, 2.1f); dot(15f, 12f, 2.1f); dot(8f, 17f, 2.1f)
        }
        Glyph.LOCK -> {
            box(5f, 10.5f, 14f, 10f, 2.5f)
            path({ moveTo(8f * s, 10.5f * s); lineTo(8f * s, 8f * s); cubicTo(8f * s, 2.8f * s, 16f * s, 2.8f * s, 16f * s, 8f * s); lineTo(16f * s, 10.5f * s) })
            dot(12f, 15.5f, 1.2f)
        }
        Glyph.SHIELD -> {
            path({
                moveTo(12f * s, 2.5f * s); lineTo(20f * s, 5.5f * s); lineTo(20f * s, 11.5f * s)
                cubicTo(20f * s, 16.5f * s, 16.5f * s, 20f * s, 12f * s, 21.5f * s)
                cubicTo(7.5f * s, 20f * s, 4f * s, 16.5f * s, 4f * s, 11.5f * s); lineTo(4f * s, 5.5f * s); close()
            })
            path({ moveTo(8.5f * s, 12f * s); lineTo(11f * s, 14.5f * s); lineTo(15.5f * s, 9.5f * s) })
        }
        Glyph.PERSON -> { ring(12f, 8f, 3.6f); path({ moveTo(4.5f * s, 20f * s); cubicTo(4.5f * s, 13.5f * s, 19.5f * s, 13.5f * s, 19.5f * s, 20f * s) }) }
        Glyph.CHEVRON_RIGHT -> path({ moveTo(9f * s, 5f * s); lineTo(16f * s, 12f * s); lineTo(9f * s, 19f * s) })
        Glyph.CHEVRON_DOWN -> path({ moveTo(5f * s, 9f * s); lineTo(12f * s, 16f * s); lineTo(19f * s, 9f * s) })
        Glyph.ARROW_LEFT -> path({ moveTo(19f * s, 12f * s); lineTo(5f * s, 12f * s); moveTo(11f * s, 6f * s); lineTo(5f * s, 12f * s); lineTo(11f * s, 18f * s) })
        Glyph.ARROW_UP -> path({ moveTo(12f * s, 19f * s); lineTo(12f * s, 5f * s); moveTo(6f * s, 11f * s); lineTo(12f * s, 5f * s); lineTo(18f * s, 11f * s) })
        Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.CHECK -> path({ moveTo(5f * s, 12.5f * s); lineTo(10f * s, 17.5f * s); lineTo(19f * s, 7f * s) })
        Glyph.CHECK_CIRCLE -> { ring(12f, 12f, 9f); path({ moveTo(7.8f * s, 12.4f * s); lineTo(10.7f * s, 15.2f * s); lineTo(16.2f * s, 9.2f * s) }) }
        Glyph.CLOCK -> { ring(12f, 12f, 9f); path({ moveTo(12f * s, 7f * s); lineTo(12f * s, 12f * s); lineTo(15.5f * s, 14f * s) }) }
        Glyph.GLOBE -> {
            ring(12f, 12f, 9f); line(3f, 12f, 21f, 12f)
            path({ moveTo(12f * s, 3f * s); cubicTo(7f * s, 8f * s, 7f * s, 16f * s, 12f * s, 21f * s) })
            path({ moveTo(12f * s, 3f * s); cubicTo(17f * s, 8f * s, 17f * s, 16f * s, 12f * s, 21f * s) })
        }
        Glyph.CALENDAR -> { box(3.5f, 5f, 17f, 15.5f, 3f); line(3.5f, 10f, 20.5f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f) }
        Glyph.HUB -> {
            ring(12f, 12f, 2.6f); ring(5f, 6f, 2f); ring(19f, 6f, 2f); ring(12f, 20f, 2f)
            line(6.5f, 7.3f, 10f, 10.5f); line(17.5f, 7.3f, 14f, 10.5f); line(12f, 14.6f, 12f, 18f)
        }
        Glyph.TRASH -> {
            line(4f, 7f, 20f, 7f); line(9.5f, 3.5f, 14.5f, 3.5f)
            path({ moveTo(6f * s, 7f * s); lineTo(7f * s, 20f * s); lineTo(17f * s, 20f * s); lineTo(18f * s, 7f * s) })
            line(10f, 11f, 10f, 16f); line(14f, 11f, 14f, 16f)
        }
        Glyph.STORAGE -> {
            drawOval(color, p(4.5f, 3.5f), Size(15f * s, 5f * s), style = stroke)
            path({ moveTo(4.5f * s, 6f * s); lineTo(4.5f * s, 18f * s); cubicTo(4.5f * s, 21f * s, 19.5f * s, 21f * s, 19.5f * s, 18f * s); lineTo(19.5f * s, 6f * s) })
            path({ moveTo(4.5f * s, 12f * s); cubicTo(4.5f * s, 15f * s, 19.5f * s, 15f * s, 19.5f * s, 12f * s) })
        }
        Glyph.BELL -> {
            path({
                moveTo(6f * s, 16.5f * s); cubicTo(6f * s, 13f * s, 6f * s, 11f * s, 6.5f * s, 9.5f * s)
                cubicTo(7.5f * s, 5f * s, 16.5f * s, 5f * s, 17.5f * s, 9.5f * s)
                cubicTo(18f * s, 11f * s, 18f * s, 13f * s, 18f * s, 16.5f * s); close()
            })
            line(4.5f, 17f, 19.5f, 17f); path({ moveTo(10f * s, 20f * s); cubicTo(10.5f * s, 21.5f * s, 13.5f * s, 21.5f * s, 14f * s, 20f * s) })
        }
        Glyph.DEVICE -> { box(7f, 2.5f, 10f, 19f, 2.5f); line(10.5f, 18.5f, 13.5f, 18.5f) }
        Glyph.BOLT -> path({ moveTo(13f * s, 2.5f * s); lineTo(5f * s, 13.5f * s); lineTo(11f * s, 13.5f * s); lineTo(10f * s, 21.5f * s); lineTo(19f * s, 10f * s); lineTo(13f * s, 10f * s); close() })
        Glyph.WAVE -> { line(5f, 9f, 5f, 15f); line(9f, 5f, 9f, 19f); line(13f, 8f, 13f, 16f); line(17f, 3.5f, 17f, 20.5f); line(21f, 10f, 21f, 14f) }
        Glyph.VOLUME -> {
            path({ moveTo(4f * s, 9.5f * s); lineTo(8f * s, 9.5f * s); lineTo(13f * s, 5f * s); lineTo(13f * s, 19f * s); lineTo(8f * s, 14.5f * s); lineTo(4f * s, 14.5f * s); close() })
            path({ moveTo(16f * s, 9f * s); cubicTo(17.5f * s, 11f * s, 17.5f * s, 13f * s, 16f * s, 15f * s) })
            path({ moveTo(18.5f * s, 6.5f * s); cubicTo(21.5f * s, 10f * s, 21.5f * s, 14f * s, 18.5f * s, 17.5f * s) })
        }
        Glyph.VOLUME_OFF -> {
            path({ moveTo(4f * s, 9.5f * s); lineTo(8f * s, 9.5f * s); lineTo(13f * s, 5f * s); lineTo(13f * s, 19f * s); lineTo(8f * s, 14.5f * s); lineTo(4f * s, 14.5f * s); close() })
            line(16.5f, 9.5f, 21f, 14.5f); line(21f, 9.5f, 16.5f, 14.5f)
        }
        Glyph.STOP -> box(6.5f, 6.5f, 11f, 11f, 2.2f)
        Glyph.WARNING -> {
            path({ moveTo(12f * s, 3.5f * s); lineTo(21.5f * s, 20f * s); lineTo(2.5f * s, 20f * s); close() })
            line(12f, 9.5f, 12f, 14f); dot(12f, 17f, 1.1f)
        }
        Glyph.INFO -> { ring(12f, 12f, 9f); line(12f, 11f, 12f, 16.5f); dot(12f, 7.8f, 1.1f) }
        Glyph.SPARK -> path({
            moveTo(12f * s, 3f * s); cubicTo(12.5f * s, 8.5f * s, 15.5f * s, 11.5f * s, 21f * s, 12f * s)
            cubicTo(15.5f * s, 12.5f * s, 12.5f * s, 15.5f * s, 12f * s, 21f * s)
            cubicTo(11.5f * s, 15.5f * s, 8.5f * s, 12.5f * s, 3f * s, 12f * s)
            cubicTo(8.5f * s, 11.5f * s, 11.5f * s, 8.5f * s, 12f * s, 3f * s); close()
        })
        Glyph.CHIP -> {
            box(6f, 6f, 12f, 12f, 2f); box(9.5f, 9.5f, 5f, 5f, 1f)
            for (x in listOf(9f, 12f, 15f)) { line(x, 2.5f, x, 6f); line(x, 18f, x, 21.5f) }
            for (y in listOf(9f, 12f, 15f)) { line(2.5f, y, 6f, y); line(18f, y, 21.5f, y) }
        }
        Glyph.CLOUD -> path({
            moveTo(7f * s, 19f * s); cubicTo(3f * s, 19f * s, 2.5f * s, 12.5f * s, 7.5f * s, 12f * s)
            cubicTo(8f * s, 6f * s, 17f * s, 6f * s, 17.5f * s, 12f * s)
            cubicTo(22.5f * s, 12.5f * s, 22f * s, 19f * s, 17.5f * s, 19f * s); close()
        })
        Glyph.FLASHLIGHT -> {
            path({ moveTo(8f * s, 3f * s); lineTo(16f * s, 3f * s); lineTo(15f * s, 9f * s); lineTo(9f * s, 9f * s); close() })
            box(9f, 9f, 6f, 12f, 2f); line(12f, 12.5f, 12f, 14.5f)
        }
        Glyph.SEND_PLANE -> path({ moveTo(3f * s, 11f * s); lineTo(21f * s, 3.5f * s); lineTo(14f * s, 21f * s); lineTo(11.5f * s, 13f * s); close() })
    }
}
