package org.cityman.camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Manual white balance from the sensor's own calibration (the DNG model that Camera2 exposes):
 * a colour temperature becomes a CIE xy white point on the Planckian locus, the colour matrices
 * interpolated for that temperature turn it into the camera's neutral, and the forward matrices
 * give the camera-to-sRGB transform used after the gains.
 */
class ColorCalibration private constructor(
    private val cm1: FloatArray,
    private val cm2: FloatArray,
    private val fm1: FloatArray,
    private val fm2: FloatArray,
    private val t1: Float,
    private val t2: Float,
) {
    data class Correction(val gains: RggbChannelVector, val transform: ColorSpaceTransform)

    /** Gains and transform for a scene lit at [kelvin]. */
    fun forKelvin(kelvin: Float): Correction {
        val k = kelvin.coerceIn(MIN_K, MAX_K)
        val (x, y) = planckianXy(k)
        val xyz = floatArrayOf(x / y, 1f, (1f - x - y) / y)
        val w = weight(k)
        val cm = lerp(cm1, cm2, w)
        val neutral = mul(cm, xyz)
        val g = neutral[1]
        val gains = RggbChannelVector(
            (g / neutral[0]).coerceIn(0.25f, 8f),
            1f,
            1f,
            (g / neutral[2]).coerceIn(0.25f, 8f),
        )
        val fm = lerp(fm1, fm2, w)
        val transform = mul3(SRGB_FROM_XYZ_D50, fm)
        return Correction(gains, toTransform(transform))
    }

    /** Colour temperature whose gains have the same red/blue balance as [gains] (for the AUTO readout). */
    fun kelvinFor(gains: RggbChannelVector): Float? {
        val g = (gains.greenEven + gains.greenOdd) / 2f
        if (g <= 0f || gains.red <= 0f || gains.blue <= 0f) return null
        val target = ln(gains.red / gains.blue)
        var best = MIN_K
        var bestErr = Float.MAX_VALUE
        var k = MIN_K
        while (k <= MAX_K) {
            val c = forKelvin(k).gains
            val err = abs(ln(c.red / c.blue) - target)
            if (err < bestErr) {
                bestErr = err
                best = k
            }
            k += 50f
        }
        return best
    }

    private fun weight(k: Float): Float {
        if (t1 == t2) return 1f
        val inv = 1f / k
        val w = (inv - 1f / t2) / (1f / t1 - 1f / t2)
        return w.coerceIn(0f, 1f)
    }

    companion object {
        const val MIN_K = 2000f
        const val MAX_K = 10000f

        // XYZ (D50, Bradford adapted) to linear sRGB.
        private val SRGB_FROM_XYZ_D50 = floatArrayOf(
            3.1338561f, -1.6168667f, -0.4906146f,
            -0.9787684f, 1.9161415f, 0.0334540f,
            0.0719453f, -0.2289914f, 1.4052427f,
        )

        fun from(chars: CameraCharacteristics): ColorCalibration? {
            val cm1 = chars[CameraCharacteristics.SENSOR_COLOR_TRANSFORM1]?.toArray() ?: return null
            val cm2 = chars[CameraCharacteristics.SENSOR_COLOR_TRANSFORM2]?.toArray() ?: cm1
            val fm1 = chars[CameraCharacteristics.SENSOR_FORWARD_MATRIX1]?.toArray() ?: return null
            val fm2 = chars[CameraCharacteristics.SENSOR_FORWARD_MATRIX2]?.toArray() ?: fm1
            val cc1 = chars[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1]?.toArray()
            val cc2 = chars[CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2]?.toArray() ?: cc1
            val t1 = illuminantKelvin(chars[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1])
            val t2 = illuminantKelvin(chars[CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2]?.toInt()) ?: t1
            if (t1 == null || t2 == null) return null
            return ColorCalibration(
                if (cc1 != null) mul3(cc1, cm1) else cm1,
                if (cc2 != null) mul3(cc2, cm2) else cm2,
                fm1, fm2, t1, t2,
            )
        }

        /** Kelvin of a Camera2 / EXIF reference illuminant. */
        fun illuminantKelvin(code: Int?): Float? = when (code) {
            null -> null
            1, 4, 9 -> 5500f // daylight, flash, fine weather
            2, 14 -> 4150f // fluorescent, cool white fluorescent
            3, 17 -> 2856f // tungsten, standard A
            10 -> 6500f // cloudy
            11 -> 7500f // shade
            12 -> 6430f // daylight fluorescent
            13 -> 5000f // day white fluorescent
            15 -> 3450f // white fluorescent
            18 -> 4874f // standard B
            19 -> 6774f // standard C
            20 -> 5503f // D55
            21 -> 6504f // D65
            22 -> 7504f // D75
            23 -> 5003f // D50
            24 -> 3200f // ISO studio tungsten
            else -> null
        }

        /** Planckian locus, Kim et al. cubic spline (valid 1667 K to 25000 K). */
        fun planckianXy(k: Float): Pair<Float, Float> {
            val t = k.toDouble()
            val x = if (t <= 4000.0) {
                -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
            } else {
                -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
            }
            val y = when {
                t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
            }
            return x.toFloat() to y.toFloat()
        }

        private fun ColorSpaceTransform.toArray(): FloatArray {
            val out = FloatArray(9)
            for (r in 0 until 3) for (c in 0 until 3) out[r * 3 + c] = getElement(c, r).toFloat()
            return out
        }

        private fun toTransform(m: FloatArray): ColorSpaceTransform {
            val den = 1024
            val ints = IntArray(18)
            for (i in 0 until 9) {
                ints[i * 2] = (m[i] * den).roundToInt()
                ints[i * 2 + 1] = den
            }
            return ColorSpaceTransform(ints)
        }

        private fun lerp(a: FloatArray, b: FloatArray, w: Float) = FloatArray(9) { a[it] * w + b[it] * (1f - w) }

        private fun mul(m: FloatArray, v: FloatArray) = FloatArray(3) { r ->
            m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2]
        }

        private fun mul3(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
            val r = i / 3
            val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }
    }
}
