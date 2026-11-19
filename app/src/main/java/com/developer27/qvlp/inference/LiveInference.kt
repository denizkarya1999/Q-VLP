package com.developer27.qvlp.inference

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import com.developer27.qvlp.capture.YuvFrame
import com.developer27.qvlp.vision.LedCentre
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference Mode: runs the Physical LED Detection Model and the OOK-Based LED Recognition Model on camera
 * frames while the preview is showing.
 *
 * The camera can deliver 30 frames a second and the models need longer than that for one, so a frame is taken
 * only when the models are free. Every other frame is skipped: the boxes always belong to a recent frame, and
 * the five frames an LED is named from are the last five that were taken. [models] says which of the two
 * models run.
 *
 * With [blobCentroids] (Settings → Inference → Enable CV Algorithms) the blob centroid of each LED is found too,
 * inside the box the models drew. It is read from the frame the models ran on and the four camera frames just
 * before it (see [LiveCentroids]), so those four frames are copied as well.
 */
internal class LiveInference(context: Context, private val models: InferenceModels, blobCentroids: Boolean = false,
                             private val deliver: (Result) -> Unit) {
    /**
     * [error] is set, and nothing else, when the models could not run. With blob centroids, [centres] holds the
     * blob of each of [detections], in the same order, and null where a box holds none; otherwise it is empty.
     */
    data class Result(val detections: List<LedDetection>, val frameWidth: Int, val frameHeight: Int,
                      val milliseconds: Long, val error: String?, val centres: List<LedCentre?> = emptyList())

    private val app = context.applicationContext
    private val busy = AtomicBoolean(false)
    private var frame = ByteArray(0)
    // The LEDs of this preview, followed from frame to frame. Used on the models' thread only.
    private val tracks = LedTracks()
    // Given its frames on the camera thread and asked for the blobs on the models' thread, never both at once:
    // busy is set while either of them has it.
    private val centroids = if (blobCentroids) LiveCentroids() else null
    @Volatile private var active = true

    /** Called on the camera thread for every frame. The caller closes [image]. */
    fun offer(image: Image) {
        if (!active || image.format != ImageFormat.YUV_420_888 || !busy.compareAndSet(false, true)) return
        val width = image.width
        val height = image.height
        try {
            val size = YuvFrame.byteCount(width, height).toInt()
            if (frame.size < size) frame = ByteArray(size)
            val planes = image.planes
            var out = 0
            fun copy(index: Int) {
                out = YuvFrame.copyPlane(planes[index].buffer.duplicate(), planes[index].rowStride, planes[index].pixelStride,
                    if (index == 0) width else (width + 1) / 2, if (index == 0) height else (height + 1) / 2, frame, out)
            }
            // The luminance plane comes first, and it is all the blob finder reads. The four frames before the
            // models' frame go to the blob finder only, so nothing more of them is copied.
            copy(0)
            if (centroids != null && !centroids.add(frame, width, height, image.timestamp)) {
                busy.set(false)
                return
            }
            copy(1)
            copy(2)
        } catch (error: Exception) {
            busy.set(false)
            return
        }
        LedRecognition.execute {
            try {
                val found = LedRecognition.detectFrame(app, frame, width, height, tracks, models)
                val centres = centroids?.centres(found.detections)?.leds ?: emptyList()
                if (active) deliver(Result(found.detections, width, height, found.milliseconds, null, centres))
                busy.set(false)
            } catch (error: Throwable) {
                // A model that cannot run will not run on the next frame either: stop instead of failing 30 times a second.
                active = false
                deliver(Result(emptyList(), width, height, 0L, error.message ?: error.javaClass.simpleName))
            }
        }
    }

    /** No result is delivered after this. */
    fun stop() {
        active = false
    }
}
