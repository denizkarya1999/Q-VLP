package com.developer27.qvlp.vision

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Frames are rendered the way the project's simulator does: a disc whose rows follow a square-wave blink. */
class LedDetectorTest {
    private data class Led(val u: Double, val v: Double, val frequency: Double, val radius: Double = 42.0,
                           val peak: Double = 180.0, val phase: Double = 0.0)

    private class Scene(val width: Int = 1280, val height: Int = 960, val rowTime: Double = 30e-6,
                        val exposure: Double = 40e-6, val background: Double = 20.0, val offLevel: Double = 0.3,
                        val noise: Double = 1.5, val start: Double = 1.234e-3) {
        fun render(leds: List<Led>, seed: Long = 7): ByteArray {
            val signal = DoubleArray(width * height)
            for (led in leds) {
                for (row in maxOf(0, (led.v - led.radius - 2).toInt())..minOf(height - 1, (led.v + led.radius + 2).toInt())) {
                    val level = level(led, row)
                    for (column in maxOf(0, (led.u - led.radius - 2).toInt())..minOf(width - 1, (led.u + led.radius + 2).toInt())) {
                        val cover = (led.radius - hypot(column - led.u, row - led.v) + 0.5).coerceIn(0.0, 1.0)
                        signal[row * width + column] += led.peak * cover * level
                    }
                }
            }
            val random = Random(seed)
            return ByteArray(width * height) {
                (background + signal[it] + noise * random.nextGaussian()).roundToInt().coerceIn(0, 255).toByte()
            }
        }

        /** Mean LED level over one row's exposure window. */
        private fun level(led: Led, row: Int): Double {
            val from = (start + row * rowTime) * led.frequency + led.phase
            val to = from + exposure * led.frequency
            fun on(p: Double) = floor(p) * 0.5 + min(p - floor(p), 0.5)
            return offLevel + (1 - offLevel) * (on(to) - on(from)) / (to - from)
        }
    }

    private val four = listOf(Led(451.6, 264.9, 1800.0), Led(854.2, 264.7, 2800.0, phase = 0.25),
        Led(451.6, 667.4, 3800.0, phase = 0.5), Led(854.2, 667.3, 4800.0, phase = 0.75))

    private fun assertFound(led: Led, reading: LedReading, tolerancePx: Double) {
        assertTrue("LED at ${led.frequency} Hz should be detected", reading.detected)
        val blob = requireNotNull(reading.blob)
        assertEquals(led.u, blob.u, tolerancePx)
        assertEquals(led.v, blob.v, tolerancePx)
        assertTrue(abs(requireNotNull(blob.frequencyHz) - led.frequency) <= 0.05 * led.frequency)
    }

    @Test fun findsAndNamesAllFourLedsWithSubPixelCentres() {
        val scene = Scene()
        val result = LedDetector().detect(scene.render(four), scene.width, scene.height, scene.rowTime)
        assertEquals(4, result.blobs.size)
        assertEquals(20.0, result.background, 1.0)
        four.forEachIndexed { index, led -> assertFound(led, result.leds[index], 0.5) }
        assertEquals(18.5, requireNotNull(result.leds[0].blob?.stripe).periodRows, 0.5)
    }

    @Test fun centresStayStableFromFrameToFrame() {
        val scene = Scene()
        val detector = LedDetector()
        val u = (0 until 8).map { frame ->
            val shifted = Scene(start = 1.234e-3 + frame / 30.0)
            requireNotNull(detector.detect(shifted.render(four, seed = frame.toLong()), scene.width, scene.height, scene.rowTime).leds[1].blob).u
        }
        assertTrue("u moved by ${u.max() - u.min()} px", u.max() - u.min() < 0.5)
    }

    @Test fun closingKeepsAFullyDarkStripedLedInOnePiece() {
        val scene = Scene(offLevel = 0.0)
        val result = LedDetector().detect(scene.render(four), scene.width, scene.height, scene.rowTime)
        assertEquals(4, result.blobs.size)
        four.forEachIndexed { index, led -> assertFound(led, result.leds[index], 6.0) }
    }

