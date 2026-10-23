package com.developer27.qvlp.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureLengthTest {
    @Test fun validatesInputWithoutSilentlyChangingTheRequestedLength() {
        assertEquals(CaptureLength(CaptureLengthMode.FRAMES, 90), CaptureLength.parse(CaptureLengthMode.FRAMES, " 90 "))
        for (raw in listOf(null, "", "0", "-1", "1.5", "1001", "99999999999999999999", "NaN")) {
            assertNull(raw, CaptureLength.parse(CaptureLengthMode.FRAMES, raw))
        }
        assertNotNull(CaptureLength.parse(CaptureLengthMode.FRAMES, "1"))
        assertNotNull(CaptureLength.parse(CaptureLengthMode.FRAMES, "1000"))
        assertNotNull(CaptureLength.parse(CaptureLengthMode.DURATION, "60"))
        assertNull(CaptureLength.parse(CaptureLengthMode.DURATION, "61"))
        assertEquals(30, CaptureLength().frameCount)
    }

    @Test fun longerFrameCaptureStopsExactlyAtItsLimitDespiteHardwareFrameNumberGaps() {
        val window = CaptureWindow(CaptureLength(CaptureLengthMode.FRAMES, 90))
        repeat(90) { index ->
            assertEquals(index, window.start(100L + index * 2, 500_000_000L + index * 40_000_000L))
            assertEquals(index == 89, window.complete)
        }
        // In-flight requests after stopRepeating must not leak into the saved data.
        assertNull(window.start(500L, 9_000_000_000L))
        assertNull(window.frameId(500L))
        // Metadata may arrive out of order after the stop request.
        assertEquals(89, window.frameId(278L))
        assertEquals(0, window.frameId(100L))
    }

    @Test fun oneFrameCaptureDoesNotNeedASecondFrameToStop() {
        val window = CaptureWindow(CaptureLength(CaptureLengthMode.FRAMES, 1))
        assertEquals(0, window.start(81, 9_000_000_000L))
        assertTrue(window.complete)
        assertNull(window.start(82, 9_033_333_334L))
    }

    @Test fun repeatedStartCallbackDoesNotCountTheSameExposureTwice() {
        val window = CaptureWindow(CaptureLength(CaptureLengthMode.FRAMES, 2))
        assertEquals(0, window.start(10, 200L))
        assertEquals(0, window.start(10, 200L))
        assertFalse(window.complete)
        assertEquals(1, window.start(11, 300L))
        assertTrue(window.complete)
    }

    @Test fun timedCaptureUsesSensorTimeEvenWhenFrameRateDrops() {
        val window = CaptureWindow(CaptureLength(CaptureLengthMode.DURATION, 2))
        val first = 123_000_000_000L
        listOf(0L, 33_333_334L, 700_000_000L, 1_950_000_000L).forEachIndexed { index, offset ->
            assertEquals(index, window.start(100L + index, first + offset))
            assertFalse(window.complete)
        }
        // Four frames, not 60: the two-second interval is independent of target FPS.
        assertNull(window.start(104, first + 2_000_000_000L))
        assertTrue(window.complete)
        assertNull(window.start(105, first + 2_033_333_334L))
        assertEquals(first, window.firstTimestampNs)
    }

    @Test fun aGapAcrossTheDurationBoundaryDoesNotExtendTheSavedInterval() {
        val window = CaptureWindow(CaptureLength(CaptureLengthMode.DURATION, 1))
        assertEquals(0, window.start(1, 0))
        assertNull(window.start(2, 1_500_000_000L))
        assertTrue(window.complete)
    }

    @Test fun storageEstimatesRoundUpAndFrameModeIsIndependentOfCadence() {
        val timed = CaptureLength(CaptureLengthMode.DURATION, 5)
        assertEquals(150L, timed.estimatedFrames(33_333_334L))
        assertEquals(300L, timed.estimatedFrames(16_666_667L))
        assertEquals(13L, timed.estimatedFrames(400_000_000L))
        val frames = CaptureLength(CaptureLengthMode.FRAMES, 90)
        assertEquals(90L, frames.estimatedFrames(33_333_334L))
        assertEquals(90L, frames.estimatedFrames(1_000_000_000L))
    }

    @Test fun timeoutAllowsLongCapturesAndSlowSensorProfiles() {
        assertEquals(12_000L, CaptureLength().timeoutMillis(33_333_334L))
        assertEquals(185_000L, CaptureLength(CaptureLengthMode.DURATION, 60).timeoutMillis(33_333_334L))
        assertEquals(3_005_000L, CaptureLength(CaptureLengthMode.FRAMES, 1000).timeoutMillis(1_000_000_000L))
    }
}
