package com.developer27.qvlp.inference

import org.junit.Assert.*
import org.junit.Test

class LedBoxesTest {
    /** A model answer with [candidates]: each is centre x, centre y, width, height and the four LED scores. */
    private fun output(vararg candidates: FloatArray): FloatArray {
        val count = candidates.size
        return FloatArray(8 * count) { candidates[it % count][it / count] }
    }
    private fun candidate(x: Float, y: Float, vararg scores: Float) =
        floatArrayOf(x, y, 40f, 40f) + FloatArray(4) { scores.getOrElse(it) { 0f } }

    @Test fun showsOneBoxPerLedHoweverManyCandidatesLieOnIt() {
        val found = LedBoxes.pick(output(
            candidate(100f, 100f, 0.90f), candidate(101f, 100f, 0.98f), candidate(99f, 101f, 0.80f),
            candidate(500f, 100f, 0f, 0.91f), candidate(501f, 99f, 0f, 0.60f),
            candidate(100f, 600f, 0f, 0f, 0.97f), candidate(500f, 600f, 0f, 0f, 0f, 0.99f), candidate(502f, 600f, 0f, 0f, 0f, 0.70f)
        ), 1280, 960)
        assertEquals(listOf(1, 2, 3, 4), found.map { it.led })
        assertEquals(listOf("LED 1 | 98%", "LED 2 | 91%", "LED 3 | 97%", "LED 4 | 99%"), found.map { it.label })
        // The winning candidate's own box: centre (101, 100), 40 wide and high.
        assertEquals(81f, found[0].left, 1e-4f); assertEquals(80f, found[0].top, 1e-4f)
        assertEquals(121f, found[0].right, 1e-4f); assertEquals(120f, found[0].bottom, 1e-4f)
    }

    @Test fun neverPutsTwoBoxesOnTheSameLight() {
        // The light at (500, 100) scores for LED 2 and, a little less, for LED 4. LED 4 has a light of its own.
        val found = LedBoxes.pick(output(
            candidate(500f, 100f, 0f, 0.90f, 0f, 0.60f), candidate(900f, 700f, 0f, 0f, 0f, 0.40f)
        ), 1280, 960)
        assertEquals(listOf(2, 4), found.map { it.led })
        assertEquals(900f, (found[1].left + found[1].right) / 2f, 1e-4f)
    }

    @Test fun aLightGoesToTheLedItLooksMostLike() {
        // One light, two names for it: only the more confident name is shown.
        val found = LedBoxes.pick(output(candidate(500f, 100f, 0f, 0.55f, 0f, 0.65f)), 1280, 960)
        assertEquals(listOf("LED 4 | 65%"), found.map { it.label })
    }

    @Test fun leavesOutWhatTheModelIsNotSureOf() {
        val answer = output(candidate(100f, 100f, 0.24f), candidate(600f, 400f, 0f, 0.25f))
        assertEquals(listOf(2), LedBoxes.pick(answer, 1280, 960).map { it.led })
        assertTrue(LedBoxes.pick(output(candidate(100f, 100f, 0.2f, 0.1f, 0.05f, 0.01f)), 1280, 960).isEmpty())
    }

    @Test fun keepsBoxesInsideThePicture() {
        val found = LedBoxes.pick(output(candidate(5f, 950f, 0.9f)), 1280, 960).single()
        assertEquals(0f, found.left, 0f); assertEquals(25f, found.right, 1e-4f)
        assertEquals(930f, found.top, 1e-4f); assertEquals(960f, found.bottom, 0f)
    }

    @Test fun rejectsAnAnswerOfTheWrongShape() {
        assertThrows(IllegalArgumentException::class.java) { LedBoxes.pick(FloatArray(10), 1280, 960) }
        assertThrows(IllegalArgumentException::class.java) { LedBoxes.pick(FloatArray(0), 1280, 960) }
    }

    @Test fun overlapIsZeroApartAndOneForTheSameBox() {
        val box = LedDetection(1, 0.9f, 10f, 10f, 50f, 50f)
        assertEquals(1f, box.overlap(box), 1e-6f)
        assertEquals(0f, box.overlap(LedDetection(2, 0.9f, 60f, 10f, 100f, 50f)), 0f)
        // Half of each box is shared: 800 of the 2400 pixels they cover together.
        assertEquals(1f / 3f, box.overlap(LedDetection(2, 0.9f, 30f, 10f, 70f, 50f)), 1e-6f)
    }
}
