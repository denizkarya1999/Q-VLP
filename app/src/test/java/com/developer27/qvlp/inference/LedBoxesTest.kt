package com.developer27.qvlp.inference

import org.junit.Assert.*
import org.junit.Test

class LedBoxesTest {
    /** A model answer with [candidates]: each is centre x, centre y, width, height and the score. */
    private fun output(vararg candidates: FloatArray): FloatArray {
        val count = candidates.size
        return FloatArray(LedBoxes.ROWS * count) { candidates[it % count][it / count] }
    }
    private fun candidate(x: Float, y: Float, score: Float) = floatArrayOf(x, y, 40f, 40f, score)

    @Test fun keepsOneBoxPerLightHoweverManyCandidatesLieOnIt() {
        val found = LedBoxes.pick(output(
            candidate(100f, 100f, 0.90f), candidate(101f, 100f, 0.98f), candidate(99f, 101f, 0.80f),
            candidate(500f, 100f, 0.91f), candidate(501f, 99f, 0.60f),
            candidate(100f, 600f, 0.97f), candidate(500f, 600f, 0.99f), candidate(502f, 600f, 0.70f)
        ), 1280, 960)
        // The most confident light first, and no light twice.
        assertEquals(listOf(0.99f, 0.98f, 0.97f, 0.91f), found.map { it.confidence })
        assertEquals(found.map { it.confidence }, found.map { it.detected })
        // The detection model finds LEDs; it does not name them.
        assertTrue(found.none { it.named })
        assertEquals("LED Detected | 99%", found[0].label)
        // The winning candidate's own box: centre (101, 100), 40 wide and high.
        assertEquals(81f, found[1].left, 1e-4f); assertEquals(80f, found[1].top, 1e-4f)
        assertEquals(121f, found[1].right, 1e-4f); assertEquals(120f, found[1].bottom, 1e-4f)
    }

    @Test fun keepsAtMostFourLeds() {
        val found = LedBoxes.pick(output(
            candidate(100f, 100f, 0.5f), candidate(300f, 100f, 0.9f), candidate(500f, 100f, 0.3f),
            candidate(700f, 100f, 0.8f), candidate(900f, 100f, 0.7f), candidate(1100f, 100f, 0.6f)
        ), 1280, 960)
        assertEquals(listOf(0.9f, 0.8f, 0.7f, 0.6f), found.map { it.confidence })
    }

    @Test fun leavesOutWhatTheModelIsNotSureOf() {
        val found = LedBoxes.pick(output(candidate(100f, 100f, 0.24f), candidate(600f, 400f, 0.25f)), 1280, 960)
        assertEquals(600f, (found.single().left + found.single().right) / 2f, 1e-4f)
        assertTrue(LedBoxes.pick(output(candidate(100f, 100f, 0.2f)), 1280, 960).isEmpty())
    }

    @Test fun keepsBoxesInsideThePicture() {
        val found = LedBoxes.pick(output(candidate(5f, 950f, 0.9f)), 1280, 960).single()
        assertEquals(0f, found.left, 0f); assertEquals(25f, found.right, 1e-4f)
        assertEquals(930f, found.top, 1e-4f); assertEquals(960f, found.bottom, 0f)
    }

    @Test fun rejectsAnAnswerOfTheWrongShape() {
        // Eight rows was the answer of the earlier four-class model.
        assertThrows(IllegalArgumentException::class.java) { LedBoxes.pick(FloatArray(8), 1280, 960) }
        assertThrows(IllegalArgumentException::class.java) { LedBoxes.pick(FloatArray(0), 1280, 960) }
    }

    @Test fun overlapIsZeroApartAndOneForTheSameBox() {
        val box = LedDetection(1, 0.9f, 10f, 10f, 50f, 50f)
        assertEquals(1f, box.overlap(box), 1e-6f)
        assertEquals(0f, box.overlap(LedDetection(2, 0.9f, 60f, 10f, 100f, 50f)), 0f)
        // Half of each box is shared: 800 of the 2400 pixels they cover together.
        assertEquals(1f / 3f, box.overlap(LedDetection(2, 0.9f, 30f, 10f, 70f, 50f)), 1e-6f)
    }

    @Test fun anLedIsLabelledWithItsNameOnceItHasOne() {
        assertEquals("LED 3 | 98%", LedDetection(3, 0.98f, 0f, 0f, 10f, 10f, detected = 0.7f).label)
        assertEquals("LED Detected | 70%", LedDetection(LedDetection.UNNAMED, 0.7f, 0f, 0f, 10f, 10f).label)
    }
}
