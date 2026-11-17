package com.developer27.qvlp.capture

import com.developer27.qvlp.inference.LedDetection
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

    /** A raw camera frame: a dark ceiling of brightness [ceiling] with one round light whose rows blink at 1800 Hz. */
    private fun rawFrame(ceiling: Int = 20): ByteArray {
        val frame = ByteArray(FramePng.i420Size(width, height)) { 128.toByte() } // Neutral colour planes.
        val periodRows = 1.0 / (1800.0 * 30e-6)
        for (row in 0 until height) for (column in 0 until width) {
            val inside = (column - 320.0) * (column - 320.0) + (row - 240.0) * (row - 240.0) <= 60.0 * 60.0
            frame[row * width + column] = (if (inside) 130 + 70 * sin(2 * PI * row / periodRows) else ceiling.toDouble()).toInt().toByte()
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
            // A Data Collection capture has no post-inference files.
            assertFalse(File(session, SessionLayout.INFERENCE).exists())
        } finally { session.deleteRecursively() }
    }

    @Test fun anInferenceModeCaptureAlsoGetsItsPostInferenceFiles() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), SessionLayout.captureName(4, LocalDateTime.of(2026, 11, 16, 21, 0, 0)))
            assertTrue(File(capture, SessionLayout.FRAMES).mkdirs())
            val frames = (0..2).map { JSONObject().put("frame_id", it).put("width", width).put("height", height) }
            frames.forEach { CaptureProcessing.rawFile(capture, it.getInt("frame_id")).writeBytes(rawFrame()) }
            // Stands in for the two LED models: they find LED 2 in every frame.
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val untouched = java.util.concurrent.atomic.AtomicBoolean(true)
            val model = FrameInference { rgb, _, _ ->
                calls.incrementAndGet()
                // The model is given the frame before LED detection draws on it: the dark ceiling is still dark.
                if (rgb[0].toInt() != 20) untouched.set(false)
                InferredFrame(byteArrayOf(1, 2, 3), listOf(LedDetection(2, 0.97f, 300f, 220f, 340f, 260f)), 12L)
            }

            val result = CaptureProcessing.process(capture, frames, rowTime, LedDetectionSettings(),
                { JSONObject().put("frame_id", it.getInt("frame_id")) }, model) { _, _ -> }

            assertEquals(setOf(0, 1, 2), result.saved)
            assertEquals(3, calls.get())
            assertTrue(untouched.get())
            assertEquals(setOf(0, 1, 2), result.inferred.keys)
            val inference = "Inference_Frames/inference_position_frames_4_20261116_210000"
            val files = session.walkTopDown().filter { it.isFile }.map { it.relativeTo(session).invariantSeparatorsPath }.sorted().toList()
            assertEquals(listOf(
                "$inference/inference_frame_and_metadata_mapping.csv",
                "$inference/inference_frame_metadata/frame_00000_metadata.json",
                "$inference/inference_frame_metadata/frame_00001_metadata.json",
                "$inference/inference_frame_metadata/frame_00002_metadata.json",
                "$inference/inference_frames/frame_00000.png", "$inference/inference_frames/frame_00001.png",
                "$inference/inference_frames/frame_00002.png",
            ), files.filter { it.startsWith("Inference_Frames/") })
            // The original and processed frames are written exactly as in Data Collection Mode.
            assertEquals(7, files.count { it.startsWith("Original_Frames/") })
            assertEquals(7, files.count { it.startsWith("Processed_Frames/") })
            assertArrayEquals(byteArrayOf(1, 2, 3), File(session, "$inference/inference_frames/frame_00000.png").readBytes())

            // The inference metadata gives the centre of the model's box, and final.csv is built from it.
            val leds = JSONObject(File(session, "$inference/inference_frame_metadata/frame_00002_metadata.json").readText())
            assertEquals(1, leds.getInt("LED_2_detected"))
            assertEquals(319.5, leds.getDouble("LED_2_u_px"), 1e-9)
            assertEquals(239.5, leds.getDouble("LED_2_v_px"), 1e-9)
            assertEquals(0, leds.getInt("LED_1_detected"))
            assertTrue(leds.isNull("LED_1_u_px"))
            assertEquals(0.97, leds.getJSONObject("details").getJSONArray("leds").getJSONObject(1).getDouble("confidence"), 1e-6)
            val fingerprint = SessionCsv.fingerprint(SessionCsv.readInference(SessionLayout.inferenceFolder(capture)))
            assertEquals("4,,,0,319.500,239.500,1,,,0,,,0", SessionCsv.finalRow("4", fingerprint))
        } finally { session.deleteRecursively() }
    }

    @Test(timeout = 60_000) fun theModelIsGivenTheFramesInTheOrderTheyWereTaken() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), SessionLayout.captureName(5, LocalDateTime.of(2026, 11, 17, 17, 0, 0)))
            assertTrue(File(capture, SessionLayout.FRAMES).mkdirs())
            // Listed out of order, and each frame's ceiling is as bright as its number says.
            val frames = listOf(6, 0, 11, 3, 9, 1, 7, 4, 10, 2, 8, 5).map { JSONObject().put("frame_id", it).put("width", width).put("height", height) }
            frames.forEach { CaptureProcessing.rawFile(capture, it.getInt("frame_id")).writeBytes(rawFrame(ceiling = 20 + 10 * it.getInt("frame_id"))) }
            val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
            val running = java.util.concurrent.atomic.AtomicInteger()
            val together = java.util.concurrent.atomic.AtomicBoolean(false)
            val model = FrameInference { rgb, _, _ ->
                if (running.incrementAndGet() > 1) together.set(true)
                seen.add(((rgb[0].toInt() and 0xFF) - 20) / 10)
                Thread.sleep(5)
                running.decrementAndGet()
                InferredFrame(byteArrayOf(1), emptyList(), 1L)
            }

            val result = CaptureProcessing.process(capture, frames, rowTime, LedDetectionSettings(),
                { JSONObject().put("frame_id", it.getInt("frame_id")) }, model) { _, _ -> }

            assertEquals((0..11).toList(), seen.toList())
            assertFalse(together.get())
            assertEquals((0..11).toSet(), result.saved)
        } finally { session.deleteRecursively() }
    }

    @Test(timeout = 60_000) fun aFrameThatCannotBeReadDoesNotKeepTheLaterOnesFromTheModel() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), SessionLayout.captureName(6, LocalDateTime.of(2026, 11, 17, 17, 5, 0)))
            assertTrue(File(capture, SessionLayout.FRAMES).mkdirs())
            val frames = (0..5).map { JSONObject().put("frame_id", it).put("width", width).put("height", height) }
            // Frame 2 has no raw file.
            frames.filter { it.getInt("frame_id") != 2 }.forEach { CaptureProcessing.rawFile(capture, it.getInt("frame_id")).writeBytes(rawFrame()) }
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val model = FrameInference { _, _, _ -> calls.incrementAndGet(); InferredFrame(byteArrayOf(1), emptyList(), 1L) }

            val result = CaptureProcessing.process(capture, frames, rowTime, LedDetectionSettings(),
                { JSONObject().put("frame_id", it.getInt("frame_id")) }, model) { _, _ -> }

            assertEquals(setOf(0, 1, 3, 4, 5), result.saved)
            assertEquals(5, calls.get())
        } finally { session.deleteRecursively() }
    }

    @Test fun aFrameTheModelCannotRunOnIsStillSaved() {
        val session = Files.createTempDirectory("session_").toFile()
        try {
            val capture = File(SessionLayout.originals(session), SessionLayout.captureName(9, LocalDateTime.of(2026, 11, 16, 21, 5, 0)))
            assertTrue(File(capture, SessionLayout.FRAMES).mkdirs())
            val frames = (0..1).map { JSONObject().put("frame_id", it).put("width", width).put("height", height) }
            frames.forEach { CaptureProcessing.rawFile(capture, it.getInt("frame_id")).writeBytes(rawFrame()) }
            val broken = FrameInference { _, _, _ -> error("The model is not available.") }

            val result = CaptureProcessing.process(capture, frames, rowTime, LedDetectionSettings(),
                { JSONObject().put("frame_id", it.getInt("frame_id")) }, broken) { _, _ -> }

            assertEquals(setOf(0, 1), result.saved)
            assertTrue(result.inferred.isEmpty())
            // The mapping file exists and lists no frame, so the position's LEDs count as not found rather than unknown.
            assertEquals("frame_file,metadata_file\n",
                File(SessionLayout.inferenceFolder(capture), SessionLayout.INFERENCE_MAPPING).readText())
            assertTrue(File(capture, "frames/frame_00001.png").isFile)
        } finally { session.deleteRecursively() }
    }
}
