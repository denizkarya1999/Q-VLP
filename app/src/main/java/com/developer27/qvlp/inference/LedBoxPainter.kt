package com.developer27.qvlp.inference

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws one LED the way Inference Mode and Local File Inference both show it: a box in the LED's colour with
 * its name and confidence on a tag above it, for example "LED 1 | 98.0%". An LED without a name gets a grey
 * box. With Enable CV Algorithms a small cross marks the LED's blob centroid and a second tag under the box
 * gives its coordinates, for example "{868.41,286.95}".
 */
internal class LedBoxPainter(private val stroke: Float, textSize: Float, private val arm: Float = textSize * 0.5f) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val padding = textSize * 0.3f
    // The cross at a blob centroid, half as thick as the box, with arms of length arm.
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = maxOf(1.5f, stroke / 2f) }
    // A dark edge under the cross, so it also shows on the white of an overexposed LED.
    private val markEdge = Paint(mark).apply { strokeWidth = mark.strokeWidth * 2f; color = Color.BLACK }

    /**
     * The box edges are in the canvas's own pixels. [coordinates] is the LED's blob centroid as text, written
     * on a second tag under the box; null when no blob was looked for or found.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, found: LedDetection, coordinates: String? = null) {
        val colour = colour(found.led)
        outline.color = colour
        tag.color = colour
        canvas.drawRect(left, top, right, bottom, outline)
        val tops = tagTops(top - stroke / 2f, bottom + stroke / 2f, text.textSize + 2 * padding, canvas.height.toFloat(), coordinates != null)
        tag(canvas, found.label, left, tops[0])
        if (coordinates != null) tag(canvas, coordinates, left, tops[1])
    }

    /** One tag: white letters on the LED's colour, starting at the box's left edge and never off the side of the picture. */
    private fun tag(canvas: Canvas, label: String, left: Float, top: Float) {
        val tagWidth = text.measureText(label) + 2 * padding
        val tagHeight = text.textSize + 2 * padding
        val tagLeft = (left - stroke / 2f).coerceIn(0f, maxOf(0f, canvas.width - tagWidth))
        canvas.drawRect(tagLeft, top, tagLeft + tagWidth, top + tagHeight, tag)
        canvas.drawText(label, tagLeft + padding, top + padding - text.fontMetrics.ascent * 0.92f, text)
    }

    /** A cross in the LED's colour at ([x], [y]), in the canvas's own pixels: the centroid of its blob. */
    fun drawCentre(canvas: Canvas, x: Float, y: Float, found: LedDetection) {
        mark.color = colour(found.led)
        for (paint in arrayOf(markEdge, mark)) {
            val reach = arm + (paint.strokeWidth - mark.strokeWidth) / 2f
            canvas.drawLine(x - reach, y, x + reach, y, paint)
            canvas.drawLine(x, y - reach, x, y + reach, paint)
        }
    }

    companion object {
        /**
         * Where the name tag and the coordinates tag go, as the top of each: the name above the box and the
         * coordinates under it. A box at the top of the picture has both under it, the name first, and a box
         * at the bottom has both above it, the coordinates next to the box. [boxTop] and [boxBottom] are the
         * outer edges of the box, and the second value is unused when there are no coordinates.
         */
        fun tagTops(boxTop: Float, boxBottom: Float, tagHeight: Float, canvasHeight: Float, coordinates: Boolean): FloatArray {
            val lowest = maxOf(0f, canvasHeight - tagHeight)
            if (boxTop - tagHeight < 0f) {
                val name = minOf(boxBottom, if (coordinates) maxOf(0f, lowest - tagHeight) else lowest)
                return floatArrayOf(name, name + tagHeight)
            }
            if (coordinates && boxBottom + tagHeight > canvasHeight && boxTop - 2 * tagHeight >= 0f) {
                return floatArrayOf(boxTop - 2 * tagHeight, boxTop - tagHeight)
            }
            return floatArrayOf(boxTop - tagHeight, minOf(boxBottom, lowest))
        }

        /** One colour per LED, as in the project's figures and the processed frames: red, blue, green, purple. */
        private val COLOURS = intArrayOf(0xFFD62728.toInt(), 0xFF1F77B4.toInt(), 0xFF2CA02C.toInt(), 0xFF9467BD.toInt())
        /** An LED that was found but not named. */
        private const val UNNAMED_COLOUR = 0xFF7F7F7F.toInt()

        fun colour(led: Int): Int = if (led in 1..COLOURS.size) COLOURS[led - 1] else UNNAMED_COLOUR

        /** For drawing on a picture itself: lines and letters sized to stay readable at the picture's width. */
        fun forPicture(width: Int) = LedBoxPainter(stroke = maxOf(2f, width / 420f), textSize = maxOf(14f, width / 38f),
            // An LED is some fifteen pixels across in a 1280 pixel frame: the cross stays about that size.
            arm = maxOf(5f, width / 160f))
    }
}
