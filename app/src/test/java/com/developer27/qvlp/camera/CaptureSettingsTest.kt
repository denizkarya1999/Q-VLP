package com.developer27.qvlp.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun aRollingShutterRateIsOnePeriodOfExposure() {
        assertEquals(100_000L, CaptureSettings.exposureNanos(10_000))
        assertEquals(166_666L, CaptureSettings.exposureNanos(6_000))
        assertEquals(10_000, CaptureSettings.shutterRateHz(100_000L))
        // Every listed rate survives the round trip, so the saved exposure selects its own entry.
        for (rate in CaptureSettings.SHUTTER_RATES_HZ) {
            assertEquals(rate, CaptureSettings.shutterRateHz(CaptureSettings.exposureNanos(rate)))
        }
    }

    @Test
    fun onlyRatesTheCameraCanExposeAreOffered() {
        // 20 µs shortest exposure, 30 fps frame duration longest.
        val rates = CaptureSettings.shutterRates(20_000L, 33_333_334L)
        assertEquals(50, rates.first())
        assertEquals(50_000, rates.last())
        assertTrue(rates.containsAll(listOf(6_000, 10_000)))
        assertEquals(listOf(5, 10, 15), CaptureSettings.SHUTTER_RATES_HZ - rates.toSet())
        assertEquals(listOf(8_000, 10_000), CaptureSettings.shutterRates(100_000L, 125_000L))
    }

    @Test
    fun theDefaultsAreSixThousandHertzAndIsoOneHundred() {
        assertEquals(6_000, CaptureSettings.shutterRateHz(CaptureSettings.DEFAULT_EXPOSURE_NS))
        // The default is exactly the exposure of the listed 6000 Hz rate, so Settings shows it selected.
        assertEquals(CaptureSettings.DEFAULT_EXPOSURE_NS, CaptureSettings.exposureNanos(6_000))
        assertTrue(6_000 in CaptureSettings.SHUTTER_RATES_HZ)
        assertEquals(100, CaptureSettings.DEFAULT_ISO_RATE)
    }
}
