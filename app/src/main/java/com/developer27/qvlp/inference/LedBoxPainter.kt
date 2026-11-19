package com.developer27.qvlp.inference

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws one LED the way Inference Mode and Local File Inference both show it: a box in the LED's colour with
 * its name and confidence on a tag, for example "LED 1 | 98%". An LED without a name gets a grey box. On the
 * preview it can also mark an LED's blob centroid with a small cross.
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

    /** The box edges are in the canvas's own pixels. */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, found: LedDetection) {
        val colour = colour(found.led)
        outline.color = colour
        tag.color = colour
        canvas.drawRect(left, top, right, bottom, outline)
        val label = found.label
        val tagWidth = text.measureText(label) + 2 * padding
        val tagHeight = text.textSize + 2 * padding
        // Above the box when there is room, otherwise below it; never off the side of the picture.
        val tagLeft = (left - stroke / 2f).coerceIn(0f, maxOf(0f, canvas.width - tagWidth))
        val above = top - stroke / 2f - tagHeight
        val tagTop = if (above >= 0f) above else minOf(bottom + stroke / 2f, maxOf(0f, canvas.height - tagHeight))
        canvas.drawRect(tagLeft, tagTop, tagLeft + tagWidth, tagTop + tagHeight, tag)
        canvas.drawText(label, tagLeft + padding, tagTop + padding - text.fontMetrics.ascent * 0.92f, text)
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
