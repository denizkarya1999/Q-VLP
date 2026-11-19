package com.developer27.qvlp.vision

import com.developer27.qvlp.inference.LedDetection
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The blob of one LED, found inside the box a model drew round it. [u] is the column and [v] the row of its
 * centre, counted from the centre of the top-left pixel like every other coordinate in a session. [left],
 * [top], [width] and [height] are the pixels the blob covers, and [frames] is how many frames it was read from.
 * A blob that touches the edge of its box may be cut off by it.
 */
internal data class LedCentre(
    val u: Double, val v: Double, val areaPx: Int, val peakLevel: Int,
    val left: Int, val top: Int, val width: Int, val height: Int,
    val saturated: Boolean, val touchesBoxEdge: Boolean, val frames: Int
)

/** The blobs of one frame, one for each box in the order the boxes were given; null where a box holds no blob. */
internal class FrameCentres(val leds: List<LedCentre?>, val background: Double, val frames: Int)

/**
 * Finds the blob centroid of each LED inside its box, for a capture made in Inference Mode with
 * Enable CV Algorithms ticked.
 *
 * The LEDs blink faster than a frame is read, so a single frame shows an LED in stripes: only the rows that
 * were read while it was lit. The lit rows are different ones in every frame, and the centre of what is lit in
 * one frame moves up and down with them by several pixels. So the blob is not taken from one frame. It is taken
 * from the brightest value each pixel had in the last [FRAMES] frames, which shows the whole LED. In that
 * picture the blob is the largest group of touching pixels that are at least half as bright as the brightest
 * pixel in the box, and its centre is the mean column and row of those pixels.
 *
 * On the third session's frames this centre stays within about half a pixel of where it settles when every
 * frame of a position is used, where the centre of the box is two to three pixels off in one frame out of twenty.
 * The first four frames of a capture have fewer frames behind them and are less steady.
 *
 * The camera has to stand still over those frames, as it does during a capture. Frames are given in the order
 * they were taken. Not thread-safe.
 */
internal class LedCentroids(private val settings: LedDetectionSettings = LedDetectionSettings()) {
    private val recent = ArrayDeque<ByteArray>()
    private var width = 0
    private var height = 0
    private var background = 0.0
    private var levels = IntArray(0)
    private var groups = IntArray(0)
    private var stack = IntArray(0)

    /** The next frame of the capture. [luma] holds at least width × height bytes, row by row; it is copied. */
    fun add(luma: ByteArray, width: Int, height: Int) {
        require(width > 0 && height > 0 && luma.size >= width * height) { "The frame is smaller than its stated size." }
        if (width != this.width || height != this.height) {
            recent.clear()
            this.width = width
            this.height = height
        }
        val plane = if (recent.size == FRAMES) recent.removeFirst() else ByteArray(width * height)
        System.arraycopy(luma, 0, plane, 0, width * height)
        recent.addLast(plane)
        background = backgroundLevel(plane, width, height, settings.backgroundSampleStep)
    }

    /** The blob in each of [boxes], which the models found in the frame added last. */
    fun centres(boxes: List<LedDetection>): FrameCentres =
        FrameCentres(boxes.map { centre(it.left, it.top, it.right, it.bottom) }, background, recent.size)

