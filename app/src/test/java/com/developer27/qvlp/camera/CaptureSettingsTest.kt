package com.developer27.qvlp.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureSettingsTest {
    @Test
    fun exposureTimeUsesConfiguredFrequency() {
        assertEquals(166_666L, CaptureSettings.exposureTimeNanos("6000"))
    }

    @Test
    fun exposureTimeFallsBackToDefaultForInvalidText() {
        assertEquals(166_666L, CaptureSettings.exposureTimeNanos("invalid"))
        assertNull(CaptureSettings.exposureTimeNanos("0"))
    }

    @Test
    fun isoInputIsValidatedAndClamped() {
        assertNull(CaptureSettings.normalizeIso("not a number"))
        assertEquals(50, CaptureSettings.normalizeIso("10"))
        assertEquals(1100, CaptureSettings.normalizeIso(" 1100 "))
        assertEquals(25600, CaptureSettings.normalizeIso("50000"))
    }
}
