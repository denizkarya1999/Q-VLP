package com.developer27.qvlp.inference

/**
 * Turns the raw output of the LED Recognition Model into the boxes that are shown.
 *
 * The model answers with thousands of candidate boxes, and several of them lie on every LED. The app shows at
 * most one box per LED class, so at most four, and never two boxes on the same light: the most confident
 * candidate of each class wins, and a light already given to one class is not given to another.
 */
internal object LedBoxes {
    const val LEDS = 4
    /** A candidate the model is less sure of than this is not shown. */
    const val MIN_CONFIDENCE = 0.25f
    /** Boxes that share more than this are on the same light. Different LEDs never overlap. */
    const val SAME_LIGHT_OVERLAP = 0.3f
    private const val ROWS = 4 + LEDS

    /**
     * [output] is the model's 1 × 8 × N answer, row by row: the centre x, centre y, width and height of each of
     * the N candidates in input pixels, then its score for LED 1 to LED 4. Boxes are kept inside
     * [width] × [height]. The result is ordered by LED number.
     */
    fun pick(output: FloatArray, width: Int, height: Int, minConfidence: Float = MIN_CONFIDENCE): List<LedDetection> {
        require(output.isNotEmpty() && output.size % ROWS == 0) { "The model answered with ${output.size} values, not 8 rows." }
        val count = output.size / ROWS
        // The few candidates worth showing, packed as (score, class, index) and sorted by score, best first.
        var candidates = LongArray(256)
        var found = 0
        for (led in 0 until LEDS) {
            val row = (4 + led) * count
            for (index in 0 until count) {
                val score = output[row + index]
                if (score >= minConfidence) {
                    if (found == candidates.size) candidates = candidates.copyOf(found * 2)
                    candidates[found++] = (score.toRawBits().toLong() shl 32) or (led.toLong() shl 28) or index.toLong()
                }
            }
        }
        // Scores are between 0 and 1, so their bit patterns sort the same way the scores do.
        java.util.Arrays.sort(candidates, 0, found)
        val chosen = arrayOfNulls<LedDetection>(LEDS)
        var remaining = LEDS
        var position = found - 1
        while (position >= 0 && remaining > 0) {
            val packed = candidates[position--]
            val led = ((packed shr 28) and 0xF).toInt()
            if (chosen[led] != null) continue
            val index = (packed and 0xFFFFFFF).toInt()
            val halfWidth = output[2 * count + index] / 2f
            val halfHeight = output[3 * count + index] / 2f
            val candidate = LedDetection(
                led + 1, Float.fromBits((packed ushr 32).toInt()),
                (output[index] - halfWidth).coerceIn(0f, width.toFloat()),
                (output[count + index] - halfHeight).coerceIn(0f, height.toFloat()),
                (output[index] + halfWidth).coerceIn(0f, width.toFloat()),
                (output[count + index] + halfHeight).coerceIn(0f, height.toFloat())
            )
            if (candidate.right <= candidate.left || candidate.bottom <= candidate.top) continue
            if (chosen.any { it != null && it.overlap(candidate) > SAME_LIGHT_OVERLAP }) continue
            chosen[led] = candidate
            remaining--
        }
        return chosen.filterNotNull()
    }
}
