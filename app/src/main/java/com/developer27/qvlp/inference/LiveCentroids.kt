package com.developer27.qvlp.inference

import com.developer27.qvlp.vision.FrameCentres
import com.developer27.qvlp.vision.LedCentroids
import com.developer27.qvlp.vision.LedDetectionSettings

/**
 * CV Processing on the live preview: the blob centroid of each LED the models find (see [LedCentroids]).
 *
 * A blob has to be read from five frames taken one after the other, and the models are given only one frame
 * in many, a second or more apart. So the frames are counted in fives: the first four go to the blob finder
 * alone, and the fifth goes to the blob finder and to the models. The blobs are then looked for inside the
 * models' boxes, in that frame and the four just before it.
 *
 * Not thread-safe: the caller gives it the frames and asks for the blobs one at a time.
 */
internal class LiveCentroids(settings: LedDetectionSettings = LedDetectionSettings()) {
    private val centroids = LedCentroids(settings)
    private var counted = 0
    private var latest = 0L

    /**
     * The next camera frame, as tightly packed I420 or its luminance plane alone, with the time the camera
     * took it. Returns true when this is the frame the models are to run on.
     */
    fun add(frame: ByteArray, width: Int, height: Int, timestampNs: Long): Boolean {
        // After a pause, for example a capture or another camera, the frames before it are too old to count.
        if (timestampNs - latest > MAX_GAP_NS || timestampNs < latest) counted = 0
        latest = timestampNs
        centroids.add(frame, width, height)
        if (++counted < LedCentroids.FRAMES) return false
        counted = 0
        return true
    }

    /** The blob in each of [boxes], which the models found in the frame [add] returned true for. */
    fun centres(boxes: List<LedDetection>): FrameCentres = centroids.centres(boxes)

    companion object {
        /** Frames further apart than this do not belong to one group of five. A camera frame takes 33 ms at 30 fps. */
        const val MAX_GAP_NS = 500_000_000L
    }
}
