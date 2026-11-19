package com.developer27.qvlp.capture

import com.developer27.qvlp.inference.InferenceModels
import com.developer27.qvlp.inference.LedDetection
import com.developer27.qvlp.vision.FrameCentres
import com.developer27.qvlp.vision.LedCentre
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class PostInferenceFilesTest {
    // LED 1 and LED 4 were found and named; a third light was found and not named.
    private val leds = listOf(LedDetection(1, 0.98f, 721f, 594f, 760f, 636f, detected = 0.91f), LedDetection(4, 0.5f, 1084f, 6f, 1125f, 44f),
        LedDetection(LedDetection.UNNAMED, 0.6f, 300f, 310f, 340f, 350f))

    @Test fun anLedIsAtTheCentreOfItsBox() {
        val points = PostInferenceFiles.points(leds)
        assertEquals(4, points.size)
        // Box edges run along pixel borders; coordinates are counted from the centre of the top-left pixel.
        assertEquals(SessionCsv.LedPoint(true, 740.0, 614.5), points[0])
        assertEquals(SessionCsv.LedPoint(false, null, null), points[1])
        assertEquals(SessionCsv.LedPoint(false, null, null), points[2])
        assertEquals(SessionCsv.LedPoint(true, 1104.0, 24.5), points[3])
    }

    @Test fun theMetadataReadsLikeAProcessedFramesAndAddsTheBoxAndConfidence() {
        val data = JSONObject(PostInferenceFiles.metadata(7, InferredFrame(ByteArray(0), leds, 240L)).toString())
        assertEquals(7, data.getInt("frame_id"))
        assertEquals("frame_00007.png", data.getString("frame_file"))
        // final.csv reads these fields the same way it reads a processed frame.
        assertEquals(PostInferenceFiles.points(leds), SessionCsv.points(data))
        assertTrue(data.isNull("LED_2_u_px"))
        assertEquals(0, data.getInt("LED_2_detected"))
        val details = data.getJSONObject("details")
        assertEquals("qvlp_physical_led_detection_model.pt", details.getString("detection_model"))
        assertEquals("qvlp_ook_led_recognition_model.pt", details.getString("recognition_model"))
        assertEquals(0.25, details.getDouble("min_confidence"), 1e-6)
        assertEquals(0.5, details.getDouble("min_recognition_probability"), 1e-6)
        assertEquals(3, details.getInt("leds_found"))
        assertEquals(240L, details.getLong("inference_ms"))
        val first = details.getJSONArray("leds").getJSONObject(0)
        // The recognition model is 98% sure it is LED 1; the detection model was 91% sure it is an LED.
        assertEquals(0.98, first.getDouble("confidence"), 1e-6)
        assertEquals(0.91, first.getDouble("detection_confidence"), 1e-6)
        assertEquals(721.0, first.getDouble("box_left_px"), 0.0)
        assertEquals(636.0, first.getDouble("box_bottom_px"), 0.0)
        assertFalse(details.getJSONArray("leds").getJSONObject(1).has("confidence"))
        // The light without a name is listed apart and is no part of final.csv.
        val unnamed = details.getJSONArray("unnamed_leds")
        assertEquals(1, unnamed.length())
        assertEquals(0.6, unnamed.getJSONObject(0).getDouble("detection_confidence"), 1e-6)
        assertEquals(300.0, unnamed.getJSONObject(0).getDouble("box_left_px"), 0.0)
        assertFalse(unnamed.getJSONObject(0).has("confidence"))
    }

    // The blob found in each box of leds, in the same order. LED 4's box holds no blob.
    private val blobs = listOf(
        LedCentre(742.25, 611.5, 171, 213, 735, 604, 15, 16, saturated = false, touchesBoxEdge = false, frames = 5), null,
        LedCentre(318.0, 331.75, 40, 255, 315, 328, 7, 8, saturated = true, touchesBoxEdge = true, frames = 5))

    @Test fun withBlobCentroidsAnLedIsAtTheCentreOfItsBlobAndNowhereWithoutOne() {
        val points = PostInferenceFiles.points(leds, blobs)
        assertEquals(SessionCsv.LedPoint(true, 742.25, 611.5), points[0])
        assertEquals(SessionCsv.LedPoint(false, null, null), points[1])
        assertEquals(SessionCsv.LedPoint(false, null, null), points[2])
        // LED 4 was named, but its box holds no blob: no position rather than the centre of the box.
        assertEquals(SessionCsv.LedPoint(false, null, null), points[3])
        // Blobs that were never looked for are no blobs.
        assertTrue(PostInferenceFiles.points(leds, emptyList()).none { it.detected })
    }

    @Test fun theCentroidMetadataHoldsTheBlobCentroidInTheSameFieldsAndSaysWhatWasFound() {
        val frame = InferredFrame(ByteArray(0), leds, 240L)
        val data = JSONObject(PostInferenceFiles.centroidMetadata(7, frame, FrameCentres(blobs, 2.0, 5)).toString())
        assertEquals(7, data.getInt("frame_id"))
        assertEquals("frame_00007.png", data.getString("frame_file"))
        // final.csv reads these fields the same way it reads any processed frame.
        assertEquals(PostInferenceFiles.points(leds, blobs), SessionCsv.points(data))
        assertEquals(742.25, data.getDouble("LED_1_u_px"), 1e-9)
        assertEquals(611.5, data.getDouble("LED_1_v_px"), 1e-9)
        assertEquals(0, data.getInt("LED_4_detected"))
        assertTrue(data.isNull("LED_4_u_px"))
        val details = data.getJSONObject("details")
        assertEquals("blob_centroid", details.getString("centre"))
        assertEquals(5, details.getInt("blob_frames"))
        assertEquals(5, details.getInt("blob_frames_used"))
        assertEquals(0.5, details.getDouble("blob_min_share_of_peak"), 1e-9)
        assertEquals(25, details.getInt("threshold_above_background"))
        assertEquals(2.0, details.getDouble("background_level"), 1e-9)
        // Everything the inference metadata says is still there.
        assertEquals("qvlp_ook_led_recognition_model.pt", details.getString("recognition_model"))
        assertEquals(3, details.getInt("leds_found"))
        assertEquals(240L, details.getLong("inference_ms"))
        val first = details.getJSONArray("leds").getJSONObject(0)
        assertEquals(0.98, first.getDouble("confidence"), 1e-6)
        assertEquals(721.0, first.getDouble("box_left_px"), 0.0)
        assertTrue(first.getBoolean("blob_found"))
        assertEquals(742.25, first.getDouble("blob_u_px"), 1e-9)
        assertEquals(611.5, first.getDouble("blob_v_px"), 1e-9)
        assertEquals(171, first.getInt("blob_area_px"))
        assertEquals(213, first.getInt("peak_level"))
        assertEquals(listOf(735, 604, 15, 16), listOf("blob_left_px", "blob_top_px", "blob_width_px", "blob_height_px").map(first::getInt))
        assertFalse(first.getBoolean("saturated"))
        assertFalse(first.getBoolean("touches_box_edge"))
        // LED 2 was not found by the models, so nothing is said about a blob. LED 4 was, and has none.
        assertFalse(details.getJSONArray("leds").getJSONObject(1).has("blob_found"))
        val fourth = details.getJSONArray("leds").getJSONObject(3)
        assertTrue(fourth.getBoolean("detected"))
        assertFalse(fourth.getBoolean("blob_found"))
        assertFalse(fourth.has("blob_u_px"))
        assertEquals(1084.0, fourth.getDouble("box_left_px"), 0.0)
        // The light without a name has its blob too, and stays out of final.csv.
        val unnamed = details.getJSONArray("unnamed_leds").getJSONObject(0)
        assertEquals(318.0, unnamed.getDouble("blob_u_px"), 1e-9)
        assertTrue(unnamed.getBoolean("saturated"))
        assertTrue(unnamed.getBoolean("touches_box_edge"))
        // The inference frame's own metadata is not changed by any of this.
        assertEquals("box_centre", PostInferenceFiles.CENTRE_BOX)
        assertFalse(JSONObject(PostInferenceFiles.metadata(7, frame).toString()).getJSONObject("details").has("centre"))
    }

    @Test fun aModelThatWasNotTickedIsNotNamedInTheMetadata() {
        // Only the detection model ran: the LEDs were found and none has a name.
        val found = listOf(LedDetection(LedDetection.UNNAMED, 0.9f, 721f, 594f, 760f, 636f))
        val detectionOnly = InferenceModels(detection = true, recognition = false)
        val details = JSONObject(PostInferenceFiles.metadata(3, InferredFrame(ByteArray(0), found, 40L), detectionOnly).toString()).getJSONObject("details")
        assertEquals("qvlp_physical_led_detection_model.pt", details.getString("detection_model"))
        assertTrue(details.isNull("recognition_model"))
        assertEquals(1, details.getJSONArray("unnamed_leds").length())
        val none = PostInferenceFiles.modelFiles(JSONObject(), InferenceModels(detection = false, recognition = false))
        assertTrue(none.isNull("detection_model"))
        assertTrue(none.isNull("recognition_model"))
    }

    @Test fun theInferenceFolderIsNamedLikeTheCaptureItBelongsTo() {
        val capture = File(File("session_x", "Original_Frames"), SessionLayout.captureName(12, LocalDateTime.of(2026, 11, 16, 9, 5, 7)))
        assertEquals(File(File("session_x", "Inference_Frames"), "inference_position_frames_12_20261116_090507"),
            SessionLayout.inferenceFolder(capture))
    }
}
