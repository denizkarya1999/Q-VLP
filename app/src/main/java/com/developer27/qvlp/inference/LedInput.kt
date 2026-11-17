package com.developer27.qvlp.inference

/**
 * The picture the LED Recognition Model takes: 1280 × 960 pixels, the size of the camera frames it was trained
 * on, as red, green and blue planes with values from 0 to 1.
 */
internal object LedInput {
    const val WIDTH = 1280
    const val HEIGHT = 960
    const val PLANE = WIDTH * HEIGHT
    const val SIZE = 3 * PLANE
    /** The grey that training put around a picture that did not fill the input. */
    const val PADDING_GREY = 114
    private val LEVEL = FloatArray(256) { it / 255f }

    /** [rgb] holds 1280 × 960 pixels as red, green, blue bytes. */
    fun fromRgb(rgb: ByteArray, target: FloatArray) {
        require(rgb.size >= SIZE && target.size >= SIZE) { "The picture is smaller than the model input." }
        val level = LEVEL
        var from = 0
        for (pixel in 0 until PLANE) {
            target[pixel] = level[rgb[from].toInt() and 0xFF]
            target[PLANE + pixel] = level[rgb[from + 1].toInt() and 0xFF]
            target[2 * PLANE + pixel] = level[rgb[from + 2].toInt() and 0xFF]
            from += 3
        }
    }

    /** [argb] holds 1280 × 960 pixels the way an Android bitmap returns them. */
    fun fromPixels(argb: IntArray, target: FloatArray) {
        require(argb.size >= PLANE && target.size >= SIZE) { "The picture is smaller than the model input." }
        val level = LEVEL
        for (pixel in 0 until PLANE) {
            val colour = argb[pixel]
            target[pixel] = level[(colour shr 16) and 0xFF]
            target[PLANE + pixel] = level[(colour shr 8) and 0xFF]
            target[2 * PLANE + pixel] = level[colour and 0xFF]
        }
    }
}

/**
 * How a picture of another size is fitted into the model input: made as large as fits without changing its
 * proportions, centred, with grey around it. A 1280 × 960 picture is used as it is.
 */
internal data class Letterbox(val scale: Float, val offsetX: Float, val offsetY: Float) {
    val unchanged: Boolean get() = scale == 1f && offsetX == 0f && offsetY == 0f

    /** A box found in the model input, moved back onto the picture it came from. */
    fun toSource(found: LedDetection): LedDetection = found.copy(
        left = (found.left - offsetX) / scale, top = (found.top - offsetY) / scale,
        right = (found.right - offsetX) / scale, bottom = (found.bottom - offsetY) / scale
    )

    companion object {
        fun fit(sourceWidth: Int, sourceHeight: Int): Letterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "The picture is empty." }
            val scale = minOf(LedInput.WIDTH.toFloat() / sourceWidth, LedInput.HEIGHT.toFloat() / sourceHeight)
            return Letterbox(scale, (LedInput.WIDTH - sourceWidth * scale) / 2f, (LedInput.HEIGHT - sourceHeight * scale) / 2f)
        }
    }
}