    @Test fun widensTheClosingForAFasterSensorWhoseDarkStripesAreLonger() {
        // 12 µs rows: one 1.8 kHz period spans 46 rows, so a 21-row window would split the blob into bands.
        val scene = Scene(rowTime = 12e-6, exposure = 40e-6, offLevel = 0.0)
        val leds = listOf(Led(400.0, 300.0, 1800.0, radius = 80.0), Led(900.0, 620.0, 4800.0, radius = 80.0))
        val result = LedDetector().detect(scene.render(leds), scene.width, scene.height, scene.rowTime)
        assertTrue(result.closingRows > 21)
        assertEquals(2, result.blobs.size)
        assertFound(leds[0], result.leds[0], 12.0)
        assertFound(leds[1], result.leds[3], 4.0)
        assertFalse(result.leds[1].detected)
        assertFalse(result.leds[2].detected)
    }

    @Test fun anLedAtTheImageEdgeIsReportedAsNotDetected() {
        val scene = Scene()
        val leds = listOf(Led(50.0, 300.0, 1800.0), Led(854.2, 667.3, 4800.0))
        val result = LedDetector().detect(scene.render(leds), scene.width, scene.height, scene.rowTime)
        assertFalse(result.leds[0].detected)
        assertTrue(requireNotNull(result.leds[0].blob).touchesBorder)
        assertTrue(result.leds[3].detected)
    }

    @Test fun aSaturatedLedIsDropped() {
        val scene = Scene()
        val result = LedDetector().detect(scene.render(listOf(Led(451.6, 264.9, 1800.0, peak = 400.0))),
            scene.width, scene.height, scene.rowTime)
        assertEquals(1, result.blobs.size)
        assertTrue(result.blobs[0].saturated)
        assertTrue(result.leds.none { it.detected })
    }

    @Test fun twoBlobsWithOneFrequencyLeaveThatLedUndetected() {
        val scene = Scene()
        val leds = listOf(Led(300.0, 300.0, 2800.0), Led(900.0, 600.0, 2800.0), Led(600.0, 760.0, 4800.0))
        val result = LedDetector().detect(scene.render(leds), scene.width, scene.height, scene.rowTime)
        assertEquals(3, result.blobs.size)
        assertFalse(result.leds[1].detected)
        assertNull(result.leds[1].blob)
        assertTrue(result.leds[3].detected)
    }

    @Test fun withoutARowTimeBlobsAreMeasuredButNotNamed() {
        val scene = Scene()
        val result = LedDetector().detect(scene.render(four), scene.width, scene.height, null)
        assertEquals(4, result.blobs.size)
        assertTrue(result.leds.none { it.detected })
        assertTrue(result.blobs.all { it.frequencyHz == null && it.stripe != null })
    }

    @Test fun ignoresSmallSpotsUnsteadyLightsAndEmptyFrames() {
        val scene = Scene()
        val detector = LedDetector()
        assertTrue(detector.detect(scene.render(emptyList()), scene.width, scene.height, scene.rowTime).blobs.isEmpty())
        // A 10 px spot is under 400 px; a steady lamp has no stripes to name it by.
        val result = detector.detect(scene.render(listOf(Led(300.0, 300.0, 1800.0, radius = 10.0),
            Led(800.0, 500.0, 1e-3))), scene.width, scene.height, scene.rowTime)
        assertEquals(1, result.blobs.size)
        assertTrue(result.leds.none { it.detected })
    }

    @Test fun aWrongRowTimeDoesNotProduceFalseNames() {
        val scene = Scene()
        // 20% off: every estimate lands between two configured frequencies' tolerance bands.
        val result = LedDetector().detect(scene.render(four), scene.width, scene.height, scene.rowTime * 1.2)
        assertTrue(result.leds.count { it.detected } < 4)
        result.leds.forEachIndexed { index, reading ->
            reading.blob?.let { assertEquals(LedDetectionSettings().ledFrequenciesHz[index], requireNotNull(it.frequencyHz), 0.05 * 4800) }
        }
    }

