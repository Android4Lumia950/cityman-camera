package org.cityman.camera

import org.cityman.camera.ui.Dials
import org.junit.Assert.assertEquals
import org.junit.Test

class DialsTest {
    @Test
    fun shutterLabels() {
        assertEquals("1/125", Dials.formatShutter(8_000_000L))
        assertEquals("1/30", Dials.formatShutter(33_333_333L))
        assertEquals("1\"", Dials.formatShutter(1_000_000_000L))
        assertEquals("0.8\"", Dials.formatShutter(828_982_077L))
    }

    @Test
    fun distanceLabels() {
        assertEquals("∞", Dials.formatDistance(0f))
        assertEquals("1m", Dials.formatDistance(1f))
        assertEquals("2m", Dials.formatDistance(0.5f))
        assertEquals("25cm", Dials.formatDistance(4f))
        assertEquals("10cm", Dials.formatDistance(10f))
    }

    @Test
    fun evLabels() {
        assertEquals("0", Dials.formatEv(0f))
        assertEquals("+1", Dials.formatEv(1f))
        assertEquals("-0.7", Dials.formatEv(-4 / 6f))
        assertEquals("+2", Dials.formatEv(12 / 6f))
    }
}
