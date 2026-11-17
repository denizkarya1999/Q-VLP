package com.developer27.qvlp.inference

/** How the camera is mounted: the turn that makes its frames upright, and whether the preview is a mirror image. */
internal data class PreviewGeometry(val sensorOrientation: Int, val mirrored: Boolean)

/**
 * Where a point of a camera frame appears on the preview. The preview shows the frame turned upright, as a
 * mirror image for the front camera, and enlarged until it fills the view, as PreviewFill does.
 */
internal class PreviewMapping(
    private val frameWidth: Int, private val frameHeight: Int, geometry: PreviewGeometry,
    private val viewWidth: Int, private val viewHeight: Int
) {
    private val turn = ((geometry.sensorOrientation % 360) + 360) % 360
    private val mirrored = geometry.mirrored
    private val sideways = turn % 180 != 0
    private val imageWidth = if (sideways) frameHeight else frameWidth
    private val imageHeight = if (sideways) frameWidth else frameHeight
    private val zoom = maxOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)

    /** View position (x, y) of the frame pixel position ([x], [y]). */
    fun point(x: Float, y: Float): Pair<Float, Float> {
        var across: Float
        val down: Float
        when (turn) {
            90 -> { across = frameHeight - y; down = x }
            180 -> { across = frameWidth - x; down = frameHeight - y }
            270 -> { across = y; down = frameWidth - x }
            else -> { across = x; down = y }
        }
        if (mirrored) across = imageWidth - across
        return (viewWidth / 2f + (across - imageWidth / 2f) * zoom) to (viewHeight / 2f + (down - imageHeight / 2f) * zoom)
    }

    /** The view rectangle (left, top, right, bottom) that covers a frame box. */
    fun box(found: LedDetection): FloatArray {
        val (x1, y1) = point(found.left, found.top)
        val (x2, y2) = point(found.right, found.bottom)
        return floatArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }
}