    private fun assertSame(expected: FrameLeds, actual: FrameLeds) {
        assertEquals(expected.blobs.size, actual.blobs.size)
        expected.blobs.zip(actual.blobs).forEach { (whole, fast) ->
            assertEquals(whole.u, fast.u, 1e-9)
            assertEquals(whole.v, fast.v, 1e-9)
            // Everything but the centre is an exact copy; the centre differs only by floating-point rounding.
            assertEquals(whole.copy(u = 0.0, v = 0.0), fast.copy(u = 0.0, v = 0.0))
        }
        assertEquals(expected.leds.map { it.detected }, actual.leds.map { it.detected })
        assertEquals(expected.leds.map { it.blob?.areaPx }, actual.leds.map { it.blob?.areaPx })
    }

    @Test fun workingOnlyNearBrightAreasGivesTheSameResultAsTheWholeFrame() {
        val detector = LedDetector()
        val scenes = listOf(
            Scene() to four,
            Scene(offLevel = 0.0) to four,
            Scene(rowTime = 12e-6, offLevel = 0.0) to listOf(Led(400.0, 300.0, 1800.0, radius = 80.0), Led(900.0, 620.0, 4800.0, radius = 80.0)),
            // At the image edge, in a corner, two LEDs nearly touching, a small spot, and a saturated one.
            Scene() to listOf(Led(50.0, 300.0, 1800.0), Led(1250.0, 930.0, 2800.0), Led(600.0, 480.0, 3800.0),
                Led(690.0, 480.0, 4800.0), Led(200.0, 800.0, 1800.0, radius = 10.0), Led(1000.0, 200.0, 2800.0, peak = 400.0)),
            Scene(noise = 6.0) to four,
            Scene() to emptyList()
        )
        scenes.forEachIndexed { index, (scene, leds) ->
            for (rowTime in listOf(scene.rowTime, null)) {
                val frame = scene.render(leds, seed = index.toLong())
                assertSame(detector.detectWhole(frame, scene.width, scene.height, rowTime),
                    detector.detect(frame, scene.width, scene.height, rowTime))
            }
        }
    }

    @Test fun aFrameThatIsBrightAlmostEverywhereStillMatchesTheWholeFrame() {
        val scene = Scene(background = 20.0)
        val leds = (0 until 12).map { Led(100.0 + 95 * it, 150.0 + 60 * (it % 3) + 200 * (it % 4), 1800.0 + 1000 * (it % 4), radius = 70.0) }
        val frame = scene.render(leds)
        val detector = LedDetector()
        assertSame(detector.detectWhole(frame, scene.width, scene.height, scene.rowTime),
            detector.detect(frame, scene.width, scene.height, scene.rowTime))
    }

    @Test fun detectionIsFastEnoughToRunAfterEachCapture() {
        val scene = Scene()
        val detector = LedDetector()
        fun millis(frame: ByteArray, run: (ByteArray) -> Unit): Double {
            repeat(3) { run(frame) }
            val started = System.nanoTime()
            repeat(10) { run(frame) }
            return (System.nanoTime() - started) / 10 / 1e6
        }
        val lit = scene.render(four)
        val dark = scene.render(emptyList())
        val whole = millis(lit) { detector.detectWhole(it, scene.width, scene.height, scene.rowTime) }
        val fast = millis(lit) { detector.detect(it, scene.width, scene.height, scene.rowTime) }
        val empty = millis(dark) { detector.detect(it, scene.width, scene.height, scene.rowTime) }
        println("LED detection per 1280x960 frame on this machine: whole frame %.1f ms, near bright areas %.1f ms, dark frame %.1f ms".format(whole, fast, empty))
        assertTrue("took $fast ms", fast < 1000)
        assertTrue("working near bright areas ($fast ms) should beat the whole frame ($whole ms)", fast < whole)
    }
}
