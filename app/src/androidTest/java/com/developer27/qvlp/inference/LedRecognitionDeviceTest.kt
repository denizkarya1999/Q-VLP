package com.developer27.qvlp.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.developer27.qvlp.capture.CaptureProcessing
import com.developer27.qvlp.capture.SessionCsv
import com.developer27.qvlp.capture.SessionLayout
import com.developer27.qvlp.vision.FramePng
import com.developer27.qvlp.vision.LedDetectionSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.FutureTask
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Runs the Physical LED Detection Model and the OOK-Based LED Recognition Model on the phone, on a frame of
 * the test split of the third session's datasets, whose LEDs are known. It touches no session (the capture it
 * processes is written into the app's cache and deleted): run it alone with
 * `adb shell am instrument -w -e class com.developer27.qvlp.inference.LedRecognitionDeviceTest …`.
 */
@RunWith(AndroidJUnit4::class)
class LedRecognitionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /**
     * pos28_frame_00000.png of the third session's "Physical LED Light Detection Dataset" (18 November 2026):
     * the labelled box of each LED, as left, top, right, bottom. The LEDs are numbered by the code they send, as
     * in that session's "OOK-Based LED Recognition Dataset": LED 1 (1010) bottom-left, LED 2 (1100) top-left,
     * LED 3 (1000) bottom-right, LED 4 (1110) top-right.
     */
    private val labelled = mapOf(
        1 to floatArrayOf(726f, 743f, 764f, 790f), 2 to floatArrayOf(742f, 118f, 782f, 158f),
        3 to floatArrayOf(1116f, 741f, 1149f, 776f), 4 to floatArrayOf(1116f, 117f, 1163f, 166f)
    )

    private fun frame(): Bitmap = instrumentation.context.assets.open("session3_pos28_frame_00000.png").use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
    }

    private fun <T> onModelThread(work: () -> T): T = FutureTask(work).also { LedRecognition.execute(it::run) }.get()

    private fun describe(found: List<LedDetection>) = found.joinToString {
        "${it.label} (detected ${it.detected}) [${it.left.toInt()}, ${it.top.toInt()}, ${it.right.toInt()}, ${it.bottom.toInt()}]"
    }

    @Test fun findsAndNamesEachOfTheFourLedsOnceWhereItIsLabelled() {
        val picture = frame()
        assertEquals(LedInput.WIDTH, picture.width)
        assertEquals(LedInput.HEIGHT, picture.height)
        val first = onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture) }
        assertEquals(describe(first.detections), listOf(1, 2, 3, 4), first.detections.map { it.led })
        for (found in first.detections) {
            val box = labelled.getValue(found.led)
            val label = LedDetection(found.led, 1f, box[0], box[1], box[2], box[3])
            assertTrue("LED ${found.led} is not where it is labelled: $found", found.overlap(label) > 0.8f)
            // On a computer the boxes overlap their labels by 92% to 96%, the detection model is 86% to 91% sure of
            // each of these LEDs and the recognition model 100%.
            assertTrue("LED ${found.led} was detected with ${found.detected}", found.detected > 0.8f)
            assertTrue("LED ${found.led} was named with ${found.confidence}", found.confidence > 0.95f)
        }
        // The first run includes loading the models; the following ones show the speed on one picture.
        val times = (1..5).map { onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture) }.milliseconds }
        Log.i("QvlpLedModelTest", "one picture: first run ${first.milliseconds} ms, then $times ms; ${describe(first.detections)}")
    }

    @Test fun aSeriesOfFramesKeepsTheNamesAndNamesAgainEveryFiveFrames() {
        val picture = frame()
        val tracks = LedTracks()
        // As on the preview and in a capture: the recognition model runs at frames 1, 5 and 10 only.
        val runs = (1..11).map { onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture, tracks) } }
        for (run in runs) assertEquals(describe(run.detections), listOf(1, 2, 3, 4), run.detections.map { it.led })
        Log.i("QvlpLedModelTest", "a series of frames: ${runs.map { it.milliseconds }} ms")
    }

    @Test fun theFramesOfACaptureAreNamedAndDrawn() {
        // The path of a capture's post-inference files: frames as red, green, blue bytes, one after the other.
        val picture = frame()
        val pixels = IntArray(picture.width * picture.height)
        picture.getPixels(pixels, 0, picture.width, 0, 0, picture.width, picture.height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, colour ->
            rgb[index * 3] = (colour shr 16).toByte(); rgb[index * 3 + 1] = (colour shr 8).toByte(); rgb[index * 3 + 2] = colour.toByte()
        }
        val untouched = rgb.copyOf()
        val capture = LedRecognition.frames(instrumentation.targetContext)
        val frames = (1..6).map { capture.run(rgb, picture.width, picture.height) }
        for (inferred in frames) {
            assertEquals(describe(inferred.detections), listOf(1, 2, 3, 4), inferred.detections.map { it.led })
            // A PNG of the frame with the boxes on it.
            val drawn = BitmapFactory.decodeByteArray(inferred.picture, 0, inferred.picture.size)
            assertEquals(picture.width, drawn.width)
            assertEquals(picture.height, drawn.height)
        }
        assertArrayEquals(untouched, rgb)
        Log.i("QvlpLedModelTest", "the frames of a capture: ${frames.map { it.milliseconds }} ms; ${describe(frames.last().detections)}")
    }

    @Test fun aCaptureGetsItsProcessedFramesFromBothModels() {
        // A whole Inference Mode capture of six frames, as the camera gives them: luminance with half-resolution colour.
        val picture = frame()
        val width = picture.width
        val height = picture.height
        val pixels = IntArray(width * height)
        picture.getPixels(pixels, 0, width, 0, 0, width, height)
        val raw = ByteArray(FramePng.i420Size(width, height))
        val chromaWidth = (width + 1) / 2
        val uPlane = width * height
        val vPlane = uPlane + chromaWidth * ((height + 1) / 2)
        for (y in 0 until height) for (x in 0 until width) {
            val colour = pixels[y * width + x]
            val red = (colour shr 16) and 0xFF; val green = (colour shr 8) and 0xFF; val blue = colour and 0xFF
            raw[y * width + x] = (0.299 * red + 0.587 * green + 0.114 * blue).roundToInt().coerceIn(0, 255).toByte()
            if (y % 2 == 0 && x % 2 == 0) {
                val at = (y / 2) * chromaWidth + x / 2
                raw[uPlane + at] = (128 - 0.168736 * red - 0.331264 * green + 0.5 * blue).roundToInt().coerceIn(0, 255).toByte()
                raw[vPlane + at] = (128 + 0.5 * red - 0.418688 * green - 0.081312 * blue).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        val session = File(instrumentation.targetContext.cacheDir, "device_test_session_${System.nanoTime()}")
        try {
            val capture = File(SessionLayout.originals(session), SessionLayout.captureName(28, LocalDateTime.of(2026, 11, 18, 23, 0, 0)))
            assertTrue(File(capture, SessionLayout.FRAMES).mkdirs())
            val frames = (0..5).map { JSONObject().put("frame_id", it).put("width", width).put("height", height) }
            frames.forEach { CaptureProcessing.rawFile(capture, it.getInt("frame_id")).writeBytes(raw) }

            val result = CaptureProcessing.process(capture, frames, CaptureProcessing.RowTime(null, "unknown"), LedDetectionSettings(),
                { JSONObject().put("frame_id", it.getInt("frame_id")) }, LedRecognition.frames(instrumentation.targetContext)) { _, _ -> }

            assertEquals((0..5).toSet(), result.saved)
            assertEquals((0..5).toSet(), result.inferred.keys)
            // LED detection did not run: the processed frames are the two models' alone.
            assertTrue(result.leds.isEmpty())
            val processed = SessionLayout.processedFolder(capture)
            val colours = intArrayOf(0xD62728, 0x1F77B4, 0x2CA02C, 0x9467BD)
            for (id in 0..5) {
                val text = File(processed, "${SessionLayout.PROCESSED_METADATA}/${SessionLayout.metadataName(id)}").readText()
                val data = JSONObject(text)
                assertEquals(LedRecognition.DETECTION_MODEL_FILE, data.getJSONObject("details").getString("detection_model"))
                assertEquals(LedRecognition.RECOGNITION_MODEL_FILE, data.getJSONObject("details").getString("recognition_model"))
                val drawn = BitmapFactory.decodeFile(File(processed, "${SessionLayout.PROCESSED_FRAMES}/${SessionLayout.frameName(id)}").path)
                for ((led, box) in labelled) {
                    // Each LED is named and is where it is labelled, and its cross is on the picture in its colour.
                    assertEquals("frame $id: $text", 1, data.getInt("LED_${led}_detected"))
                    val u = data.getDouble("LED_${led}_u_px")
                    val v = data.getDouble("LED_${led}_v_px")
                    assertTrue("frame $id LED $led at $u, $v", abs(u - ((box[0] + box[2]) / 2 - 0.5)) < 4 && abs(v - ((box[1] + box[3]) / 2 - 0.5)) < 4)
                    assertEquals("frame $id LED $led", colours[led - 1], drawn.getPixel(u.roundToInt(), v.roundToInt()) and 0xFFFFFF)
                }
            }
            // Both folders give final.csv the same row.
            assertEquals(SessionCsv.readInference(SessionLayout.inferenceFolder(capture)), SessionCsv.readProcessed(processed))
            // One processed frame is kept where it can be looked at from a computer.
            instrumentation.targetContext.getExternalFilesDir(null)?.let { folder ->
                File(processed, "${SessionLayout.PROCESSED_FRAMES}/${SessionLayout.frameName(5)}").copyTo(File(folder, "device_test_processed_frame.png"), overwrite = true)
            }
        } finally {
            session.deleteRecursively()
        }
    }

    @Test fun onlyTheTickedModelsRun() {
        val picture = frame()
        // Settings → Options → Inference Models with the recognition model unticked: the LEDs are found, none is named.
        val detectionOnly = InferenceModels(detection = true, recognition = false)
        val found = onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture, models = detectionOnly) }.detections
        assertEquals(describe(found), 4, found.size)
        assertTrue(describe(found), found.none { it.named })
        assertEquals(found.map { it.detected }.sortedDescending(), found.map { it.detected })
        for (box in labelled.values) {
            val label = LedDetection(LedDetection.UNNAMED, 1f, box[0], box[1], box[2], box[3])
            assertTrue("No box on the LED labelled at ${box.toList()}: ${describe(found)}", found.any { it.overlap(label) > 0.8f })
        }
        // With the detection model unticked nothing is looked for.
        val nothing = InferenceModels(detection = false, recognition = false)
        assertTrue(onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture, models = nothing) }.detections.isEmpty())
    }

    @Test fun aPictureOfAnotherSizeGetsItsBoxesInItsOwnPixels() {
        // The same frame at half size: the detection model sees it enlarged back, and the boxes come out at half size.
        val half = Bitmap.createScaledBitmap(frame(), LedInput.WIDTH / 2, LedInput.HEIGHT / 2, true)
        val found = onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, half) }.detections
        Log.i("QvlpLedModelTest", "half size: ${describe(found)}")
        assertTrue("Only ${describe(found)} found at half size", found.size >= 3)
        for (led in found.filter { it.named }) {
            val box = labelled.getValue(led.led)
            val label = LedDetection(led.led, 1f, box[0] / 2f, box[1] / 2f, box[2] / 2f, box[3] / 2f)
            assertTrue("LED ${led.led} is not where it is labelled: $led", led.overlap(label) > 0.5f)
        }
    }
}
