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
    fun theFpsRateIsAnyWholeNumberFromTheSlowestToTheFastestTheCameraLists() {
        // The Pixel 8a at 1280 x 960.
        val range = CaptureSettings.fpsRange(listOf(1, 5, 10, 15, 24, 25, 30))
        assertEquals(1..30, range)
        assertEquals(1, CaptureSettings.parseFps("1", range))
        assertEquals(7, CaptureSettings.parseFps(" 7 ", range))
        assertEquals(30, CaptureSettings.parseFps("30", range))
        for (raw in listOf("0", "31", "-5", "12.5", "fast", "", null)) assertNull(CaptureSettings.parseFps(raw, range))
        // A camera that lists 60 fps offers it; one that cannot go below 5 fps does not offer less.
        assertEquals(5..60, CaptureSettings.fpsRange(listOf(60, 30, 5)))
        assertEquals(30..30, CaptureSettings.fpsRange(emptyList()))
    }

    @Test
    fun aCameraStartsAtThirtyFps() {
        assertEquals(30, CaptureSettings.DEFAULT_FPS_RATE)
        assertEquals(30, CaptureSettings.defaultFps(listOf(1, 5, 10, 15, 24, 25, 30)))
        assertEquals(30, CaptureSettings.defaultFps(listOf(5, 30, 60)))
        // A camera that cannot reach 30 fps starts at the fastest rate it has, and one that cannot go that slowly at its slowest.
        assertEquals(24, CaptureSettings.defaultFps(listOf(1, 15, 24)))
        assertEquals(60, CaptureSettings.defaultFps(listOf(60, 120)))
        assertEquals(30, CaptureSettings.defaultFps(emptyList()))
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
