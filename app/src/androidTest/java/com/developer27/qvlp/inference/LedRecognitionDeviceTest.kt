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
 * Runs the LED Recognition Model on the phone, on a frame of the test dataset whose LEDs are known.
 * It touches no session: run it alone with
 * `adb shell am instrument -w -e class com.developer27.qvlp.inference.LedRecognitionDeviceTest …`.
 */
@RunWith(AndroidJUnit4::class)
class LedRecognitionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** pos10_frame_00000.png of the test dataset: the labelled box of each LED, as left, top, right, bottom. */
    private val labelled = mapOf(
        1 to floatArrayOf(769f, 744f, 808f, 785f), 2 to floatArrayOf(1164f, 751f, 1202f, 790f),
        3 to floatArrayOf(777f, 171f, 815f, 210f), 4 to floatArrayOf(1136f, 153f, 1176f, 193f)
    )

    private fun frame(): Bitmap = instrumentation.context.assets.open("pos10_frame_00000.png").use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
    }

    private fun <T> onModelThread(work: () -> T): T = FutureTask(work).also { LedRecognition.execute(it::run) }.get()

    @Test fun findsEachOfTheFourLedsOnceWhereItIsLabelled() {
        val picture = frame()
        assertEquals(LedInput.WIDTH, picture.width)
        assertEquals(LedInput.HEIGHT, picture.height)
        val first = onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture) }
        assertEquals(listOf(1, 2, 3, 4), first.detections.map { it.led })
        for (found in first.detections) {
            val box = labelled.getValue(found.led)
            val label = LedDetection(found.led, 1f, box[0], box[1], box[2], box[3])
            assertTrue("LED ${found.led} is not where it is labelled: $found", found.overlap(label) > 0.8f)
            assertTrue("LED ${found.led} confidence ${found.confidence}", found.confidence > 0.8f)
        }
        // The first run includes loading the model; the following ones show the speed of Inference Mode.
        val times = (1..5).map { onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, picture) }.milliseconds }
        Log.i("QvlpLedModelTest", "first run ${first.milliseconds} ms, then $times ms; " +
            first.detections.joinToString { "${it.label} [${it.left.toInt()}, ${it.top.toInt()}, ${it.right.toInt()}, ${it.bottom.toInt()}]" })
    }

    @Test fun aPictureOfAnotherSizeGetsItsBoxesInItsOwnPixels() {
        // The same frame at half size: the model sees it enlarged back, and the boxes come out at half size.
        val half = Bitmap.createScaledBitmap(frame(), LedInput.WIDTH / 2, LedInput.HEIGHT / 2, true)
        val found = onModelThread { LedRecognition.detectPicture(instrumentation.targetContext, half) }.detections
        assertTrue("Only ${found.map { it.led }} found at half size", found.size >= 3)
        for (led in found) {
            val box = labelled.getValue(led.led)
            val label = LedDetection(led.led, 1f, box[0] / 2f, box[1] / 2f, box[2] / 2f, box[3] / 2f)
            assertTrue("LED ${led.led} is not where it is labelled: $led", led.overlap(label) > 0.5f)
        }
    }
}
