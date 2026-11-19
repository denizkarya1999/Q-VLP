package com.developer27.qvlp.inference

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask

/**
 * Runs the Physical LED Detection Model and the OOK-Based LED Recognition Model on the phone, on a frame of
 * the test split of the third session's datasets, whose LEDs are known. It touches no session: run it alone with
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
