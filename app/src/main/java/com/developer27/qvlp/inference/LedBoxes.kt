package com.developer27.qvlp.inference

/**
 * Turns the raw output of the Physical LED Detection Model into the boxes of the LEDs in the picture.
 *
 * The model answers with thousands of candidate boxes, and several of them lie on every LED. The app keeps at
 * most four, one for each LED of the rig, and never two boxes on the same light: the most confident candidates
 * win, and a candidate that overlaps a box already kept is dropped.
 */
internal object LedBoxes {
    const val LEDS = 4
    /** A candidate the model is less sure of than this is not an LED. */
    const val MIN_CONFIDENCE = 0.25f
    /** Boxes that share more than this are on the same light. Different LEDs never overlap. */
    const val SAME_LIGHT_OVERLAP = 0.3f
    /** The centre x, centre y, width and height of a candidate, then its one score: how sure the model is that it is an LED. */
    const val ROWS = 5

    /**
     * [output] is the model's 1 × 5 × N answer, row by row: the centre x, centre y, width and height of each of
     * the N candidates in input pixels, then its score. Boxes are kept inside [width] × [height]. The result
     * holds the most confident box first, and none of its LEDs has a name yet.
     */
    fun pick(output: FloatArray, width: Int, height: Int, minConfidence: Float = MIN_CONFIDENCE): List<LedDetection> {
        require(output.isNotEmpty() && output.size % ROWS == 0) { "The model answered with ${output.size} values, not $ROWS rows." }
        val count = output.size / ROWS
        // The few candidates worth keeping, packed as (score, index) and sorted by score.
        var candidates = LongArray(256)
        var found = 0
        val scores = (ROWS - 1) * count
        for (index in 0 until count) {
            val score = output[scores + index]
            if (score >= minConfidence) {
                if (found == candidates.size) candidates = candidates.copyOf(found * 2)
                candidates[found++] = (score.toRawBits().toLong() shl 32) or index.toLong()
            }
        }
        // Scores are between 0 and 1, so their bit patterns sort the same way the scores do.
        java.util.Arrays.sort(candidates, 0, found)
        val chosen = ArrayList<LedDetection>(LEDS)
        var position = found - 1
        while (position >= 0 && chosen.size < LEDS) {
            val packed = candidates[position--]
            val index = (packed and 0xFFFFFFFFL).toInt()
            val halfWidth = output[2 * count + index] / 2f
            val halfHeight = output[3 * count + index] / 2f
            val candidate = LedDetection(
                LedDetection.UNNAMED, Float.fromBits((packed ushr 32).toInt()),
                (output[index] - halfWidth).coerceIn(0f, width.toFloat()),
                (output[count + index] - halfHeight).coerceIn(0f, height.toFloat()),
                (output[index] + halfWidth).coerceIn(0f, width.toFloat()),
                (output[count + index] + halfHeight).coerceIn(0f, height.toFloat())
            )
            if (candidate.right <= candidate.left || candidate.bottom <= candidate.top) continue
            if (chosen.any { it.overlap(candidate) > SAME_LIGHT_OVERLAP }) continue
            chosen.add(candidate)
        }
        return chosen
    }
}
