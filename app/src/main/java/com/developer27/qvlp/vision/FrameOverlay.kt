package com.developer27.qvlp.vision

import com.developer27.qvlp.inference.LedDetection
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws what LED detection found onto a frame: a box around every blob, a cross at its centre and its LED name.
 * This is the "processed" picture of a frame. A dashed box marks a blob that was found but not accepted.
 * A capture made in Inference Mode gets the same picture from what the two LED models found.
 */
internal object FrameOverlay {
    /** One colour per LED, as in the project's figures: red, blue, green, purple. */
    private val LED_COLOURS = intArrayOf(0xD62728, 0x1F77B4, 0x2CA02C, 0x9467BD)
    private const val UNKNOWN = 0xFFD400
    private const val LINE = 2
    private const val TEXT_SCALE = 3

    /** Returns false when there was nothing to draw, so the picture is unchanged. */
    fun draw(rgb: ByteArray, width: Int, height: Int, frame: FrameLeds): Boolean {
        if (frame.blobs.isEmpty()) return false
        for (blob in frame.blobs) {
            val led = frame.leds.indexOfFirst { it.blob === blob }
            val accepted = led >= 0 && frame.leds[led].detected
            val colour = if (led >= 0) LED_COLOURS[led % LED_COLOURS.size] else UNKNOWN
            box(rgb, width, height, blob.left - LINE, blob.top - LINE, blob.left + blob.width + LINE - 1,
                blob.top + blob.height + LINE - 1, colour, dashed = !accepted)
            cross(rgb, width, height, blob.u.roundToInt(), blob.v.roundToInt(), colour)
            val label = if (led >= 0) "LED ${led + 1}" + (if (accepted) "" else " ?") else "?"
            val textHeight = 7 * TEXT_SCALE
            val above = blob.top - LINE - textHeight - 4
            text(rgb, width, height, blob.left - LINE, if (above >= 0) above else blob.top + blob.height + LINE + 4, label, colour)
        }
        return true
    }

    /**
     * The processed picture of a frame from an Inference Mode capture: a box round every LED the detection
     * model found, a cross at its centre and the name the recognition model gave it. An LED that was found but
     * not named is yellow and marked "LED ?". Returns false when there was nothing to draw.
     */
    fun drawInferred(rgb: ByteArray, width: Int, height: Int, found: List<LedDetection>): Boolean {
        if (found.isEmpty()) return false
        for (led in found) {
            val colour = if (led.named) LED_COLOURS[(led.led - 1).mod(LED_COLOURS.size)] else UNKNOWN
            // The pixels the box covers; the line is drawn just outside them, as round a blob.
            val left = floor(led.left).toInt()
            val top = floor(led.top).toInt()
            val right = maxOf(left, ceil(led.right).toInt() - 1)
            val bottom = maxOf(top, ceil(led.bottom).toInt() - 1)
            box(rgb, width, height, left - LINE, top - LINE, right + LINE, bottom + LINE, colour, dashed = false)
            // The centre as the metadata gives it: measured from the centre of the top-left pixel.
            cross(rgb, width, height, ((led.left + led.right) / 2f - 0.5f).roundToInt(), ((led.top + led.bottom) / 2f - 0.5f).roundToInt(), colour)
            val above = top - LINE - 7 * TEXT_SCALE - 4
            text(rgb, width, height, left - LINE, if (above >= 0) above else bottom + LINE + 5, if (led.named) "LED ${led.led}" else "LED ?", colour)
        }
        return true
    }

    private fun set(rgb: ByteArray, width: Int, height: Int, x: Int, y: Int, colour: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        val index = (y * width + x) * 3
        rgb[index] = (colour shr 16).toByte(); rgb[index + 1] = (colour shr 8).toByte(); rgb[index + 2] = colour.toByte()
    }

    private fun box(rgb: ByteArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, colour: Int, dashed: Boolean) {
        fun on(step: Int) = !dashed || (step / 6) % 2 == 0
        for (thickness in 0 until LINE) {
            for (x in left..right) if (on(x - left)) {
                set(rgb, width, height, x, top + thickness, colour); set(rgb, width, height, x, bottom - thickness, colour)
            }
            for (y in top..bottom) if (on(y - top)) {
                set(rgb, width, height, left + thickness, y, colour); set(rgb, width, height, right - thickness, y, colour)
            }
        }
    }

    private fun cross(rgb: ByteArray, width: Int, height: Int, x: Int, y: Int, colour: Int) {
        for (offset in -9..9) for (thickness in -1..0) {
            set(rgb, width, height, x + offset, y + thickness, colour); set(rgb, width, height, x + thickness, y + offset, colour)
        }
    }

    private fun text(rgb: ByteArray, width: Int, height: Int, left: Int, top: Int, label: String, colour: Int) {
        var x = left.coerceIn(0, maxOf(0, width - label.length * 6 * TEXT_SCALE))
        for (character in label) {
            val glyph = GLYPHS[character]
            if (glyph != null) for (row in 0 until 7) for (column in 0 until 5) {
                if (glyph[row][column] == '#') for (dy in 0 until TEXT_SCALE) for (dx in 0 until TEXT_SCALE) {
                    set(rgb, width, height, x + column * TEXT_SCALE + dx, top + row * TEXT_SCALE + dy, colour)
                }
            }
            x += 6 * TEXT_SCALE
        }
    }

    /** A 5 × 7 dot font for the handful of characters a label needs. */
    private val GLYPHS: Map<Char, List<String>> = mapOf(
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '?' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#..")
    )
}
