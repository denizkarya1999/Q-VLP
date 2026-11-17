package com.developer27.qvlp.camera

import org.junit.Assert.*
import org.junit.Test

class ResearchCapturePolicyTest {
    private fun issue(zoom: Float = 1f, fixedResolution: Boolean = true,
                      exposureNs: Long = 100_000L, fixedIso: Boolean = true, fps: Int = 30,
                      fixedFocus: Boolean = true, fixedWhiteBalance: Boolean = true) =
        ResearchCapturePolicy.settingsIssue(zoom, fixedResolution, exposureNs, fixedIso, fps, fixedFocus, fixedWhiteBalance)

    @Test fun acceptsResearchSettingsAtTheLimits() {
        assertNull(issue())
        assertNull(issue(exposureNs = 50_000L, fps = 60))
    }

    @Test fun acceptsEveryRollingShutterRateTheOperatorCanChoose() {
        // 500 Hz is 2 ms and 50 Hz is 20 ms: slower than the default, and still a valid choice.
        for (rate in CaptureSettings.SHUTTER_RATES_HZ) assertNull(issue(exposureNs = CaptureSettings.exposureNanos(rate)))
        val check = ResearchCapturePolicy.Confirmation()
        val slow = valid.copy(exposureNs = 1_991_924L)
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(slow))
        assertNull(check.observe(slow))
    }

    @Test fun acceptsEveryFpsRateTheOperatorCanChoose() {
        // The FPS rate is a setting: 1 fps and 29 fps are as valid as 30 and 60.
        for (fps in 1..60) assertNull(issue(fps = fps))
        // The camera rounds a frame up to whole sensor lines. A chosen rate below 30 fps is confirmed with
        // up to a tenth of a millisecond of that rounding, and rejected when the camera is really slower.
        for (fps in listOf(1, 7, 15, 24, 29)) {
            val period = CaptureSettings.frameDurationNanos(fps)
            val check = ResearchCapturePolicy.Confirmation()
            val rounded = valid.copy(frameDurationNs = period + 34_000L)
            assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(rounded, fps))
            assertNull(check.observe(rounded, fps))
            assertTrue(ResearchCapturePolicy.usesFrameTimingTolerance(rounded.frameDurationNs!!, fps))
            assertFalse(ResearchCapturePolicy.usesFrameTimingTolerance(period, fps))
            val slower = valid.copy(frameDurationNs = ResearchCapturePolicy.maxConfirmedFrameDurationNs(fps) + 1)
            assertEquals(ResearchCapturePolicy.Issue.REPORTED_FRAME_RATE, check.observe(slower, fps))
        }
        // A camera asked for 15 fps that still runs at 30 is not slower than asked.
        val fast = ResearchCapturePolicy.Confirmation()
        fast.observe(valid, 15)
        assertNull(fast.observe(valid, 15))
        // A slow rate is given at least a tenth of a millisecond, and 0.05% once that is more: at 1 fps, half a millisecond.
        assertEquals(1_000_500_000L, ResearchCapturePolicy.maxConfirmedFrameDurationNs(1))
        assertEquals(66_666_667L + 100_000L, ResearchCapturePolicy.maxConfirmedFrameDurationNs(15))
        // The 30 fps rule is unchanged.
        assertEquals(ResearchCapturePolicy.MAX_CONFIRMED_FRAME_DURATION_NS, ResearchCapturePolicy.maxConfirmedFrameDurationNs(30))
        assertEquals(16_666_667L + 8_333L, ResearchCapturePolicy.maxConfirmedFrameDurationNs(60))
    }

    @Test fun rejectsEachNonResearchSetting() {
        assertEquals(ResearchCapturePolicy.Issue.ZOOM, issue(zoom = 1.1f))
        assertEquals(ResearchCapturePolicy.Issue.ZOOM, issue(zoom = Float.NaN))
        assertEquals(ResearchCapturePolicy.Issue.RESOLUTION, issue(fixedResolution = false))
        assertEquals(ResearchCapturePolicy.Issue.EXPOSURE, issue(exposureNs = 0L))
        assertEquals(ResearchCapturePolicy.Issue.ISO, issue(fixedIso = false))
        assertEquals(ResearchCapturePolicy.Issue.FRAME_RATE, issue(fps = 0))
        assertEquals(ResearchCapturePolicy.Issue.FOCUS, issue(fixedFocus = false))
        assertEquals(ResearchCapturePolicy.Issue.WHITE_BALANCE, issue(fixedWhiteBalance = false))
    }

    @Test fun acceptsFourByThreeOnTheSlightlyWiderPixel8aFrontSensor() {
        val front = 4208.0 / 3072
        assertTrue(ResearchCapturePolicy.coversSensor(4208, 3072, front))
        assertTrue(ResearchCapturePolicy.coversSensor(1280, 960, front))
        assertFalse(ResearchCapturePolicy.coversSensor(1920, 1080, front))
        assertFalse(ResearchCapturePolicy.coversSensor(720, 480, front))
        assertFalse(ResearchCapturePolicy.coversSensor(1080, 1080, front))
    }

    @Test fun keepsTheStrictRuleWhenAnOutputWouldCropSensorRows() {
        val rear = 4624.0 / 3472
        assertTrue(ResearchCapturePolicy.coversSensor(1280, 960, rear))
        assertFalse(ResearchCapturePolicy.coversSensor(1920, 1080, rear))
        // A 4:3 output of a narrower sensor would lose rows, so only the small tolerance applies.
        assertFalse(ResearchCapturePolicy.coversSensor(1280, 960, 1.30))
        assertTrue(ResearchCapturePolicy.coversSensor(1280, 960, 1.32))
    }

    private val valid = ResearchCapturePolicy.Reading(99_900L, 50, 33_333_334L, true)

    @Test fun waitsForTwoActualReadingsRatherThanAssumingRequestedValues() {
        val check = ResearchCapturePolicy.Confirmation()
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(valid))
        assertNull(check.observe(valid))
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(valid.copy(iso = 51)))
        assertNull(check.observe(valid.copy(iso = 51)))
        check.reset()
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(valid.copy(iso = 51)))
    }

    @Test fun acceptsPixel8aNominalThirtyFpsOnlyAfterStableReadings() {
        val check = ResearchCapturePolicy.Confirmation()
        val pixel = valid.copy(exposureNs = 98_504L, frameDurationNs = 33_340_371L)
        assertTrue(ResearchCapturePolicy.usesFrameTimingTolerance(pixel.frameDurationNs!!))
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(pixel))
        assertNull(check.observe(pixel))
        // Accepting nominal timing must not round or overwrite actual measurements.
        assertEquals(33_340_371L, pixel.frameDurationNs)
        assertEquals(98_504L, pixel.exposureNs)
    }

    @Test fun timingToleranceIsBoundedAndDoesNotAcceptSlowerModes() {
        val check = ResearchCapturePolicy.Confirmation()
        assertEquals(33_350_000L, ResearchCapturePolicy.MAX_CONFIRMED_FRAME_DURATION_NS)
        val boundary = valid.copy(frameDurationNs = 33_350_000L)
        check.observe(boundary)
        assertNull(check.observe(boundary))
        for (duration in listOf(33_350_001L, 33_366_700L, 34_482_759L, 41_666_667L, 66_666_667L)) {
            repeat(3) {
                assertEquals(ResearchCapturePolicy.Issue.REPORTED_FRAME_RATE,
                    check.observe(valid.copy(frameDurationNs = duration)))
            }
            assertFalse(ResearchCapturePolicy.usesFrameTimingTolerance(duration))
        }
        assertFalse(ResearchCapturePolicy.usesFrameTimingTolerance(33_333_334L))
        assertFalse(ResearchCapturePolicy.usesFrameTimingTolerance(16_666_667L))
    }

    @Test fun nominalTimingStillRejectsGeometryAndMissingMeasurements() {
        val check = ResearchCapturePolicy.Confirmation()
        val pixel = valid.copy(frameDurationNs = 33_340_371L)
        assertEquals(ResearchCapturePolicy.Issue.REPORTED_GEOMETRY, check.observe(pixel.copy(geometryMatches = false)))
        assertEquals(ResearchCapturePolicy.Issue.MISSING_MEASUREMENTS, check.observe(pixel.copy(iso = null)))
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(pixel))
        assertNull(check.observe(pixel))
    }

    @Test fun missingMetadataDoesNotBecomeReady() {
        val check = ResearchCapturePolicy.Confirmation()
        for (reading in listOf(valid.copy(exposureNs = null), valid.copy(iso = null), valid.copy(frameDurationNs = null))) {
            repeat(3) { assertEquals(ResearchCapturePolicy.Issue.MISSING_MEASUREMENTS, check.observe(reading)) }
        }
    }

    @Test fun rejectsSlowCadenceAndStaleZoomResults() {
        val check = ResearchCapturePolicy.Confirmation()
        check.observe(valid); check.observe(valid)
        assertEquals(ResearchCapturePolicy.Issue.REPORTED_FRAME_RATE, check.observe(valid.copy(frameDurationNs = ResearchCapturePolicy.MAX_CONFIRMED_FRAME_DURATION_NS + 1)))
        assertEquals(ResearchCapturePolicy.Issue.REPORTED_GEOMETRY, check.observe(valid.copy(geometryMatches = false)))
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(valid))
        assertNull(check.observe(valid))
    }
}
