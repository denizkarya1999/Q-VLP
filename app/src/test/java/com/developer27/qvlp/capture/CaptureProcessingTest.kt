package com.developer27.qvlp.capture

import com.developer27.qvlp.vision.FramePng
import com.developer27.qvlp.vision.LedDetectionSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin

class CaptureProcessingTest {
    private val width = 640
    private val height = 480
    private val rowTime = CaptureProcessing.RowTime(30.0, "test")

    /** A raw camera frame: a dark ceiling with one round light whose rows blink at 1800 Hz. */
    private fun rawFrame(): ByteArray {
        val frame = ByteArray(FramePng.i420Size(width, height)) { 128.toByte() } // Neutral colour planes.
        val periodRows = 1.0 / (1800.0 * 30e-6)
        for (row in 0 until height) for (column in 0 until width) {
            val inside = (column - 320.0) * (column - 320.0) + (row - 240.0) * (row - 240.0) <= 60.0 * 60.0
            frame[row * width + column] = (if (inside) 130 + 70 * sin(2 * PI * row / periodRows) else 20.0).toInt().toByte()
        }
        return frame
    }

    @Test fun aCaptureIsWrittenIntoOriginalFramesAndProcessedFrames() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), SessionLayout.captureName(4, LocalDateTime.of(2026, 11, 14, 19, 0, 0)))
            assertTrue(File(capture, SessionLayout.FRAMES).mkdirs())
            val frames = (0..1).map { JSONObject().put("frame_id", it).put("width", width).put("height", height) }
            frames.forEach { CaptureProcessing.rawFile(capture, it.getInt("frame_id")).writeBytes(rawFrame()) }

            val result = CaptureProcessing.process(capture, frames, rowTime, LedDetectionSettings(),
                { JSONObject().put("frame_id", it.getInt("frame_id")).put("requested", JSONObject().put("iso", 50)) }) { _, _ -> }

            assertEquals(setOf(0, 1), result.saved)
            val original = "Original_Frames/position_4_20261114_190000"
            val processed = "Processed_Frames/processed_position_frames_4_20261114_190000"
            // Exactly the session's files, each in its place. The raw frames are gone.
            assertEquals(listOf(
                "$original/frame_and_metadata_mapping.csv",
                "$original/frames/frame_00000.png", "$original/frames/frame_00001.png",
                "$original/metadata/frame_00000_metadata.json", "$original/metadata/frame_00001_metadata.json",
                "$processed/processed_frame_and_metadata_mapping.csv",
                "$processed/processed_frame_metadata/frame_00000_metadata.json",
                "$processed/processed_frame_metadata/frame_00001_metadata.json",
                "$processed/processed_frames/frame_00000.png", "$processed/processed_frames/frame_00001.png",
            ), session.walkTopDown().filter { it.isFile }.map { it.relativeTo(session).invariantSeparatorsPath }.sorted().toList())

            // The metadata beside the original frame holds the camera settings only.
            val camera = JSONObject(File(session, "$original/metadata/frame_00000_metadata.json").readText())
            assertEquals(50, camera.getJSONObject("requested").getInt("iso"))
            assertFalse(camera.has("LED_1_detected"))

            // The processed metadata holds the LED found in the frame; the processed picture has it drawn on.
            val leds = JSONObject(File(session, "$processed/processed_frame_metadata/frame_00000_metadata.json").readText())
            assertEquals(1, leds.getInt("LED_1_detected"))
            assertEquals(320.0, leds.getDouble("LED_1_u_px"), 1.0)
            assertEquals(240.0, leds.getDouble("LED_1_v_px"), 1.0)
            for (led in 2..4) {
                assertEquals(0, leds.getInt("LED_${led}_detected"))
                assertTrue(leds.isNull("LED_${led}_u_px"))
            }
            assertFalse(File(session, "$original/frames/frame_00000.png").readBytes()
                .contentEquals(File(session, "$processed/processed_frames/frame_00000.png").readBytes()))

            // final.csv reads the processed metadata through the processed mapping file.
            val mapping = "frame_file,metadata_file\nframe_00000.png,frame_00000_metadata.json\nframe_00001.png,frame_00001_metadata.json\n"
            assertEquals(mapping, File(session, "$original/frame_and_metadata_mapping.csv").readText())
            assertEquals(mapping, File(session, "$processed/processed_frame_and_metadata_mapping.csv").readText())
            val fingerprint = SessionCsv.fingerprint(SessionCsv.readProcessed(SessionLayout.processedFolder(capture)))
            assertTrue(SessionCsv.finalRow("4", fingerprint).startsWith("4,3"))
            assertTrue(SessionCsv.finalRow("4", fingerprint).endsWith(",1,,,0,,,0,,,0"))
        } finally { session.deleteRecursively() }
    }
}