    /**
     * The blob inside a box whose edges are given in pixels from the picture's top-left corner, or null when
     * nothing in the box was lit in any of the frames.
     */
    fun centre(left: Float, top: Float, right: Float, bottom: Float): LedCentre? {
        if (recent.isEmpty()) return null
        // The whole pixels the box covers, as the processed picture draws it.
        val firstColumn = floor(left).toInt().coerceIn(0, width - 1)
        val firstRow = floor(top).toInt().coerceIn(0, height - 1)
        val boxWidth = (ceil(right).toInt() - 1).coerceIn(firstColumn, width - 1) - firstColumn + 1
        val boxHeight = (ceil(bottom).toInt() - 1).coerceIn(firstRow, height - 1) - firstRow + 1
        val size = boxWidth * boxHeight
        if (levels.size < size) { levels = IntArray(size); groups = IntArray(size); stack = IntArray(size) }
        var peak = 0
        for (row in 0 until boxHeight) {
            val from = (firstRow + row) * width + firstColumn
            for (column in 0 until boxWidth) {
                var level = 0
                for (plane in recent) level = maxOf(level, plane[from + column].toInt() and 0xFF)
                levels[row * boxWidth + column] = level
                if (level > peak) peak = level
            }
        }
        if (peak <= background + settings.thresholdAboveBackground) return null
        // A pixel is in when it is at least half the peak (MIN_PEAK_SHARE): twice its level reaches the peak.
        java.util.Arrays.fill(groups, 0, size, 0)
        var best = 0
        var bestArea = 0
        var group = 0
        for (start in 0 until size) {
            if (groups[start] != 0 || 2 * levels[start] < peak) continue
            group++
            var area = 0
            var top = 0
            stack[top++] = start
            groups[start] = group
            while (top > 0) {
                val pixel = stack[--top]
                area++
                val column = pixel % boxWidth
                val row = pixel / boxWidth
                // Touching means sharing an edge, as in LED detection.
                if (column > 0) top = join(pixel - 1, group, peak, top)
                if (column < boxWidth - 1) top = join(pixel + 1, group, peak, top)
                if (row > 0) top = join(pixel - boxWidth, group, peak, top)
                if (row < boxHeight - 1) top = join(pixel + boxWidth, group, peak, top)
            }
            if (area > bestArea) { bestArea = area; best = group }
        }
        var sumColumns = 0L
        var sumRows = 0L
        var minColumn = boxWidth
        var maxColumn = -1
        var minRow = boxHeight
        var maxRow = -1
        for (pixel in 0 until size) {
            if (groups[pixel] != best) continue
            val column = pixel % boxWidth
            val row = pixel / boxWidth
            sumColumns += column; sumRows += row
            if (column < minColumn) minColumn = column
            if (column > maxColumn) maxColumn = column
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
        }
        return LedCentre(
            u = firstColumn + sumColumns.toDouble() / bestArea, v = firstRow + sumRows.toDouble() / bestArea,
            areaPx = bestArea, peakLevel = peak,
            left = firstColumn + minColumn, top = firstRow + minRow, width = maxColumn - minColumn + 1, height = maxRow - minRow + 1,
            saturated = peak >= settings.saturationLevel,
            touchesBoxEdge = minColumn == 0 || minRow == 0 || maxColumn == boxWidth - 1 || maxRow == boxHeight - 1,
            frames = recent.size
        )
    }

    /** Adds [pixel] to [group] when it is bright enough and belongs to no group yet. Returns the new stack height. */
    private fun join(pixel: Int, group: Int, peak: Int, top: Int): Int {
        if (groups[pixel] != 0 || 2 * levels[pixel] < peak) return top
        groups[pixel] = group
        stack[top] = pixel
        return top + 1
    }

    companion object {
        /**
         * The luminance of [count] pixels of a picture, given as red, green and blue in the low three bytes of
         * each of [colours], written to [target] from [offset] on. The weights are those of the camera's frames,
         * so a grey pixel of a saved frame gets back the luminance the camera gave it.
         */
        fun luma(colours: IntArray, count: Int, target: ByteArray, offset: Int) {
            for (index in 0 until count) {
                val colour = colours[index]
                target[offset + index] = ((77 * ((colour shr 16) and 0xFF) + 150 * ((colour shr 8) and 0xFF) + 29 * (colour and 0xFF) + 128) shr 8).toByte()
            }
        }

        /** The frames a blob is read from: this one and the four before it, like the five frames an LED is named from. */
        const val FRAMES = 5
        /**
         * A pixel belongs to the blob when it is at least this share of the brightest pixel in the box. Of the
         * shares from a fifth to seven tenths, half was the steadiest for all four LEDs of the third session and
         * never let the glow of an overexposed LED reach the edge of its box. The share decides where the centre
         * lies: with a fifth it is 0.3 to 1.7 pixels elsewhere.
         */
        const val MIN_PEAK_SHARE = 0.5
    }
}
