package com.developer27.qvlp.capture

import com.developer27.qvlp.inference.LedBoxes
import com.developer27.qvlp.inference.LedDetection
import com.developer27.qvlp.inference.LedRecognition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the LED Recognition Model on one frame of a capture. Given to a capture made in Inference Mode with
 * Generate Post-Inference Files ticked; a Data Collection capture has none.
 */
internal fun interface FrameInference {
    /** [rgb] holds [width] × [height] pixels as red, green, blue bytes. It must be left as it is. */
    fun run(rgb: ByteArray, width: Int, height: Int): InferredFrame
}

/** [picture] is the frame as a PNG with a box round every LED in [detections], drawn as on the preview. */
internal class InferredFrame(val picture: ByteArray, val detections: List<LedDetection>, val milliseconds: Long)

/** The post-inference files of a capture: what the LED Recognition Model found in each of its frames. */
internal object PostInferenceFiles {
    /**
     * One entry per LED, in the form final.csv is built from. An LED's position is the centre of its box,
     * measured like every other coordinate in a session: from the centre of the top-left pixel.
     */
    fun points(detections: List<LedDetection>): List<SessionCsv.LedPoint> = (1..SessionCsv.LEDS).map { led ->
        val found = detections.firstOrNull { it.led == led }
        SessionCsv.LedPoint(found != null, found?.let { (it.left + it.right) / 2.0 - 0.5 }, found?.let { (it.top + it.bottom) / 2.0 - 0.5 })
    }

    /**
     * The metadata file beside one inference frame. The first fields are the same as in a processed frame's
     * metadata (LED_k_u_px, LED_k_v_px, LED_k_detected), so final.csv reads both the same way.
     */
    fun metadata(frameId: Int, frame: InferredFrame): JSONObject {
        val data = JSONObject().put("frame_id", frameId).put("frame_file", SessionLayout.frameName(frameId))
        points(frame.detections).forEachIndexed { index, point ->
            data.put("LED_${index + 1}_u_px", point.u?.let(::rounded) ?: JSONObject.NULL)
            data.put("LED_${index + 1}_v_px", point.v?.let(::rounded) ?: JSONObject.NULL)
            data.put("LED_${index + 1}_detected", if (point.detected) 1 else 0)
        }
        return data.put("details", JSONObject().put("model", LedRecognition.MODEL_FILE)
            .put("min_confidence", LedBoxes.MIN_CONFIDENCE.toDouble()).put("inference_ms", frame.milliseconds)
            .put("leds", JSONArray((1..SessionCsv.LEDS).map { led ->
                val found = frame.detections.firstOrNull { it.led == led }
                JSONObject().put("led", led).put("detected", found != null).apply {
                    if (found != null) put("confidence", rounded(found.confidence.toDouble()))
                        .put("box_left_px", rounded(found.left.toDouble())).put("box_top_px", rounded(found.top.toDouble()))
                        .put("box_right_px", rounded(found.right.toDouble())).put("box_bottom_px", rounded(found.bottom.toDouble()))
                }
            })))
    }

    private fun rounded(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}
