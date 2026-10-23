package com.developer27.qvlp.camera

import org.junit.Assert.*
import org.junit.Test

class ResearchCapturePolicyTest {
    private fun issue(rear: Boolean = true, zoom: Float = 1f, fixedResolution: Boolean = true,
                      exposureNs: Long = 100_000L, fixedIso: Boolean = true, fps: Int = 30,
                      fixedFocus: Boolean = true, fixedWhiteBalance: Boolean = true) =
        ResearchCapturePolicy.settingsIssue(rear, zoom, fixedResolution, exposureNs, fixedIso, fps, fixedFocus, fixedWhiteBalance)

    @Test fun acceptsResearchSettingsAtTheLimits() {
        assertNull(issue())
        assertNull(issue(exposureNs = 50_000L, fps = 60))
    }

    @Test fun rejectsEachNonResearchSetting() {
        assertEquals(ResearchCapturePolicy.Issue.REAR_CAMERA, issue(rear = false))
        assertEquals(ResearchCapturePolicy.Issue.ZOOM, issue(zoom = 1.1f))
        assertEquals(ResearchCapturePolicy.Issue.ZOOM, issue(zoom = Float.NaN))
        assertEquals(ResearchCapturePolicy.Issue.RESOLUTION, issue(fixedResolution = false))
        assertEquals(ResearchCapturePolicy.Issue.EXPOSURE, issue(exposureNs = 100_001L))
        assertEquals(ResearchCapturePolicy.Issue.EXPOSURE, issue(exposureNs = 0L))
        assertEquals(ResearchCapturePolicy.Issue.ISO, issue(fixedIso = false))
        assertEquals(ResearchCapturePolicy.Issue.FRAME_RATE, issue(fps = 29))
        assertEquals(ResearchCapturePolicy.Issue.FOCUS, issue(fixedFocus = false))
        assertEquals(ResearchCapturePolicy.Issue.WHITE_BALANCE, issue(fixedWhiteBalance = false))
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

    @Test fun missingMetadataDoesNotBecomeReady() {
        val check = ResearchCapturePolicy.Confirmation()
        for (reading in listOf(valid.copy(exposureNs = null), valid.copy(iso = null), valid.copy(frameDurationNs = null))) {
            repeat(3) { assertEquals(ResearchCapturePolicy.Issue.MISSING_MEASUREMENTS, check.observe(reading)) }
        }
    }

    @Test fun rejectsClampedExposureSlowCadenceAndStaleZoomResults() {
        val check = ResearchCapturePolicy.Confirmation()
        check.observe(valid); check.observe(valid)
        assertEquals(ResearchCapturePolicy.Issue.REPORTED_EXPOSURE, check.observe(valid.copy(exposureNs = 120_000L)))
        assertEquals(ResearchCapturePolicy.Issue.REPORTED_FRAME_RATE, check.observe(valid.copy(frameDurationNs = 33_333_335L)))
        assertEquals(ResearchCapturePolicy.Issue.REPORTED_GEOMETRY, check.observe(valid.copy(geometryMatches = false)))
        assertEquals(ResearchCapturePolicy.Issue.CONFIRMING, check.observe(valid))
        assertNull(check.observe(valid))
    }
}
