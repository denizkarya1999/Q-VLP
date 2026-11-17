package com.developer27.qvlp.inference

/**
 * Follows each LED from one frame to the next, so that the OOK-Based LED Recognition Model can be given the
 * sequence of five frames it was trained on, and gives each LED its name.
 *
 * An LED in a new frame is the same LED as in the frame before when their boxes overlap. Each LED keeps its
 * last five crops. The recognition model names an LED from its first frame, as five copies of that frame, and
 * names it again from every five new frames; in between, the LED keeps the name it has. One object follows
 * one series of frames: the preview, one capture, or a single picture.
 */
internal class LedTracks {
    private class Track(var box: LedDetection) {
        val crops = ArrayDeque<ByteArray>(LedCrop.FRAMES)
        var frames = 0
        var missed = 0
        var probabilities: FloatArray? = null
    }

    private val tracks = ArrayList<Track>()

    /**
     * [found] holds the LEDs the detection model found in the next frame, without names. [crop] cuts one of
     * them out of that frame (see [LedCrop]). [recognise] runs the recognition model on the crop sequences it
     * is given, each at most five crops with the earliest first, and answers with the probabilities of LED 1
     * to LED 4 for each. Returns the same LEDs with their names: LED 1 to LED 4 first, then any without a name.
     */
    fun update(found: List<LedDetection>, crop: (LedDetection) -> ByteArray,
               recognise: (List<List<ByteArray>>) -> List<FloatArray>): List<LedDetection> {
        val current = follow(found)
        current.forEach { track ->
            if (track.crops.size == LedCrop.FRAMES) track.crops.removeFirst()
            track.crops.addLast(crop(track.box))
            track.frames++
        }
        val due = current.filter { it.frames == 1 || it.frames % LedCrop.FRAMES == 0 }
        if (due.isNotEmpty()) {
            val answers = recognise(due.map { it.crops.toList() })
            check(answers.size == due.size) { "The recognition model answered for ${answers.size} LEDs, not ${due.size}." }
            due.forEachIndexed { index, track -> track.probabilities = answers[index] }
        }
        return name(current.map { it.box }, current.map { it.probabilities })
    }

    /** The track of each LED in [found], in the same order. An LED no track overlaps starts a new one. */
    private fun follow(found: List<LedDetection>): List<Track> {
        val matched = arrayOfNulls<Track>(found.size)
        val taken = HashSet<Track>()
        // The pairs that overlap most are matched first.
        val pairs = ArrayList<Triple<Float, Int, Track>>()
        found.forEachIndexed { index, box ->
            tracks.forEach { track ->
                val overlap = track.box.overlap(box)
                if (overlap > LedBoxes.SAME_LIGHT_OVERLAP) pairs.add(Triple(overlap, index, track))
            }
        }
        pairs.sortByDescending { it.first }
        for ((_, index, track) in pairs) {
            if (matched[index] != null || track in taken) continue
            matched[index] = track
            taken.add(track)
        }
        // An LED that is missed in a few frames keeps its crops; one that stays away is forgotten.
        tracks.removeAll { track -> track !in taken && ++track.missed > MAX_MISSED }
        return found.mapIndexed { index, box ->
            matched[index]?.also { it.box = box; it.missed = 0 } ?: Track(box).also(tracks::add)
        }
    }

    companion object {
        /** An LED the recognition model is less sure of than this keeps no name. */
        const val MIN_PROBABILITY = 0.5f
        /** A track is kept through this many frames in which its LED was not found. */
        const val MAX_MISSED = 5

        /**
         * Gives each of [boxes] the name its [probabilities] point to: the LED with the highest probability,
         * when that is at least [MIN_PROBABILITY]. No two boxes get the same name: the one the model is surest
         * of keeps it and the other stays without a name. The result holds LED 1 to LED 4 in that order, then
         * the LEDs without a name, the most confidently detected first.
         */
        fun name(boxes: List<LedDetection>, probabilities: List<FloatArray?>): List<LedDetection> {
            require(boxes.size == probabilities.size) { "Each LED needs its probabilities." }
            val best = probabilities.map { answer ->
                if (answer == null || answer.isEmpty()) -1 else answer.indices.maxByOrNull { answer[it] }!!
            }
            val named = arrayOfNulls<LedDetection>(boxes.size)
            val used = HashSet<Int>()
            boxes.indices.filter { best[it] >= 0 && probabilities[it]!![best[it]] >= MIN_PROBABILITY }
                .sortedByDescending { probabilities[it]!![best[it]] }
                .forEach { index ->
                    if (used.add(best[index]))
                        named[index] = boxes[index].copy(led = best[index] + 1, confidence = probabilities[index]!![best[index]])
                }
            val unnamed = boxes.indices.filter { named[it] == null }
                .map { boxes[it].copy(led = LedDetection.UNNAMED, confidence = boxes[it].detected) }
                .sortedByDescending { it.detected }
            return named.filterNotNull().sortedBy { it.led } + unnamed
        }
    }
}
