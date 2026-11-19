package com.developer27.qvlp.capture

import com.developer27.qvlp.inference.InferenceModels
import com.developer27.qvlp.inference.LedBoxes
import com.developer27.qvlp.inference.LedDetection
import com.developer27.qvlp.inference.LedRecognition
import com.developer27.qvlp.inference.LedTracks
import com.developer27.qvlp.vision.FrameCentres
import com.developer27.qvlp.vision.LedCentre
import com.developer27.qvlp.vision.LedCentroids
import com.developer27.qvlp.vision.LedDetectionSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the Physical LED Detection Model and the OOK-Based LED Recognition Model on one frame of a capture.
 * Given to a capture made in Inference Mode with Generate Post-Inference Files ticked; a Data Collection
 * capture has none. The frames of a capture are given in the order they were taken, one at a time: an LED is
 * named from its last five frames.
 */
internal fun interface FrameInference {
    /** The models that run, as ticked in Settings. The files of the capture name only these. */
    val models: InferenceModels get() = InferenceModels.BOTH

    /**
     * Whether the blob centroid of each LED is looked for inside its box (Settings → Inference → Enable CV
     * Algorithms). The processed frames and final.csv of the capture then hold blob centroids, not box centres.
     */
    val blobCentroids: Boolean get() = false

    /** [rgb] holds [width] × [height] pixels as red, green, blue bytes. It must be left as it is. */
    fun run(rgb: ByteArray, width: Int, height: Int): InferredFrame
}

/** [picture] is the frame as a PNG with a box round every LED in [detections], drawn as on the preview. */
internal class InferredFrame(val picture: ByteArray, val detections: List<LedDetection>, val milliseconds: Long)

/** The post-inference files of a capture: the LEDs the two models found and named in each of its frames. */
internal object PostInferenceFiles {
    /**
     * One entry per LED, in the form final.csv is built from. An LED's position is the centre of its box,
     * measured like every other coordinate in a session: from the centre of the top-left pixel. An LED that
     * was found but not named belongs to none of the four entries.
     */
    fun points(detections: List<LedDetection>): List<SessionCsv.LedPoint> = (1..SessionCsv.LEDS).map { led ->
        val found = detections.firstOrNull { it.led == led }
        SessionCsv.LedPoint(found != null, found?.let { (it.left + it.right) / 2.0 - 0.5 }, found?.let { (it.top + it.bottom) / 2.0 - 0.5 })
    }

    /**
     * One entry per LED for a capture made with Enable CV Algorithms: an LED's position is the centroid of the
     * blob found inside its box. [centres] holds the blob of each of [detections], in the same order. A named
     * LED whose box holds no blob has no position: a wrong centre is worse than a missing one.
     */
    fun points(detections: List<LedDetection>, centres: List<LedCentre?>): List<SessionCsv.LedPoint> = (1..SessionCsv.LEDS).map { led ->
        val centre = detections.indexOfFirst { it.led == led }.takeIf { it >= 0 }?.let(centres::getOrNull)
        SessionCsv.LedPoint(centre != null, centre?.u, centre?.v)
    }

