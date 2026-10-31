package org.cityman.camera

import org.cityman.camera.camera.ColorCalibration
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorCalibrationTest {
    /** Planckian locus chromaticities (CIE 1931) at known temperatures. */
    @Test
    fun planckianLocus() {
        val (xA, yA) = ColorCalibration.planckianXy(2856f) // illuminant A
        assertEquals(0.4476, xA.toDouble(), 0.002)
        assertEquals(0.4074, yA.toDouble(), 0.002)
        val (x65, y65) = ColorCalibration.planckianXy(6500f) // black body near D65
        assertEquals(0.3135, x65.toDouble(), 0.002)
        assertEquals(0.3236, y65.toDouble(), 0.002)
    }

    @Test
    fun illuminants() {
        assertEquals(6504f, ColorCalibration.illuminantKelvin(21)!!, 0.5f) // D65
        assertEquals(2856f, ColorCalibration.illuminantKelvin(17)!!, 0.5f) // standard A
        assertEquals(null, ColorCalibration.illuminantKelvin(99))
    }
}
