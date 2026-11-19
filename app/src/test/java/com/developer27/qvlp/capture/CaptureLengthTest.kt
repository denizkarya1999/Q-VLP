package com.developer27.qvlp.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureLengthTest {
    @Test fun validatesInputWithoutSilentlyChangingTheRequestedLength() {
        assertEquals(CaptureLength(90), CaptureLength.parse(" 90 "))
        for (raw in listOf(null, "", "0", "-1", "1.5", "1001", "99999999999999999999", "NaN")) {
            assertNull(raw, CaptureLength.parse(raw))
        }
        assertNotNull(CaptureLength.parse("1"))
        assertNotNull(CaptureLength.parse("1000"))
        assertEquals(50, CaptureLength().frames)
        assertTrue(runCatching { CaptureLength(0) }.isFailure)
    }

    @Test fun longerCaptureStopsExactlyAtItsLimitDespiteHardwareFrameNumberGaps() {
        val window = CaptureWindow(CaptureLength(90))
        repeat(90) { index ->
            assertEquals(index, window.start(100L + index * 2))
            assertEquals(index == 89, window.complete)
        }
        // In-flight requests after stopRepeating must not leak into the saved data.
        assertNull(window.start(500L))
        assertNull(window.frameId(500L))
        // Metadata may arrive out of order after the stop request.
        assertEquals(89, window.frameId(278L))
        assertEquals(0, window.frameId(100L))
    }

    @Test fun oneFrameCaptureDoesNotNeedASecondFrameToStop() {
        val window = CaptureWindow(CaptureLength(1))
        assertEquals(0, window.start(81))
        assertTrue(window.complete)
        assertNull(window.start(82))
    }

    @Test fun repeatedStartCallbackDoesNotCountTheSameExposureTwice() {
        val window = CaptureWindow(CaptureLength(2))
        assertEquals(0, window.start(10))
        assertEquals(0, window.start(10))
        assertFalse(window.complete)
        assertEquals(1, window.start(11))
        assertTrue(window.complete)
    }

    @Test fun timeoutAllowsLongCapturesAndSlowSensorProfiles() {
        assertEquals(12_000L, CaptureLength().timeoutMillis(33_333_334L))
        assertEquals(104_999L, CaptureLength(1000).timeoutMillis(33_333_334L))
        assertEquals(3_005_000L, CaptureLength(1000).timeoutMillis(1_000_000_000L))
    }
}
