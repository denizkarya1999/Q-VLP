package com.developer27.qvlp.inference

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Lies over the camera preview in Inference Mode and draws the LEDs the model found in the latest frame. */
class LedOverlayView @JvmOverloads constructor(context: Context, attributes: AttributeSet? = null) : View(context, attributes) {
    private val painter = LedBoxPainter(
        stroke = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics),
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    )
    private var detections: List<LedDetection> = emptyList()
    private var frameWidth = 0
    private var frameHeight = 0
    private var geometry = PreviewGeometry(0, false)

    /** The boxes are in pixels of a [frameWidth] × [frameHeight] camera frame. */
    internal fun show(detections: List<LedDetection>, frameWidth: Int, frameHeight: Int, geometry: PreviewGeometry) {
        this.detections = detections
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        this.geometry = geometry
        invalidate()
    }

    fun clear() {
        if (detections.isEmpty()) return
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty() || frameWidth <= 0 || frameHeight <= 0 || width == 0 || height == 0) return
        val mapping = PreviewMapping(frameWidth, frameHeight, geometry, width, height)
        for (found in detections) {
            val box = mapping.box(found)
            painter.draw(canvas, box[0], box[1], box[2], box[3], found)
        }
    }
}
