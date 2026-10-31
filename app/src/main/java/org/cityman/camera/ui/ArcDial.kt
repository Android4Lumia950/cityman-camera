package org.cityman.camera.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A half ring around [center] (the shutter button), opening towards the viewfinder: to the left in
 * landscape, upwards in portrait. The first stop (automatic) sits at the [startAngle] end; dragging
 * along the ring moves through the stops, snapping to each one. Angles are in degrees, clockwise
 * from three o'clock; [sweep] is +180 or -180.
 */
@Composable
fun ArcDial(
    spec: DialSpec,
    index: Int,
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweep: Float,
    onIndex: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val measurer = rememberTextMeasurer()
    val currentIndex by rememberUpdatedState(index)
    val currentSpec by rememberUpdatedState(spec)
    val onIndexState by rememberUpdatedState(onIndex)
    val onDismissState by rememberUpdatedState(onDismiss)
    val labelStyle = remember { TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Normal) }
    val labelActive = remember(Accent) { TextStyle(color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    val titleStyle = remember { TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, letterSpacing = 2.sp) }
    val valueStyle = remember { TextStyle(color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light) }

    Canvas(
        modifier.pointerInput(center, radius, startAngle, sweep) {
            val band = 64.dp.toPx()
            // Degrees travelled from the start end along the sweep direction, 0..360.
            fun along(p: Offset): Double {
                val deg = Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble()))
                val dir = if (sweep >= 0f) 1.0 else -1.0
                return (((deg - startAngle) * dir) % 360.0 + 360.0) % 360.0
            }
            fun indexAt(p: Offset): Int {
                val n = currentSpec.stops.size
                val a = along(p)
                val t = when {
                    a <= 180.0 -> a / 180.0
                    a > 270.0 -> 0.0
                    else -> 1.0
                }
                return (t * (n - 1)).roundToInt().coerceIn(0, n - 1)
            }

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val d = hypot(down.position.x - center.x, down.position.y - center.y)
                val a = along(down.position)
                val onRing = d > radius - band && d < radius + band * 0.8f && (a <= 195.0 || a >= 345.0)
                if (!onRing) {
                    // A tap away from the ring closes the dial.
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if ((ch.position - down.position).getDistance() > 24f) moved = true
                        if (!ch.pressed) break
                    }
                    if (!moved) onDismissState()
                    return@awaitEachGesture
                }
                down.consume()
                fun set(p: Offset) {
                    val i = indexAt(p)
                    if (i != currentIndex) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onIndexState(i)
                    }
                }
                set(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (ch.positionChange() != Offset.Zero) {
                        ch.consume()
                        set(ch.position)
                    }
                }
            }
        },
    ) {
        val n = spec.stops.size
        fun angleOf(i: Int): Float = startAngle + sweep * i / (n - 1).coerceAtLeast(1)
        val arcFrom = minOf(startAngle, startAngle + sweep)
        fun point(angleDeg: Float, r: Float): Offset {
            val a = angleDeg * PI.toFloat() / 180f
            return Offset(center.x + r * cos(a), center.y + r * sin(a))
        }

        val band = 64.dp.toPx()
        val ringTopLeft = Offset(center.x - radius, center.y - radius)
        val ringSize = Size(radius * 2, radius * 2)

        // Translucent band under the ring, then the track.
        drawArc(
            color = Color.Black.copy(alpha = 0.5f), startAngle = arcFrom - 4f, sweepAngle = 188f, useCenter = false,
            topLeft = Offset(center.x - (radius - band * 0.25f), center.y - (radius - band * 0.25f)),
            size = Size((radius - band * 0.25f) * 2, (radius - band * 0.25f) * 2),
            style = Stroke(width = band * 1.3f),
        )
        drawArc(
            color = Color.White.copy(alpha = 0.35f), startAngle = arcFrom, sweepAngle = 180f, useCenter = false,
            topLeft = ringTopLeft, size = ringSize, style = Stroke(width = 2.dp.toPx()),
        )

        // Highlighted part: from the auto end (or zero for EV) to the current stop.
        val from = angleOf(spec.zeroIndex)
        val to = angleOf(index)
        if (from != to) {
            drawArc(
                color = Accent, startAngle = minOf(from, to), sweepAngle = abs(from - to), useCenter = false,
                topLeft = ringTopLeft, size = ringSize, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // Ticks and labels.
        val tickMajor = 12.dp.toPx()
        val tickMinor = 6.dp.toPx()
        val labelR = radius - 30.dp.toPx()
        spec.stops.forEachIndexed { i, stop ->
            val a = angleOf(i)
            val len = if (stop.major) tickMajor else tickMinor
            val selected = i == index
            drawLine(
                color = if (selected) Accent else Color.White.copy(alpha = if (stop.major) 0.9f else 0.5f),
                start = point(a, radius + 2.dp.toPx()), end = point(a, radius + 2.dp.toPx() + len),
                strokeWidth = (if (stop.major) 2.dp else 1.5.dp).toPx(),
            )
            if ((stop.major || selected) && stop.label.isNotEmpty()) {
                drawLabel(measurer.measure(stop.label, if (selected) labelActive else labelStyle), point(a, labelR))
            }
        }

        // Knob.
        val knob = point(to, radius)
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = 12.dp.toPx(), center = knob)
        drawCircle(Accent, radius = 9.dp.toPx(), center = knob)

        // Title and the current value, inside the ring.
        val stop = spec.stops[index.coerceIn(0, n - 1)]
        val valueText = when {
            stop.value == null -> "AUTO"
            spec.kind == DialKind.EV -> stop.label.ifEmpty { evLabel(spec, index) }
            else -> stop.label
        }
        val textCenter = point(startAngle + sweep / 2f, radius * 0.56f)
        val title = measurer.measure(spec.kind.title, titleStyle)
        val value = measurer.measure(valueText, valueStyle)
        drawLabel(title, textCenter + Offset(0f, -value.size.height / 2f - 6.dp.toPx()))
        drawLabel(value, textCenter + Offset(0f, title.size.height / 2f))
    }
}

/** EV stops between whole values carry no label; show thirds in the readout. */
private fun evLabel(spec: DialSpec, index: Int): String {
    val thirds = index - spec.zeroIndex
    val whole = thirds / 3
    val rest = abs(thirds % 3)
    val sign = if (thirds < 0) "-" else "+"
    return sign + (if (abs(whole) > 0) "${abs(whole)} " else "") + "$rest/3"
}

private fun DrawScope.drawLabel(layout: androidx.compose.ui.text.TextLayoutResult, center: Offset) {
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}
