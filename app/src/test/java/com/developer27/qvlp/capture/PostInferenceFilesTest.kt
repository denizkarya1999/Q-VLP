package com.developer27.qvlp.capture

import com.developer27.qvlp.inference.LedDetection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class PostInferenceFilesTest {
    private val leds = listOf(LedDetection(1, 0.98f, 721f, 594f, 760f, 636f), LedDetection(4, 0.5f, 1084f, 6f, 1125f, 44f))

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
        assertEquals("qvlp_led_detection_model.pt", details.getString("model"))
        assertEquals(240L, details.getLong("inference_ms"))
        val first = details.getJSONArray("leds").getJSONObject(0)
        assertEquals(0.98, first.getDouble("confidence"), 1e-6)
        assertEquals(721.0, first.getDouble("box_left_px"), 0.0)
        assertEquals(636.0, first.getDouble("box_bottom_px"), 0.0)
        assertFalse(details.getJSONArray("leds").getJSONObject(1).has("confidence"))
    }

    @Test fun theInferenceFolderIsNamedLikeTheCaptureItBelongsTo() {
        val capture = File(File("session_x", "Original_Frames"), SessionLayout.captureName(12, LocalDateTime.of(2026, 11, 16, 9, 5, 7)))
        assertEquals(File(File("session_x", "Inference_Frames"), "inference_position_frames_12_20261116_090507"),
            SessionLayout.inferenceFolder(capture))
    }
}
