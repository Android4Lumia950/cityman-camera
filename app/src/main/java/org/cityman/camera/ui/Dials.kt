package org.cityman.camera.ui

import org.cityman.camera.camera.CameraCaps
import org.cityman.camera.camera.LiveValues
import org.cityman.camera.camera.ManualControls
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class DialKind(val title: String) {
    WB("WB"),
    FOCUS("FOCUS"),
    ISO("ISO"),
    SHUTTER("SHUTTER"),
    EV("EV"),
}

/** One position on an arc dial. [value] null is the automatic position. */
data class DialStop(val value: Double?, val label: String, val major: Boolean = true)

/** The stops of one dial. A [centered] dial (EV) highlights from its zero stop instead of from the auto end. */
data class DialSpec(val kind: DialKind, val stops: List<DialStop>, val centered: Boolean = false) {
    val zeroIndex: Int = if (centered) stops.indexOfFirst { it.value == 0.0 }.coerceAtLeast(0) else 0
}

object Dials {
    private val ISO_STOPS = intArrayOf(
        25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
    )
    private val SHUTTER_DENOMINATORS = intArrayOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2)
    private val FOCUS_DIOPTERS = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 15.0, 20.0)
    private val FOCUS_MAJOR = setOf(0.0, 0.5, 1.0, 2.0, 4.0, 10.0, 20.0)

    /** The dial for [kind] on this camera, or null when the camera cannot change it. */
    fun spec(kind: DialKind, caps: CameraCaps): DialSpec? = when (kind) {
        DialKind.WB -> if (caps.color != null && caps.manualPostProcessing) wbSpec() else null
        DialKind.FOCUS -> if (caps.hasFocus) focusSpec(caps) else null
        DialKind.ISO -> if (caps.manualSensor && caps.isoRange != null) isoSpec(caps) else null
        DialKind.SHUTTER -> if (caps.manualSensor && caps.exposureRange != null) shutterSpec(caps) else null
        DialKind.EV -> if (caps.evStep > 0f && caps.evRange.upper > caps.evRange.lower) evSpec(caps) else null
    }

    private fun wbSpec(): DialSpec {
        val stops = mutableListOf(DialStop(null, "A"))
        var k = 2400
        var i = 0
        while (k <= 8000) {
            stops += DialStop(k.toDouble(), "${k / 100 / 10.0}k".replace(".0k", "k"), major = i % 4 == 0)
            k += 200
            i++
        }
        return DialSpec(DialKind.WB, stops)
    }

    private fun focusSpec(caps: CameraCaps): DialSpec {
        val stops = mutableListOf(DialStop(null, "A"))
        for (d in FOCUS_DIOPTERS) {
            if (d > caps.minFocusDiopters + 1e-3) break
            stops += DialStop(d, formatDistance(d.toFloat()), major = d in FOCUS_MAJOR)
        }
        return DialSpec(DialKind.FOCUS, stops)
    }

    private fun isoSpec(caps: CameraCaps): DialSpec {
        val range = caps.isoRange!!
        val values = ISO_STOPS.filter { it >= range.lower && it <= isoUpper(caps) }
        val stops = mutableListOf(DialStop(null, "A"))
        val labelAll = values.size <= 12
        values.forEachIndexed { i, v -> stops += DialStop(v.toDouble(), v.toString(), major = labelAll || i % 3 == 0) }
        return DialSpec(DialKind.ISO, stops)
    }

    /** Highest ISO offered on the dial. */
    fun isoUpper(caps: CameraCaps): Int = caps.isoRange?.upper ?: 0

    private fun shutterSpec(caps: CameraCaps): DialSpec {
        val range = caps.exposureRange!!
        val values = mutableListOf<Long>()
        for (d in SHUTTER_DENOMINATORS) {
            val ns = 1_000_000_000L / d
            if (ns >= range.lower && ns <= range.upper) values += ns
        }
        for (s in doubleArrayOf(1.0, 2.0, 4.0, 8.0)) {
            val ns = (s * 1e9).roundToLong()
            if (ns <= range.upper) values += ns
        }
        // The longest exposure the sensor allows, if it is not already a stop.
        if (values.isEmpty() || range.upper > values.last() * 1.2) values += range.upper
        val stops = mutableListOf(DialStop(null, "A"))
        val every = if (values.size > 11) 2 else 1
        values.forEachIndexed { i, ns ->
            stops += DialStop(ns.toDouble(), formatShutter(ns), major = i % every == 0 || i == values.lastIndex)
        }
        return DialSpec(DialKind.SHUTTER, stops)
    }

    private fun evSpec(caps: CameraCaps): DialSpec {
        val perThird = (1.0 / 3.0 / caps.evStep).roundToInt().coerceAtLeast(1)
        val stops = mutableListOf<DialStop>()
        var units = (caps.evRange.lower / perThird) * perThird
        while (units <= caps.evRange.upper) {
            val ev = units * caps.evStep
            val whole = abs(ev - ev.roundToInt()) < 0.01
            stops += DialStop(units.toDouble(), if (whole) formatEv(ev) else "", major = whole)
            units += perThird
        }
        return DialSpec(DialKind.EV, stops, centered = true)
    }

    /** Stop that matches the current setting. */
    fun indexOf(spec: DialSpec, controls: ManualControls): Int {
        val v: Double? = when (spec.kind) {
            DialKind.WB -> controls.wbKelvin?.toDouble()
            DialKind.FOCUS -> controls.focusDiopters?.toDouble()
            DialKind.ISO -> controls.iso?.toDouble()
            DialKind.SHUTTER -> controls.exposureNs?.toDouble()
            DialKind.EV -> controls.evSteps.toDouble()
        }
        if (v == null) return 0
        var best = 0
        var bestErr = Double.MAX_VALUE
        spec.stops.forEachIndexed { i, s ->
            val sv = s.value ?: return@forEachIndexed
            val err = abs(sv - v)
            if (err < bestErr) {
                bestErr = err
                best = i
            }
        }
        return best
    }

    fun apply(kind: DialKind, stop: DialStop, c: ManualControls): ManualControls {
        val v = stop.value
        return when (kind) {
            DialKind.WB -> c.copy(wbKelvin = v?.toFloat())
            DialKind.FOCUS -> c.copy(focusDiopters = v?.toFloat())
            DialKind.ISO -> c.copy(iso = v?.roundToInt())
            DialKind.SHUTTER -> c.copy(exposureNs = v?.roundToLong())
            DialKind.EV -> c.copy(evSteps = v?.roundToInt() ?: 0)
        }
    }

    fun reset(kind: DialKind, c: ManualControls): ManualControls = when (kind) {
        DialKind.WB -> c.copy(wbKelvin = null)
        DialKind.FOCUS -> c.copy(focusDiopters = null)
        DialKind.ISO -> c.copy(iso = null)
        DialKind.SHUTTER -> c.copy(exposureNs = null)
        DialKind.EV -> c.copy(evSteps = 0)
    }

    /** EV has no effect once both ISO and shutter are manual. */
    fun evLocked(c: ManualControls) = c.iso != null && c.exposureNs != null

    /** Main text and the smaller live reading for a control chip. */
    fun chipText(kind: DialKind, caps: CameraCaps?, c: ManualControls, live: LiveValues): Pair<String, String?> {
        if (caps == null) return "—" to null
        if (spec(kind, caps) == null) return (if (kind == DialKind.FOCUS) "FIXED" else "—") to null
        return when (kind) {
            DialKind.WB -> c.wbKelvin?.let { "${it.roundToInt()}K" to null }
                ?: ("AUTO" to live.kelvin?.let { "${(it / 100).roundToInt() * 100}K" })
            DialKind.FOCUS -> c.focusDiopters?.let { formatDistance(it) to null }
                ?: ("AUTO" to if (live.focusDiopters > 0f || live.afState != 0) formatDistance(live.focusDiopters) else null)
            DialKind.ISO -> c.iso?.let { it.toString() to null }
                ?: ("AUTO" to live.iso.takeIf { it > 0 }?.toString())
            DialKind.SHUTTER -> c.exposureNs?.let { formatShutter(it) to null }
                ?: ("AUTO" to live.exposureNs.takeIf { it > 0 }?.let { formatShutter(it) })
            DialKind.EV -> if (evLocked(c)) "M" to null else formatEv(c.evSteps * caps.evStep) to null
        }
    }

    fun formatShutter(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.4) {
            val text = if (abs(s - s.roundToInt()) < 0.05) s.roundToInt().toString() else String.format(Locale.US, "%.1f", s)
            "$text\""
        } else {
            "1/" + (1.0 / s).roundToInt()
        }
    }

    fun formatDistance(diopters: Float): String {
        if (diopters <= 0.01f) return "∞"
        val m = 1f / diopters
        return if (m >= 1f) {
            if (abs(m - m.roundToInt()) < 0.05f) "${m.roundToInt()}m" else String.format(Locale.US, "%.1fm", m)
        } else {
            "${(m * 100).roundToInt()}cm"
        }
    }

    fun formatEv(ev: Float): String = when {
        abs(ev) < 0.01f -> "0"
        ev > 0 -> String.format(Locale.US, "+%.1f", ev).replace(".0", "")
        else -> String.format(Locale.US, "%.1f", ev).replace(".0", "")
    }
}
