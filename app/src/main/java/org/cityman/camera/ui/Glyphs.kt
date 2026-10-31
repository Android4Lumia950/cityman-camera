package org.cityman.camera.ui

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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val Accent = Color(0xFFFFB300)
val RecordRed = Color(0xFFFF3B30)

enum class Glyph { SWITCH, VIDEO, PHOTO, SETTINGS, GRID }

/** Line icons drawn for this app. */
@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 28.dp, color: Color = Color.White) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.SWITCH -> drawSwitch(color, stroke, s)
            Glyph.VIDEO -> drawVideo(color, stroke, s)
            Glyph.PHOTO -> drawPhoto(color, stroke, s)
            Glyph.SETTINGS -> drawSettings(color, stroke, s)
            Glyph.GRID -> drawGrid(color, stroke, s)
        }
    }
}

/** Two half circles chasing each other around a lens dot. */
private fun DrawScope.drawSwitch(color: Color, stroke: Stroke, s: Float) {
    val r = s * 0.36f
    val c = center
    val tl = Offset(c.x - r, c.y - r)
    val sz = Size(r * 2, r * 2)
    drawArc(color, 200f, 130f, false, tl, sz, style = stroke)
    drawArc(color, 20f, 130f, false, tl, sz, style = stroke)
    // arrow heads at the end of each arc (330° and 150°)
    fun head(angle: Float) {
        rotate(angle, c) {
            val p = Offset(c.x + r, c.y)
            val path = Path().apply {
                moveTo(p.x - s * 0.11f, p.y - s * 0.02f)
                lineTo(p.x, p.y + s * 0.11f)
                lineTo(p.x + s * 0.11f, p.y - s * 0.02f)
            }
            drawPath(path, color, style = stroke)
        }
    }
    head(330f)
    head(150f)
    drawCircle(color, radius = s * 0.08f, center = c)
}

private fun DrawScope.drawVideo(color: Color, stroke: Stroke, s: Float) {
    drawRoundRect(
        color, topLeft = Offset(s * 0.08f, s * 0.28f), size = Size(s * 0.56f, s * 0.44f),
        cornerRadius = CornerRadius(s * 0.08f), style = stroke,
    )
    val path = Path().apply {
        moveTo(s * 0.66f, s * 0.44f)
        lineTo(s * 0.92f, s * 0.30f)
        lineTo(s * 0.92f, s * 0.70f)
        lineTo(s * 0.66f, s * 0.56f)
        close()
    }
    drawPath(path, color, style = stroke)
}

private fun DrawScope.drawPhoto(color: Color, stroke: Stroke, s: Float) {
    val body = Path().apply {
        moveTo(s * 0.10f, s * 0.32f)
        lineTo(s * 0.32f, s * 0.32f)
        lineTo(s * 0.40f, s * 0.20f)
        lineTo(s * 0.60f, s * 0.20f)
        lineTo(s * 0.68f, s * 0.32f)
        lineTo(s * 0.90f, s * 0.32f)
        lineTo(s * 0.90f, s * 0.80f)
        lineTo(s * 0.10f, s * 0.80f)
        close()
    }
    drawPath(body, color, style = stroke)
    drawCircle(color, radius = s * 0.15f, center = Offset(s * 0.5f, s * 0.55f), style = stroke)
}

/** A small arc dial with a pointer: the settings button. */
private fun DrawScope.drawSettings(color: Color, stroke: Stroke, s: Float) {
    val r = s * 0.38f
    drawArc(color, 140f, 260f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = stroke)
    for (a in listOf(170f, 230f, 290f, 350f)) {
        rotate(a, center) {
            drawLine(color, Offset(center.x + r * 0.62f, center.y), Offset(center.x + r * 0.82f, center.y), stroke.width * 0.8f, StrokeCap.Round)
        }
    }
    rotate(250f, center) {
        drawLine(color, center, Offset(center.x + r * 0.55f, center.y), stroke.width, StrokeCap.Round)
    }
    drawCircle(color, radius = s * 0.06f, center = center)
}

private fun DrawScope.drawGrid(color: Color, stroke: Stroke, s: Float) {
    val a = s * 0.12f
    val b = s * 0.88f
    drawRoundRect(color, Offset(a, a), Size(b - a, b - a), CornerRadius(s * 0.06f), style = stroke)
    for (f in listOf(1f / 3f, 2f / 3f)) {
        val p = a + (b - a) * f
        drawLine(color, Offset(p, a), Offset(p, b), stroke.width * 0.7f)
        drawLine(color, Offset(a, p), Offset(b, p), stroke.width * 0.7f)
    }
}
