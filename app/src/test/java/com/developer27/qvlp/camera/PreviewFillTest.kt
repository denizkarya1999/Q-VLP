package com.developer27.qvlp.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewFillTest {
    // A 1280 × 960 frame from a sideways sensor appears as 960 × 1280 on screen.
    private fun shown(viewWidth: Int, viewHeight: Int): Pair<Float, Float> {
        val (sx, sy) = PreviewFill.scale(viewWidth, viewHeight, 960, 1280)
        return viewWidth * sx to viewHeight * sy
    }

    @Test fun fillsATallScreenAndCropsTheSidesWithoutStretching() {
        val (width, height) = shown(1080, 2400)
        assertEquals(2400f, height, 0.5f)
        assertEquals(1800f, width, 0.5f)
    }

    @Test fun fillsAWideScreenAndCropsTopAndBottomWithoutStretching() {
        val (width, height) = shown(2400, 1080)
        assertEquals(2400f, width, 0.5f)
        assertEquals(3200f, height, 0.5f)
    }

    @Test fun aViewWithTheImageShapeIsLeftUnscaled() {
        assertEquals(1f to 1f, PreviewFill.scale(960, 1280, 960, 1280))
    }
}
