package com.developer27.qvlp.capture

import com.developer27.qvlp.capture.SessionCsv.LedPoint
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionCsvFinalTest {
    private fun seen(u: Double, v: Double) = LedPoint(true, u, v)
    private val missed = LedPoint(false, null, null)
    private fun frame(vararg leds: LedPoint) = List(SessionCsv.LEDS) { leds.getOrElse(it) { missed } }

    @Test fun finalCsvHasThePositionAndThreeColumnsPerLed() {
        assertEquals("position_id,LED_1_u_px,LED_1_v_px,LED_1_detected,LED_2_u_px,LED_2_v_px,LED_2_detected," +
            "LED_3_u_px,LED_3_v_px,LED_3_detected,LED_4_u_px,LED_4_v_px,LED_4_detected", SessionCsv.finalHeader)
    }

    @Test fun writesTheMedianCentreOfTheValidFramesForEachDetectedLed() {
        val frames = listOf(frame(seen(451.2, 264.9)), frame(seen(451.9, 264.1)), frame(seen(451.6, 265.3)))
        assertEquals("17,451.600,264.900,1,,,0,,,0,,,0", SessionCsv.finalRow("17", SessionCsv.fingerprint(frames)))
    }

    @Test fun anLedNeedsSeventyPercentOfTheFramesToCount() {
        fun frames(valid: Int) = List(valid) { frame(seen(100.0, 200.0)) } + List(10 - valid) { frame() }
        assertTrue(SessionCsv.fingerprint(frames(7))[0].detected)
        assertFalse(SessionCsv.fingerprint(frames(6))[0].detected)
        // A doubtful LED is dropped with empty coordinates, never a guess.
        assertTrue(SessionCsv.finalRow("3", SessionCsv.fingerprint(frames(6))).startsWith("3,,,0,"))
        assertTrue(SessionCsv.finalRow("3", SessionCsv.fingerprint(frames(7))).startsWith("3,100.000,200.000,1,"))
    }

    @Test fun usesTheMiddlePairForAnEvenNumberOfValidFrames() {
        val frames = listOf(10.0, 20.0, 40.0, 30.0).map { frame(seen(it, 5.0)) }
        assertEquals(25.0, SessionCsv.fingerprint(frames)[0].u!!, 1e-9)
    }

    @Test fun aCaptureThatWasNeverProcessedKeepsEmptyLedCellsRatherThanZero() {
        assertEquals("4,,,,,,,,,,,,", SessionCsv.finalRow("4", SessionCsv.fingerprint(emptyList())))
    }

    @Test fun readsEachLedFromAProcessedFramesMetadata() {
        val points = SessionCsv.points(JSONObject("""{"frame_id": 0, "LED_1_u_px": 451.613, "LED_1_v_px": 264.871,
            "LED_1_detected": 1, "LED_2_u_px": null, "LED_2_v_px": null, "LED_2_detected": 0, "LED_4_detected": 1}"""))
        assertEquals(seen(451.613, 264.871), points[0])
        assertEquals(missed, points[1])
        assertEquals(missed, points[2])
        // Marked detected but without coordinates: it cannot contribute a centre.
        assertEquals(LedPoint(true, null, null), points[3])
        assertFalse(SessionCsv.fingerprint(listOf(points))[3].detected)
    }

    @Test fun buildsAPositionsFingerprintFromTheFilesItsMappingLists() {
        val processed = Files.createTempDirectory("processed_position_frames_3_").toFile()
        try {
            val folder = File(processed, SessionLayout.PROCESSED_METADATA).apply { mkdirs() }
            listOf(100.0, 102.0, 104.0).forEachIndexed { index, u ->
                File(folder, SessionLayout.metadataName(index)).writeText(JSONObject().put("LED_2_u_px", u)
                    .put("LED_2_v_px", 50.0).put("LED_2_detected", 1).put("LED_1_detected", 0).toString())
            }
            // Not listed in the mapping, so it is not part of the capture.
            File(folder, SessionLayout.metadataName(9)).writeText(JSONObject().put("LED_2_u_px", 999.0)
                .put("LED_2_v_px", 999.0).put("LED_2_detected", 1).toString())
            assertTrue(SessionCsv.readProcessed(processed).isEmpty())
            File(processed, SessionLayout.PROCESSED_MAPPING).writeText(SessionLayout.mapping(listOf(0, 1, 2)))
            val frames = SessionCsv.readProcessed(processed)
            assertEquals(3, frames.size)
            assertEquals("3,,,0,102.000,50.000,1,,,0,,,0", SessionCsv.finalRow("3", SessionCsv.fingerprint(frames)))
        } finally { processed.deleteRecursively() }
    }

    @Test fun blobCentroidsAreReadFromTheProcessedMetadataOfTheFramesTheModelsRanOn() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), "position_3_20261119_100000")
            val processed = SessionLayout.processedFolder(capture)
            val inference = SessionLayout.inferenceFolder(capture)
            val centroids = File(processed, SessionLayout.PROCESSED_METADATA).apply { mkdirs() }
            val boxes = File(inference, SessionLayout.INFERENCE_METADATA).apply { mkdirs() }
            listOf(100.2, 100.4, 100.6, 250.0).forEachIndexed { index, u ->
                // The centre of the model's box beside the inference frame, the blob centroid beside the processed one.
                File(boxes, SessionLayout.metadataName(index)).writeText(JSONObject().put("LED_2_u_px", 103.5)
                    .put("LED_2_v_px", 52.5).put("LED_2_detected", 1).toString())
                File(centroids, SessionLayout.metadataName(index)).writeText(JSONObject().put("LED_2_u_px", u)
                    .put("LED_2_v_px", 50.0).put("LED_2_detected", 1).toString())
            }
            // The models could not run on the fourth frame: LED detection wrote its processed metadata, and it does not count.
            File(processed, SessionLayout.PROCESSED_MAPPING).writeText(SessionLayout.mapping(listOf(0, 1, 2, 3)))
            File(inference, SessionLayout.INFERENCE_MAPPING).writeText(SessionLayout.mapping(listOf(0, 1, 2)))
            val frames = SessionCsv.readCentroids(processed, inference)
            assertEquals(3, frames.size)
            assertEquals("3,,,0,100.400,50.000,1,,,0,,,0", SessionCsv.finalRow("3", SessionCsv.fingerprint(frames)))
            assertEquals("3,,,0,103.500,52.500,1,,,0,,,0", SessionCsv.finalRow("3", SessionCsv.fingerprint(SessionCsv.readInference(inference))))
        } finally { session.deleteRecursively() }
    }
}
