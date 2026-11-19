package com.developer27.qvlp.inference

import com.developer27.qvlp.camera.PreviewFill
import org.junit.Assert.*
import org.junit.Test

class PreviewMappingTest {
    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, 1e-3f)
        assertEquals(expectedY, actual.second, 1e-3f)
    }

    // A 1280 x 960 frame from a sideways sensor is 960 x 1280 on screen. A 960 x 1280 view shows it at 1:1.
    private fun rear() = PreviewMapping(1280, 960, PreviewGeometry(90, false), 960, 1280)
    private fun front() = PreviewMapping(1280, 960, PreviewGeometry(270, true), 960, 1280)

    @Test fun theRearCameraFrameIsTurnedAQuarterClockwise() {
        // The frame's top-left corner ends up at the top-right of the screen, its top-right at the bottom-right.
        assertPoint(960f, 0f, rear().point(0f, 0f))
        assertPoint(960f, 1280f, rear().point(1280f, 0f))
        assertPoint(0f, 0f, rear().point(0f, 960f))
        assertPoint(480f, 640f, rear().point(640f, 480f))
    }

    @Test fun theFrontCameraFrameIsTurnedTheOtherWayAndMirrored() {
        // Unmirrored, the frame's top-left corner would be at the bottom-left; the mirror puts it bottom-right.
        assertPoint(960f, 1280f, front().point(0f, 0f))
        assertPoint(960f, 0f, front().point(1280f, 0f))
        assertPoint(0f, 1280f, front().point(0f, 960f))
        assertPoint(480f, 640f, front().point(640f, 480f))
    }

    @Test fun aBlobCentroidIsCountedFromTheMiddleOfTheTopLeftPixel() {
        // u = 0, v = 0 is the middle of the frame's top-left pixel, half a pixel in from the frame's corner.
        assertPoint(959.5f, 0.5f, rear().centre(0.0, 0.0))
        assertPoint(959.5f, 1279.5f, front().centre(0.0, 0.0))
        // A box over columns 100 to 139 and rows 80 to 119 has its centre at u = 119.5, v = 99.5: the middle of the box drawn.
        val box = LedDetection(2, 0.9f, 100f, 80f, 140f, 120f)
        for (mapping in listOf(rear(), front(), PreviewMapping(1280, 960, PreviewGeometry(90, false), 1080, 2400))) {
            val drawn = mapping.box(box)
            assertPoint((drawn[0] + drawn[2]) / 2f, (drawn[1] + drawn[3]) / 2f, mapping.centre(119.5, 99.5))
        }
    }

    @Test fun followsThePreviewWhenItIsEnlargedToFillTheScreen() {
        // A 1080 x 2400 screen: the 960 x 1280 picture is enlarged 1.875 times and its sides are cropped.
        val mapping = PreviewMapping(1280, 960, PreviewGeometry(90, false), 1080, 2400)
        val (scaleX, scaleY) = PreviewFill.scale(1080, 2400, 960, 1280)
        assertEquals(1.875f, scaleY * 2400f / 1280f, 1e-4f)
        assertEquals(1.875f, scaleX * 1080f / 960f, 1e-4f)
        assertPoint(540f, 1200f, mapping.point(640f, 480f))
        // 100 frame pixels to the right is 187.5 screen pixels down.
        assertPoint(540f, 1387.5f, mapping.point(740f, 480f))
        // The frame's top row is beyond the right edge of the screen: that part of the preview is cropped away.
        assertTrue(mapping.point(640f, 0f).first > 1080f)
    }

    @Test fun aBoxStaysABoxWhateverTheTurn() {
        val found = LedDetection(1, 0.9f, 100f, 200f, 140f, 260f)
        for (mapping in listOf(rear(), front(), PreviewMapping(1280, 960, PreviewGeometry(0, false), 1280, 960),
            PreviewMapping(1280, 960, PreviewGeometry(180, false), 1280, 960))) {
            val box = mapping.box(found)
            assertTrue(box[0] < box[2] && box[1] < box[3])
            // Its area is unchanged at 1:1.
            assertEquals(40f * 60f, (box[2] - box[0]) * (box[3] - box[1]), 1e-2f)
        }
        // Sideways, the box's width and height swap.
        val turned = rear().box(found)
        assertEquals(60f, turned[2] - turned[0], 1e-3f)
        assertEquals(40f, turned[3] - turned[1], 1e-3f)
        assertArrayEquals(floatArrayOf(100f, 200f, 140f, 260f),
            PreviewMapping(1280, 960, PreviewGeometry(0, false), 1280, 960).box(found), 1e-3f)
    }
}
