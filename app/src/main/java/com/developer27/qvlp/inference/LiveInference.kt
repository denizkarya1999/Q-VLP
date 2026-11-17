package com.developer27.qvlp.inference

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import com.developer27.qvlp.capture.YuvFrame
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference Mode: runs the LED Recognition Model on camera frames while the preview is showing.
 *
 * The camera delivers 30 frames a second and the model needs longer than that for one, so a frame is taken
 * only when the model is free. Every other frame is skipped: the boxes always belong to a recent frame.
 */
internal class LiveInference(context: Context, private val deliver: (Result) -> Unit) {
    /** [error] is set, and nothing else, when the model could not run. */
    data class Result(val detections: List<LedDetection>, val frameWidth: Int, val frameHeight: Int,
                      val milliseconds: Long, val error: String?)

    private val app = context.applicationContext
    private val busy = AtomicBoolean(false)
    private var frame = ByteArray(0)
    @Volatile private var active = true

    /** Called on the camera thread for every frame. The caller closes [image]. */
    fun offer(image: Image) {
        if (!active || image.format != ImageFormat.YUV_420_888 || !busy.compareAndSet(false, true)) return
        val width = image.width
        val height = image.height
        try {
            val size = YuvFrame.byteCount(width, height).toInt()
            if (frame.size < size) frame = ByteArray(size)
            var out = 0
            image.planes.forEachIndexed { index, plane ->
                out = YuvFrame.copyPlane(plane.buffer.duplicate(), plane.rowStride, plane.pixelStride,
                    if (index == 0) width else (width + 1) / 2, if (index == 0) height else (height + 1) / 2, frame, out)
            }
        } catch (error: Exception) {
            busy.set(false)
            return
        }
        LedRecognition.execute {
            try {
                val found = LedRecognition.detectFrame(app, frame, width, height)
                if (active) deliver(Result(found.detections, width, height, found.milliseconds, null))
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
