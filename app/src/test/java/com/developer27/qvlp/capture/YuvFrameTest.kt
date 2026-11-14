package com.developer27.qvlp.capture

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class YuvFrameTest {
    /** A plane as cameras deliver it: padded rows, and chroma bytes separated by [pixelStride]. */
    private fun plane(columns: Int, rows: Int, rowStride: Int, pixelStride: Int, lead: Int = 0): Pair<ByteBuffer, ByteArray> {
        val expected = ByteArray(columns * rows) { (it * 7 + 3).toByte() }
        // The last row is not padded out to the stride, as on real devices.
        val bytes = ByteArray(lead + (rows - 1) * rowStride + (columns - 1) * pixelStride + 1) { 0x55 }
        for (row in 0 until rows) for (column in 0 until columns)
            bytes[lead + row * rowStride + column * pixelStride] = expected[row * columns + column]
        return ByteBuffer.wrap(bytes).apply { position(lead) } to expected
    }

    private fun copied(columns: Int, rows: Int, rowStride: Int, pixelStride: Int, lead: Int = 0) {
        val (source, expected) = plane(columns, rows, rowStride, pixelStride, lead)
        val target = ByteArray(expected.size + 5) { 9 }
        assertEquals(expected.size + 5, YuvFrame.copyPlane(source, rowStride, pixelStride, columns, rows, target, 5))
        assertArrayEquals(expected, target.copyOfRange(5, target.size))
        assertArrayEquals(ByteArray(5) { 9 }, target.copyOf(5))
    }

    @Test fun copiesATightlyPackedPlaneInOnePiece() = copied(columns = 64, rows = 48, rowStride = 64, pixelStride = 1)

    @Test fun dropsRowPadding() = copied(columns = 61, rows = 37, rowStride = 80, pixelStride = 1)

    @Test fun picksEverySecondByteOfInterleavedChroma() = copied(columns = 32, rows = 24, rowStride = 64, pixelStride = 2)

    @Test fun honoursTheBufferPositionAndWiderStrides() = copied(columns = 9, rows = 5, rowStride = 40, pixelStride = 3, lead = 7)

    @Test fun refusesAPlaneThatIsTooShort() {
        val (source, _) = plane(columns = 32, rows = 24, rowStride = 64, pixelStride = 2)
        source.limit(source.limit() - 1)
        assertTrue(runCatching { YuvFrame.copyPlane(source, 64, 2, 32, 24, ByteArray(32 * 24), 0) }.isFailure)
    }

    @Test fun buffersEnoughFramesToRideOutTheStartOfABurst() {
        // 1280 × 960 frames are 1.8 MB: twelve fit the 32 MB budget. A full-sensor frame still gets one.
        assertEquals(12, BurstWriter.inFlightFrames(1280, 960))
        assertEquals(10, BurstWriter.inFlightFrames(1920, 1080))
        assertEquals(1, BurstWriter.inFlightFrames(4624, 3472))
    }
}