    /**
     * The processed frame metadata of a capture made with Enable CV Algorithms. It reads like the metadata
     * beside the inference frame, with two differences: LED_k_u_px and LED_k_v_px are the blob centroid, and
     * every LED in details also says what blob was found in its box. LED_k_detected is 1 only when the LED
     * was named and its box holds a blob.
     */
    fun centroidMetadata(frameId: Int, frame: InferredFrame, centres: FrameCentres, models: InferenceModels = InferenceModels.BOTH,
                         settings: LedDetectionSettings = LedDetectionSettings()): JSONObject {
        val data = JSONObject().put("frame_id", frameId).put("frame_file", SessionLayout.frameName(frameId))
        points(frame.detections, centres.leds).forEachIndexed { index, point ->
            data.put("LED_${index + 1}_u_px", point.u?.let(::rounded) ?: JSONObject.NULL)
            data.put("LED_${index + 1}_v_px", point.v?.let(::rounded) ?: JSONObject.NULL)
            data.put("LED_${index + 1}_detected", if (point.detected) 1 else 0)
        }
        fun blob(target: JSONObject, index: Int): JSONObject {
            val centre = centres.leds.getOrNull(index) ?: return target.put("blob_found", false)
            return target.put("blob_found", true)
                .put("blob_u_px", rounded(centre.u)).put("blob_v_px", rounded(centre.v))
                .put("blob_area_px", centre.areaPx).put("peak_level", centre.peakLevel)
                .put("blob_left_px", centre.left).put("blob_top_px", centre.top)
                .put("blob_width_px", centre.width).put("blob_height_px", centre.height)
                .put("saturated", centre.saturated).put("touches_box_edge", centre.touchesBoxEdge)
        }
        return data.put("details", modelFiles(JSONObject(), models)
            .put("min_confidence", LedBoxes.MIN_CONFIDENCE.toDouble())
            .put("min_recognition_probability", LedTracks.MIN_PROBABILITY.toDouble())
            .put("inference_ms", frame.milliseconds).put("leds_found", frame.detections.size)
            .put("centre", CENTRE_BLOB)
            .put("blob_frames", LedCentroids.FRAMES).put("blob_frames_used", centres.frames)
            .put("blob_min_share_of_peak", LedCentroids.MIN_PEAK_SHARE)
            .put("threshold_above_background", settings.thresholdAboveBackground)
            .put("background_level", centres.background)
            .put("leds", JSONArray((1..SessionCsv.LEDS).map { led ->
                val index = frame.detections.indexOfFirst { it.led == led }
                JSONObject().put("led", led).put("detected", index >= 0).apply {
                    if (index >= 0) blob(box(put("confidence", rounded(frame.detections[index].confidence.toDouble())), frame.detections[index]), index)
                }
            }))
            .put("unnamed_leds", JSONArray(frame.detections.withIndex().filter { !it.value.named }.map { blob(box(JSONObject(), it.value), it.index) })))
    }

    /** How a capture's record says where its LED positions come from: the centre of the model's box, or the blob centroid. */
    const val CENTRE_BOX = "box_centre"
    const val CENTRE_BLOB = "blob_centroid"

    /**
     * The metadata file beside one inference frame. The first fields are the same as in a processed frame's
     * metadata (LED_k_u_px, LED_k_v_px, LED_k_detected), so final.csv reads both the same way.
     */
    fun metadata(frameId: Int, frame: InferredFrame, models: InferenceModels = InferenceModels.BOTH): JSONObject {
        val data = JSONObject().put("frame_id", frameId).put("frame_file", SessionLayout.frameName(frameId))
        points(frame.detections).forEachIndexed { index, point ->
            data.put("LED_${index + 1}_u_px", point.u?.let(::rounded) ?: JSONObject.NULL)
            data.put("LED_${index + 1}_v_px", point.v?.let(::rounded) ?: JSONObject.NULL)
            data.put("LED_${index + 1}_detected", if (point.detected) 1 else 0)
        }
        return data.put("details", modelFiles(JSONObject(), models)
            .put("min_confidence", LedBoxes.MIN_CONFIDENCE.toDouble())
            .put("min_recognition_probability", LedTracks.MIN_PROBABILITY.toDouble())
            .put("inference_ms", frame.milliseconds).put("leds_found", frame.detections.size)
            .put("leds", JSONArray((1..SessionCsv.LEDS).map { led ->
                val found = frame.detections.firstOrNull { it.led == led }
                JSONObject().put("led", led).put("detected", found != null).apply {
                    // confidence is how sure the recognition model is of the name; detection_confidence how sure
                    // the detection model is that there is an LED.
                    if (found != null) box(put("confidence", rounded(found.confidence.toDouble())), found)
                }
            }))
            .put("unnamed_leds", JSONArray(frame.detections.filter { !it.named }.map { box(JSONObject(), it) })))
    }

    /** The file of each model that ran. A model that was not ticked in Settings is written as null. */
    fun modelFiles(data: JSONObject, models: InferenceModels): JSONObject = data
        .put("detection_model", if (models.detection) LedRecognition.DETECTION_MODEL_FILE else JSONObject.NULL)
        .put("recognition_model", if (models.recognition) LedRecognition.RECOGNITION_MODEL_FILE else JSONObject.NULL)

    private fun box(data: JSONObject, found: LedDetection): JSONObject = data
        .put("detection_confidence", rounded(found.detected.toDouble()))
        .put("box_left_px", rounded(found.left.toDouble())).put("box_top_px", rounded(found.top.toDouble()))
        .put("box_right_px", rounded(found.right.toDouble())).put("box_bottom_px", rounded(found.bottom.toDouble()))

    private fun rounded(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}
