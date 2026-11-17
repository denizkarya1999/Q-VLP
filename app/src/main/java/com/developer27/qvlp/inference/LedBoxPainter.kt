package com.developer27.qvlp.inference

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Draws one LED the way Inference Mode and Local File Inference both show it: a box in the LED's colour with
 * its name and confidence on a tag, for example "LED 1 | 98%".
 */
internal class LedBoxPainter(private val stroke: Float, textSize: Float) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val padding = textSize * 0.3f

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

    companion object {
        /** One colour per LED, as in the project's figures and the processed frames: red, blue, green, purple. */
        private val COLOURS = intArrayOf(0xFFD62728.toInt(), 0xFF1F77B4.toInt(), 0xFF2CA02C.toInt(), 0xFF9467BD.toInt())

        fun colour(led: Int): Int = COLOURS[(led - 1).coerceIn(0, COLOURS.size - 1)]

        /** For drawing on a picture itself: lines and letters sized to stay readable at the picture's width. */
        fun forPicture(width: Int) = LedBoxPainter(stroke = maxOf(2f, width / 420f), textSize = maxOf(14f, width / 38f))
    }
}
