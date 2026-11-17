package com.developer27.qvlp.inference

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One LED found by the LED Recognition Model. [led] is 1 to 4. The box edges are in pixels of the picture
 * the detection belongs to, measured from its top-left corner.
 */
internal data class LedDetection(
    val led: Int, val confidence: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float
) {
    /** What is written beside the box, for example "LED 1 | 98%". */
    val label: String get() = "LED $led | ${(confidence * 100f).roundToInt().coerceIn(0, 100)}%"

    /** The shared part of two boxes as a fraction of the area they cover together: 0 apart, 1 identical. */
    fun overlap(other: LedDetection): Float {
        val width = min(right, other.right) - max(left, other.left)
        val height = min(bottom, other.bottom) - max(top, other.top)
        if (width <= 0f || height <= 0f) return 0f
        val shared = width * height
        return shared / ((right - left) * (bottom - top) + (other.right - other.left) * (other.bottom - other.top) - shared)
    }
}
