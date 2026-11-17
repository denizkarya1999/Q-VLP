package com.developer27.qvlp.inference

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What the OOK-Based LED Recognition Model is given. One crop is an LED cut out of a frame along its box and
 * resized to 128 × 128 pixels. One input of the model is a strip of five crops of the same LED side by side,
 * the earliest on the left: 640 × 128 pixels, as red, green and blue planes with values from 0 to 1. This is
 * how the training notebook builds its strips.
 */
internal object LedCrop {
    /** A crop is SIDE × SIDE pixels. */
    const val SIDE = 128
    /** The number of crops in a strip. */
    const val FRAMES = 5
    /** The size of one crop as red, green, blue bytes. */
    const val BYTES = SIDE * SIDE * 3
    const val STRIP_WIDTH = FRAMES * SIDE
    const val STRIP_PLANE = SIDE * STRIP_WIDTH
    /** The number of values in one strip. */
    const val STRIP_SIZE = 3 * STRIP_PLANE
    private val LEVEL = FloatArray(256) { it / 255f }

    /**
     * The whole pixels a box covers in a [width] × [height] picture, as left, top, right, bottom with the
     * right and bottom edges not included. It always holds at least one pixel.
     */
    fun region(box: LedDetection, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "The picture is empty." }
        val left = box.left.roundToInt().coerceIn(0, width - 1)
        val top = box.top.roundToInt().coerceIn(0, height - 1)
        return intArrayOf(left, top, box.right.roundToInt().coerceIn(left + 1, width), box.bottom.roundToInt().coerceIn(top + 1, height))
    }

    /** The crop of [region] from a picture [width] pixels wide, held in [rgb] as red, green, blue bytes. */
    fun fromRgb(rgb: ByteArray, width: Int, region: IntArray): ByteArray {
        val regionWidth = region[2] - region[0]
        val regionHeight = region[3] - region[1]
        val cut = ByteArray(regionWidth * regionHeight * 3)
        for (row in 0 until regionHeight)
            System.arraycopy(rgb, ((region[1] + row) * width + region[0]) * 3, cut, row * regionWidth * 3, regionWidth * 3)
        return resize(cut, regionWidth, regionHeight)
    }

    /** The crop of a region whose [regionWidth] × [regionHeight] pixels are in [argb] the way an Android bitmap returns them. */
    fun fromPixels(argb: IntArray, regionWidth: Int, regionHeight: Int): ByteArray {
        val cut = ByteArray(regionWidth * regionHeight * 3)
        var to = 0
        for (pixel in 0 until regionWidth * regionHeight) {
            val colour = argb[pixel]
            cut[to] = (colour shr 16).toByte(); cut[to + 1] = (colour shr 8).toByte(); cut[to + 2] = colour.toByte()
            to += 3
        }
        return resize(cut, regionWidth, regionHeight)
    }

    /**
     * [source] resized to SIDE × SIDE with bilinear interpolation between pixel centres, which is what the
     * training notebook's resize does to a picture it enlarges.
     */
    fun resize(source: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && source.size >= width * height * 3) { "The crop is smaller than its size says." }
        val columns = IntArray(SIDE)
        val columnShare = FloatArray(SIDE)
        val rows = IntArray(SIDE)
        val rowShare = FloatArray(SIDE)
        for (index in 0 until SIDE) {
            val x = (index + 0.5f) * width / SIDE - 0.5f
            val y = (index + 0.5f) * height / SIDE - 0.5f
            columns[index] = floor(x).toInt(); columnShare[index] = x - columns[index]
            rows[index] = floor(y).toInt(); rowShare[index] = y - rows[index]
        }
        val target = ByteArray(BYTES)
        var to = 0
        for (row in 0 until SIDE) {
            val upper = rows[row].coerceIn(0, height - 1) * width
            val lower = (rows[row] + 1).coerceIn(0, height - 1) * width
            val down = rowShare[row]
            for (column in 0 until SIDE) {
                val first = columns[column].coerceIn(0, width - 1)
                val second = (columns[column] + 1).coerceIn(0, width - 1)
                val across = columnShare[column]
                for (channel in 0..2) {
                    val a = source[(upper + first) * 3 + channel].toInt() and 0xFF
                    val b = source[(upper + second) * 3 + channel].toInt() and 0xFF
                    val c = source[(lower + first) * 3 + channel].toInt() and 0xFF
                    val d = source[(lower + second) * 3 + channel].toInt() and 0xFF
                    val above = a + (b - a) * across
                    val below = c + (d - c) * across
                    target[to++] = (above + (below - above) * down).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return target
    }

    /**
     * Writes one strip into [target] as strip number [index] of a batch. [crops] holds the crops of one LED in
     * the order they were taken, at most five. With fewer than five the earliest is repeated on the left, so
     * an LED seen in one frame only is a strip of five copies of that frame.
     */
    fun strip(crops: List<ByteArray>, target: FloatArray, index: Int) {
        require(crops.isNotEmpty() && crops.size <= FRAMES) { "A strip takes 1 to $FRAMES crops, not ${crops.size}." }
        require(target.size >= (index + 1) * STRIP_SIZE) { "The batch has no room for strip $index." }
        val level = LEVEL
        val start = index * STRIP_SIZE
        for (tile in 0 until FRAMES) {
            val crop = crops[(tile - (FRAMES - crops.size)).coerceAtLeast(0)]
            require(crop.size >= BYTES) { "A crop holds ${crop.size} bytes, not $BYTES." }
            var from = 0
            for (row in 0 until SIDE) {
                var to = start + row * STRIP_WIDTH + tile * SIDE
                for (column in 0 until SIDE) {
                    target[to] = level[crop[from].toInt() and 0xFF]
                    target[to + STRIP_PLANE] = level[crop[from + 1].toInt() and 0xFF]
                    target[to + 2 * STRIP_PLANE] = level[crop[from + 2].toInt() and 0xFF]
                    from += 3
                    to++
                }
            }
        }
    }
}
