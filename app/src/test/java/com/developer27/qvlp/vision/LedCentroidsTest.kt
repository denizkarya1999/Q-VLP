package com.developer27.qvlp.vision

import com.developer27.qvlp.inference.LedDetection
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LedCentroidsTest {
    private val width = 200
    private val height = 160

    /**
     * One frame of a round LED at column 100, row 80, 15 pixels across, on a dark ceiling. It blinks faster than
     * the frame is read: of every 20 rows only 10 are lit, and [shift] says which ones.
     */
    private fun frame(shift: Int, level: Int = 200, lit: Boolean = true): ByteArray {
        val luma = ByteArray(width * height) { 2 }
        if (lit) for (row in 0 until height) for (column in 0 until width) {
            val inside = (column - 100) * (column - 100) + (row - 80) * (row - 80) <= 7 * 7
            if (inside && (row + shift).mod(20) < 10) luma[row * width + column] = level.toByte()
        }
        return luma
    }

    private val box = LedDetection(1, 0.99f, 80f, 62f, 121f, 102f)

    @Test fun oneFrameShowsOnlyTheLitRowsAndFiveFramesShowTheWholeLed() {
        val centroids = LedCentroids()
        centroids.add(frame(0), width, height)
        val first = centroids.centres(listOf(box))
        assertEquals(1, first.frames)
        // Rows 80 to 87 were lit in this frame, so the centre of what is lit sits well below the LED's own.
        val alone = first.leds.single()!!
        assertEquals(100.0, alone.u, 1e-9)
        assertTrue(alone.v - 80.0 > 2.5)
        assertEquals(1, alone.frames)

        listOf(4, 8, 12, 16).forEach { centroids.add(frame(it), width, height) }
        val settled = centroids.centres(listOf(box))
        assertEquals(5, settled.frames)
        assertEquals(2.0, settled.background, 1e-9)
        val whole = settled.leds.single()!!
        assertEquals(100.0, whole.u, 1e-9)
        assertEquals(80.0, whole.v, 1e-9)
        assertEquals(149, whole.areaPx)                    // every pixel of the round LED
        assertEquals(200, whole.peakLevel)
        assertEquals(listOf(93, 73, 15, 15), listOf(whole.left, whole.top, whole.width, whole.height))
        assertFalse(whole.saturated)
        assertFalse(whole.touchesBoxEdge)
        assertEquals(5, whole.frames)
    }

    @Test fun theCentreStaysPutWhicheverRowsTheLatestFrameShows() {
        val centroids = LedCentroids()
        val centres = (0 until 40).map { index ->
            centroids.add(frame(index * 4), width, height)
            centroids.centre(box.left, box.top, box.right, box.bottom)!!
        }
        // From the fifth frame on the last five frames always cover every row of the LED.
        assertTrue(centres.drop(4).all { abs(it.u - 100.0) < 1e-9 && abs(it.v - 80.0) < 1e-9 })
        assertTrue(centres.take(4).any { abs(it.v - 80.0) > 2.5 })
    }

    @Test fun onlyTheLastFiveFramesCount() {
        val centroids = LedCentroids()
        repeat(5) { centroids.add(frame(it * 4), width, height) }
        assertNotNull(centroids.centre(box.left, box.top, box.right, box.bottom))
        // The LED goes dark. After four dark frames one lit frame is still among the last five.
        repeat(4) { centroids.add(frame(0, lit = false), width, height) }
        assertNotNull(centroids.centre(box.left, box.top, box.right, box.bottom))
        centroids.add(frame(0, lit = false), width, height)
        assertNull(centroids.centre(box.left, box.top, box.right, box.bottom))
    }

    @Test fun aBoxWithNothingLitHasNoBlob() {
        val centroids = LedCentroids()
        assertNull(centroids.centre(0f, 0f, 10f, 10f))     // no frame yet
        // 27 is the ceiling's 2 plus the threshold of 25: only brighter counts as lit.
        centroids.add(frame(0, level = 27), width, height)
        repeat(4) { centroids.add(frame(4 + it * 4, level = 27), width, height) }
        assertNull(centroids.centres(listOf(box)).leds.single())
        centroids.add(frame(0, level = 28), width, height)
        assertNotNull(centroids.centres(listOf(box)).leds.single())
        // A box beside the LED is dark.
        assertNull(centroids.centre(10f, 10f, 50f, 50f))
    }

    @Test fun theBlobIsTheLargestGroupOfPixelsAtLeastHalfAsBrightAsTheBrightest() {
        val luma = ByteArray(width * height) { 2 }
        fun fill(columns: IntRange, rows: IntRange, level: Int) { for (row in rows) for (column in columns) luma[row * width + column] = level.toByte() }
        fill(90..99, 70..79, 200)       // the LED: 100 pixels, centre 94.5, 74.5
        fill(100..101, 70..79, 99)      // its dim rim
        fill(110..112, 90..92, 240)     // a small reflection, brighter than the LED: 9 pixels
        fill(86..87, 70..71, 100)       // a dim speck beside the LED
        val centroids = LedCentroids()
        centroids.add(luma, width, height)
        // The brightest pixel is 240, so a pixel counts from 120 up: the LED and the reflection, and the LED is larger.
        val blob = centroids.centre(80f, 60f, 120f, 100f)!!
        assertEquals(240, blob.peakLevel)
        assertEquals(100, blob.areaPx)
        assertEquals(94.5, blob.u, 1e-9)
        assertEquals(74.5, blob.v, 1e-9)
        // Without the reflection the brightest pixel is 200 and a pixel counts from exactly 100 up. The rim at 99
        // stays out; a strip at 100 that touches the LED joins it, and the speck joins through the strip.
        fill(110..112, 90..92, 2)
        fill(88..89, 70..79, 100)
        val next = LedCentroids().apply { add(luma, width, height) }.centre(80f, 60f, 120f, 100f)!!
        assertEquals(200, next.peakLevel)
        assertEquals(100 + 20 + 4, next.areaPx)
    }

    @Test fun coordinatesAreCountedFromTheCentreOfTheTopLeftPixelAndTheBoxCoversWholePixels() {
        val luma = ByteArray(width * height) { 2 }
        luma[20 * width + 10] = 180.toByte()
        val centroids = LedCentroids().apply { add(luma, width, height) }
        // The box covers part of columns 9 to 11 and rows 19 to 21: all of those pixels are looked at.
        val blob = centroids.centre(9.6f, 19.2f, 11.3f, 21.9f)!!
        assertEquals(10.0, blob.u, 1e-9)
        assertEquals(20.0, blob.v, 1e-9)
        assertEquals(1, blob.areaPx)
        assertFalse(blob.touchesBoxEdge)
        // A box that ends at the pixel's left border does not hold it.
        assertNull(centroids.centre(5f, 15f, 10f, 25f))
        // A box that leaves the picture is cut to it.
        assertNotNull(centroids.centre(-20f, -20f, 11f, 21f))
        assertNull(centroids.centre(300f, 300f, 340f, 340f))
    }

    @Test fun aBlobCutByItsBoxOrAtFullBrightnessSaysSo() {
        val centroids = LedCentroids()
        repeat(5) { centroids.add(frame(it * 4, level = 255), width, height) }
        val whole = centroids.centre(80f, 62f, 121f, 102f)!!
        assertTrue(whole.saturated)
        assertFalse(whole.touchesBoxEdge)
        // The box ends at column 100, in the middle of the LED.
        val cut = centroids.centre(80f, 62f, 101f, 102f)!!
        assertTrue(cut.touchesBoxEdge)
        assertTrue(cut.u < 99.0)
    }

    @Test fun everyBoxGetsItsOwnBlobInTheOrderGiven() {
        val centroids = LedCentroids()
        repeat(5) { centroids.add(frame(it * 4), width, height) }
        val dark = LedDetection(3, 0.9f, 10f, 10f, 50f, 50f)
        val unnamed = LedDetection(LedDetection.UNNAMED, 0.8f, 85f, 65f, 115f, 95f)
        val found = centroids.centres(listOf(dark, box, unnamed))
        assertEquals(3, found.leds.size)
        assertNull(found.leds[0])
        assertEquals(80.0, found.leds[1]!!.v, 1e-9)
        assertEquals(100.0, found.leds[2]!!.u, 1e-9)
        assertTrue(centroids.centres(emptyList()).leds.isEmpty())
    }

    @Test fun aFrameOfAnotherSizeStartsAgain() {
        val centroids = LedCentroids()
        repeat(5) { centroids.add(frame(it * 4), width, height) }
        centroids.add(ByteArray(64 * 48) { 2 }, 64, 48)
        assertEquals(1, centroids.centres(emptyList()).frames)
        assertNull(centroids.centre(0f, 0f, 64f, 48f))
    }
}
