package com.developer27.qvlp.inference

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One LED in a picture. The Physical LED Detection Model found it and is [detected] sure that it is an LED;
 * the OOK-Based LED Recognition Model then named it: [led] is 1 to 4, or [UNNAMED] for an LED it could not
 * name. [confidence] is the number shown beside the box: how sure the recognition model is of the name, or,
 * for an LED without a name, [detected]. The box edges are in pixels of the picture the detection belongs to,
 * measured from its top-left corner.
 */
internal data class LedDetection(
    val led: Int, val confidence: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val detected: Float = confidence
) {
    val named: Boolean get() = led != UNNAMED

    /** "LED 1" to "LED 4", or "LED Detected" for an LED without a name. */
    val name: String get() = if (named) "LED $led" else "LED Detected"

    /** What is written beside the box, for example "LED 1 | 98%", or "LED Detected | 93%" for an LED without a name. */
    val label: String get() = "$name | ${(confidence * 100f).roundToInt().coerceIn(0, 100)}%"

    /** The shared part of two boxes as a fraction of the area they cover together: 0 apart, 1 identical. */
    fun overlap(other: LedDetection): Float {
        val width = min(right, other.right) - max(left, other.left)
        val height = min(bottom, other.bottom) - max(top, other.top)
        if (width <= 0f || height <= 0f) return 0f
        val shared = width * height
        return shared / ((right - left) * (bottom - top) + (other.right - other.left) * (other.bottom - other.top) - shared)
    }

    companion object {
        /** The [led] of an LED that was found but not named. */
        const val UNNAMED = 0
    }
}
