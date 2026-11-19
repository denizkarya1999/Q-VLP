package com.developer27.qvlp.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LedBoxPainterTest {
    @Test fun theNameGoesAboveTheBoxAndTheCoordinatesUnderIt() {
        assertArrayEquals(floatArrayOf(80f, 140f), LedBoxPainter.tagTops(100f, 140f, 20f, 480f, coordinates = true), 0f)
        assertEquals(80f, LedBoxPainter.tagTops(100f, 140f, 20f, 480f, coordinates = false)[0], 0f)
    }

    @Test fun aBoxAtTheTopHasBothUnderIt() {
        assertArrayEquals(floatArrayOf(45f, 65f), LedBoxPainter.tagTops(5f, 45f, 20f, 480f, coordinates = true), 0f)
        assertEquals(45f, LedBoxPainter.tagTops(5f, 45f, 20f, 480f, coordinates = false)[0], 0f)
    }

    @Test fun aBoxAtTheBottomHasBothAboveIt() {
        assertArrayEquals(floatArrayOf(390f, 410f), LedBoxPainter.tagTops(430f, 470f, 20f, 480f, coordinates = true), 0f)
        assertEquals(410f, LedBoxPainter.tagTops(430f, 470f, 20f, 480f, coordinates = false)[0], 0f)
    }

    @Test fun aBoxAsTallAsThePictureKeepsItsTagsInside() {
        val tops = LedBoxPainter.tagTops(2f, 478f, 20f, 480f, coordinates = true)
        assertArrayEquals(floatArrayOf(440f, 460f), tops, 0f)
    }
}
