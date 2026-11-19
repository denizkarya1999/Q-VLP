package com.developer27.qvlp.inference

import com.developer27.qvlp.vision.LedCentroids
import org.junit.Assert.*
import org.junit.Test

class LiveCentroidsTest {
    private val width = 200
    private val height = 160
    // The camera's frames at 30 a second.
    private val interval = 33_333_333L
    private val start = 5_000_000_000L

    /**
     * The luminance plane of one frame: a round LED at [column], row 80, 15 pixels across, lit in 10 of every
     * 20 rows. [shift] says which rows are the lit ones in this frame.
     */
    private fun frame(shift: Int, column: Int = 100): ByteArray {
        val luma = ByteArray(width * height) { 2 }
        for (row in 0 until height) for (x in 0 until width) {
            val inside = (x - column) * (x - column) + (row - 80) * (row - 80) <= 7 * 7
            if (inside && (row + shift).mod(20) < 10) luma[row * width + x] = 200.toByte()
        }
        return luma
    }

    private fun box(column: Int) = LedDetection(1, 0.99f, column - 20f, 62f, column + 21f, 102f)

    @Test fun everyFifthFrameGoesToTheModelsAndTheBlobIsReadFromItAndTheFourBefore() {
        val live = LiveCentroids()
        val forModels = (0 until 15).filter { live.add(frame(it * 4), width, height, start + it * interval) }
        assertEquals(listOf(4, 9, 14), forModels)
        val found = live.centres(listOf(box(100)))
        assertEquals(LedCentroids.FRAMES, found.frames)
        // The five frames together show every row of the LED, so its centre is where the LED is.
        assertEquals(100.0, found.leds.single()!!.u, 1e-9)
        assertEquals(80.0, found.leds.single()!!.v, 1e-9)
        assertEquals(149, found.leds.single()!!.areaPx)
    }

    @Test fun theFirstFrameTheModelsGetAlreadyHasFiveFramesBehindIt() {
        val live = LiveCentroids()
        repeat(4) { assertFalse(live.add(frame(it * 4), width, height, start + it * interval)) }
        assertTrue(live.add(frame(16), width, height, start + 4 * interval))
        assertEquals(5, live.centres(listOf(box(100))).leds.single()!!.frames)
    }

    @Test fun theBlobBelongsToTheLatestFiveFramesOnly() {
        val live = LiveCentroids()
        repeat(5) { live.add(frame(it * 4), width, height, start + it * interval) }
        // While the models ran the camera was moved: the LED is now at column 60. Long after, the next five frames arrive.
        val later = start + 1_400_000_000L
        val forModels = (0 until 5).map { live.add(frame(it * 4, column = 60), width, height, later + it * interval) }
        assertEquals(listOf(false, false, false, false, true), forModels)
        val found = live.centres(listOf(box(100), box(60)))
        // Nothing of the earlier five frames is left: no blob where the LED was, and its whole shape where it is.
        assertNull(found.leds[0])
        assertEquals(60.0, found.leds[1]!!.u, 1e-9)
        assertEquals(80.0, found.leds[1]!!.v, 1e-9)
    }

    @Test fun afterAPauseInTheMiddleOfAGroupTheCountStartsAgain() {
        val live = LiveCentroids()
        repeat(3) { assertFalse(live.add(frame(it * 4), width, height, start + it * interval)) }
        // A position is captured: the camera's frames go to the capture for two seconds, and the camera is moved.
        val later = start + 2_000_000_000L
        val forModels = (0 until 5).map { live.add(frame(it * 4, column = 60), width, height, later + it * interval) }
        assertEquals(listOf(false, false, false, false, true), forModels)
        val found = live.centres(listOf(box(100), box(60)))
        assertNull(found.leds[0])
        assertEquals(60.0, found.leds[1]!!.u, 1e-9)
    }

    @Test fun framesUpToHalfASecondApartStillBelongTogether() {
        val live = LiveCentroids()
        // A slow camera: a frame every half second, which is the limit.
        val slow = (0 until 5).map { live.add(frame(it * 4), width, height, start + it * LiveCentroids.MAX_GAP_NS) }
        assertEquals(listOf(false, false, false, false, true), slow)
        // One nanosecond more is a pause.
        assertFalse(live.add(frame(0), width, height, start + 5 * LiveCentroids.MAX_GAP_NS))
        assertFalse(live.add(frame(4), width, height, start + 6 * LiveCentroids.MAX_GAP_NS + 1))
        val after = (2..5).map { live.add(frame(it * 4), width, height, start + 6 * LiveCentroids.MAX_GAP_NS + 1 + (it - 1) * interval) }
        assertEquals(listOf(false, false, false, true), after)
    }

    @Test fun aClockThatStartsAgainStartsTheCountAgain() {
        val live = LiveCentroids()
        repeat(3) { live.add(frame(it * 4), width, height, start + it * interval) }
        // Another camera counts its time from somewhere else.
        val other = (0 until 5).map { live.add(frame(it * 4), width, height, 1_000L + it * interval) }
        assertEquals(listOf(false, false, false, false, true), other)
    }

    @Test fun aFrameMayBeGivenAsWholeI420() {
        val live = LiveCentroids()
        // The luminance plane comes first; the colour planes after it are not read.
        val whole = (0 until 5).map { frame(it * 4) + ByteArray(width * height / 2) { 128.toByte() } }
        whole.forEachIndexed { index, bytes -> live.add(bytes, width, height, start + index * interval) }
        assertEquals(100.0, live.centres(listOf(box(100))).leds.single()!!.u, 1e-9)
    }
}
