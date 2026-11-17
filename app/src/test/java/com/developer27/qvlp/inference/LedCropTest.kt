package com.developer27.qvlp.inference

import org.junit.Assert.*
import org.junit.Test

class LedCropTest {
    private fun box(left: Float, top: Float, right: Float, bottom: Float) = LedDetection(LedDetection.UNNAMED, 0.9f, left, top, right, bottom)

    /** A crop of one colour all over. */
    private fun plain(red: Int, green: Int = red, blue: Int = red) = ByteArray(LedCrop.BYTES) { index ->
        (when (index % 3) { 0 -> red; 1 -> green; else -> blue }).toByte()
    }

    @Test fun aBoxCoversWholePixelsInsideThePicture() {
        assertArrayEquals(intArrayOf(769, 744, 808, 785), LedCrop.region(box(769.4f, 743.6f, 808.4f, 785.2f), 1280, 960))
        // A box that reaches past the picture is cut at its edge.
        assertArrayEquals(intArrayOf(0, 950, 3, 960), LedCrop.region(box(-5f, 950f, 3f, 970f), 1280, 960))
        // However small, it holds a pixel.
        assertArrayEquals(intArrayOf(10, 10, 11, 11), LedCrop.region(box(10.2f, 10.2f, 10.4f, 10.4f), 1280, 960))
        assertArrayEquals(intArrayOf(1279, 959, 1280, 960), LedCrop.region(box(1290f, 990f, 1300f, 999f), 1280, 960))
    }

    @Test fun aRegionOfTheCropsOwnSizeIsCopiedAsItIs() {
        val width = 200
        val height = 150
        val picture = ByteArray(width * height * 3) { (it * 7 % 251).toByte() }
        val crop = LedCrop.fromRgb(picture, width, intArrayOf(10, 5, 10 + LedCrop.SIDE, 5 + LedCrop.SIDE))
        assertEquals(LedCrop.BYTES, crop.size)
        for (row in listOf(0, 60, 127)) for (column in listOf(0, 31, 127)) for (channel in 0..2)
            assertEquals(picture[((5 + row) * width + 10 + column) * 3 + channel], crop[(row * LedCrop.SIDE + column) * 3 + channel])
    }

    @Test fun aSmallerRegionIsEnlargedSmoothlyBetweenItsPixels() {
        // Two pixels side by side, dark and bright, over two rows.
        val crop = LedCrop.resize(byteArrayOf(0, 0, 0, 200.toByte(), 200.toByte(), 200.toByte(), 0, 0, 0, 200.toByte(), 200.toByte(), 200.toByte()), 2, 2)
        val row = (0 until LedCrop.SIDE).map { crop[(64 * LedCrop.SIDE + it) * 3].toInt() and 0xFF }
        // Outside the two pixel centres the edge pixel is repeated; between them the level rises steadily.
        assertEquals(0, row[0]); assertEquals(0, row[31]); assertEquals(200, row[96]); assertEquals(200, row[127])
        assertTrue(row.zipWithNext().all { (left, right) -> right >= left })
        assertEquals(98, row[63]); assertEquals(102, row[64])
        // Nothing changes from top to bottom, and the three colours stay equal.
        for (column in listOf(0, 50, 64, 127)) {
            assertEquals(crop[column * 3], crop[(127 * LedCrop.SIDE + column) * 3])
            assertEquals(crop[column * 3], crop[column * 3 + 2])
        }
    }

    @Test fun aBitmapRegionGivesTheSameCropAsTheSameColoursInBytes() {
        val width = 37
        val height = 41
        val argb = IntArray(width * height) { (0xFF shl 24) or ((it * 3 % 256) shl 16) or ((it * 5 % 256) shl 8) or (it * 11 % 256) }
        val rgb = ByteArray(width * height * 3) { index ->
            val colour = argb[index / 3]
            (when (index % 3) { 0 -> colour shr 16; 1 -> colour shr 8; else -> colour }).toByte()
        }
        assertArrayEquals(LedCrop.fromRgb(rgb, width, intArrayOf(0, 0, width, height)), LedCrop.fromPixels(argb, width, height))
    }

    @Test fun aStripIsFiveCropsSideBySideAsColourPlanes() {
        val strip = FloatArray(LedCrop.STRIP_SIZE)
        LedCrop.strip(listOf(plain(0), plain(51), plain(102), plain(153), plain(255, 0, 51)), strip, 0)
        for (row in listOf(0, 77, 127)) {
            for (tile in 0..3) for (column in listOf(0, 127))
                assertEquals(tile * 51 / 255f, strip[row * LedCrop.STRIP_WIDTH + tile * LedCrop.SIDE + column], 1e-6f)
            // The last crop is red 255, green 0, blue 51: one plane each.
            val last = row * LedCrop.STRIP_WIDTH + 4 * LedCrop.SIDE + 5
            assertEquals(1f, strip[last], 0f)
            assertEquals(0f, strip[LedCrop.STRIP_PLANE + last], 0f)
            assertEquals(0.2f, strip[2 * LedCrop.STRIP_PLANE + last], 1e-6f)
        }
    }

    @Test fun withFewerThanFiveCropsTheEarliestIsRepeatedOnTheLeft() {
        val strip = FloatArray(LedCrop.STRIP_SIZE)
        LedCrop.strip(listOf(plain(51), plain(255)), strip, 0)
        assertEquals(listOf(0.2f, 0.2f, 0.2f, 0.2f, 1f), (0..4).map { strip[it * LedCrop.SIDE + 3] })
        LedCrop.strip(listOf(plain(102)), strip, 0)
        assertEquals(listOf(0.4f, 0.4f, 0.4f, 0.4f, 0.4f), (0..4).map { strip[it * LedCrop.SIDE + 3] })
    }

    @Test fun eachLedOfABatchHasItsOwnStrip() {
        val batch = FloatArray(2 * LedCrop.STRIP_SIZE)
        LedCrop.strip(listOf(plain(255)), batch, 0)
        LedCrop.strip(listOf(plain(51)), batch, 1)
        assertEquals(1f, batch[LedCrop.STRIP_SIZE - 1], 0f)
        assertEquals(0.2f, batch[LedCrop.STRIP_SIZE], 1e-6f)
        assertEquals(0.2f, batch[2 * LedCrop.STRIP_SIZE - 1], 1e-6f)
    }

    @Test fun rejectsAStripWithNoCropsOrTooMany() {
        val strip = FloatArray(LedCrop.STRIP_SIZE)
        assertThrows(IllegalArgumentException::class.java) { LedCrop.strip(emptyList(), strip, 0) }
        assertThrows(IllegalArgumentException::class.java) { LedCrop.strip(List(6) { plain(0) }, strip, 0) }
        assertThrows(IllegalArgumentException::class.java) { LedCrop.strip(listOf(plain(0)), strip, 1) }
    }
}
