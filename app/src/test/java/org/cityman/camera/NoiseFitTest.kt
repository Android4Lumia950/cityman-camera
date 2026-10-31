package org.cityman.camera

import org.cityman.camera.processing.MultiFrameProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class NoiseFitTest {
    /**
     * Builds the |difference| histograms the GPU would produce for a known noise model
     * (variance = a·x + b) and checks that the fit finds it again.
     */
    @Test
    fun recoversNoiseModel() {
        val a = 8e-4
        val b = 2e-5
        val counts = IntArray(16 * 64)
        val rnd = java.util.Random(1)
        for (bb in 0 until 16) {
            val x = ((bb + 0.5) / 16.0).let { it * it }
            val sigma = sqrt(a * x + b)
            repeat(20_000) {
                // difference of two noisy samples: sigma·√2
                val d = kotlin.math.abs(rnd.nextGaussian() * sigma * sqrt(2.0))
                val db = minOf((sqrt(d / 0.12) * 64).toInt(), 63)
                counts[bb * 64 + db]++
            }
        }
        val fit = MultiFrameProcessor.fitNoise(counts, 342)
        assertEquals(a, fit[0].toDouble(), a * 0.25)
        assertEquals(b, fit[1].toDouble(), b * 0.6)
    }

    @Test
    fun fallsBackWithoutData() {
        val fit = MultiFrameProcessor.fitNoise(IntArray(16 * 64), 100)
        assertEquals(1.2e-4, fit[0].toDouble(), 1e-6)
    }
}
