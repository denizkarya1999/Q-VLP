package com.developer27.qvlp.vision

import com.developer27.qvlp.inference.LedDetection
import org.junit.Assert.*
import org.junit.Test

class FrameOverlayTest {
    private val width = 320
    private val height = 240
    private fun blob(u: Double, v: Double, left: Int, top: Int) = LedBlob(u, v, 1600, 200, left, top, 40, 40,
        touchesBorder = false, saturated = false, roughlyRound = true, stripe = null, frequencyHz = null)
    private fun pixel(rgb: ByteArray, x: Int, y: Int) =
        ((rgb[(y * width + x) * 3].toInt() and 0xFF) shl 16) or ((rgb[(y * width + x) * 3 + 1].toInt() and 0xFF) shl 8) or (rgb[(y * width + x) * 3 + 2].toInt() and 0xFF)

    @Test fun leavesAFrameWithoutBlobsUntouched() {
        val rgb = ByteArray(width * height * 3) { 7 }
        assertFalse(FrameOverlay.draw(rgb, width, height, FrameLeds(List(4) { LedReading(false, null) }, emptyList(), 1.0, 21)))
        assertTrue(rgb.all { it.toInt() == 7 })
    }

    @Test fun marksEachLedInItsOwnColourAtItsCentreAndAroundItsBlob() {
        val first = blob(120.4, 100.6, 100, 80)
        val third = blob(250.0, 180.0, 230, 160)
        val stray = blob(40.0, 200.0, 20, 180)
        val frame = FrameLeds(listOf(LedReading(true, first), LedReading(false, null), LedReading(false, third), LedReading(false, null)),
            listOf(first, third, stray), 1.0, 21)
        val rgb = ByteArray(width * height * 3)
        assertTrue(FrameOverlay.draw(rgb, width, height, frame))
        assertEquals(0xD62728, pixel(rgb, 120, 101))   // LED 1 centre, rounded
        assertEquals(0xD62728, pixel(rgb, 120, 78))    // its box, just outside the blob
        assertEquals(0x2CA02C, pixel(rgb, 250, 180))   // LED 3 keeps its colour even when rejected
        assertEquals(0xFFD400, pixel(rgb, 40, 200))    // a blob that matched no LED
        assertEquals(0, pixel(rgb, 120, 90))           // inside the blob, away from the cross, nothing is covered
        assertEquals(0, pixel(rgb, 5, 5))
        // An accepted LED has a solid box; a rejected one is dashed, so part of its edge stays clear.
        assertTrue((98..141).all { pixel(rgb, it, 78) == 0xD62728 })
        assertTrue((228..271).any { pixel(rgb, it, 158) == 0 })
    }

    @Test fun marksWhatTheModelsFoundTheSameWay() {
        val rgb = ByteArray(width * height * 3)
        assertFalse(FrameOverlay.drawInferred(rgb, width, height, emptyList()))
        assertTrue(rgb.all { it.toInt() == 0 })
        val second = LedDetection(2, 0.97f, 100f, 80f, 140f, 120f)
        val fourth = LedDetection(4, 0.99f, 230.4f, 160.2f, 270.6f, 199.8f)
        val unnamed = LedDetection(LedDetection.UNNAMED, 0.9f, 20f, 180f, 60f, 220f)
        assertTrue(FrameOverlay.drawInferred(rgb, width, height, listOf(second, fourth, unnamed)))
        // The cross is at the centre the metadata gives, 119.5 and 99.5, rounded.
        assertEquals(0x1F77B4, pixel(rgb, 120, 100))
        // A solid box just outside the pixels the model's box covers, columns 100 to 139 and rows 80 to 119.
        assertTrue((98..141).all { pixel(rgb, it, 78) == 0x1F77B4 && pixel(rgb, it, 121) == 0x1F77B4 })
        assertTrue((78..121).all { pixel(rgb, 98, it) == 0x1F77B4 && pixel(rgb, 141, it) == 0x1F77B4 })
        assertEquals(0, pixel(rgb, 105, 85))
        assertEquals(0x9467BD, pixel(rgb, 250, 180))   // LED 4
        assertEquals(0x9467BD, pixel(rgb, 228, 158))   // its box holds the part-covered edge pixels too
        assertEquals(0xFFD400, pixel(rgb, 40, 200))    // found by the detection model, not named
        assertEquals(0, pixel(rgb, 5, 5))
        // Drawing never leaves the frame.
        assertTrue(FrameOverlay.drawInferred(ByteArray(width * height * 3), width, height,
            listOf(LedDetection(1, 1f, -4f, -4f, 6f, 6f), LedDetection(3, 1f, 300f, 220f, 330f, 250f))))
    }

    @Test fun marksTheBlobFoundInsideEachModelBoxAndItsCentre() {
        val rgb = ByteArray(width * height * 3)
        assertFalse(FrameOverlay.drawCentres(rgb, width, height, emptyList(), emptyList()))
        assertTrue(rgb.all { it.toInt() == 0 })
        val second = LedDetection(2, 0.97f, 100f, 80f, 140f, 120f)
        val third = LedDetection(3, 0.9f, 230f, 160f, 270f, 200f)
        val unnamed = LedDetection(LedDetection.UNNAMED, 0.9f, 20f, 180f, 60f, 220f)
        // LED 2's blob covers columns 112 to 123 and rows 95 to 108, off the middle of its box. LED 3's box holds none.
        val blob = LedCentre(117.4, 101.6, 140, 210, 112, 95, 12, 14, saturated = false, touchesBoxEdge = false, frames = 5)
        val speck = LedCentre(40.0, 200.0, 9, 90, 39, 199, 3, 3, saturated = false, touchesBoxEdge = false, frames = 5)
        assertTrue(FrameOverlay.drawCentres(rgb, width, height, listOf(second, third, unnamed), listOf(blob, null, speck)))
        // The cross is at the blob's centre, rounded, not at the centre of the model's box (119.5, 99.5).
        assertEquals(0x1F77B4, pixel(rgb, 117, 102))
        assertEquals(0x1F77B4, pixel(rgb, 117, 110))   // the cross reaches past the small blob
        assertEquals(0, pixel(rgb, 120, 100))
        // A solid box just outside the blob's own pixels; the model's larger box is not drawn.
        assertTrue((110..125).all { pixel(rgb, it, 93) == 0x1F77B4 && pixel(rgb, it, 110) == 0x1F77B4 })
        assertTrue((93..110).all { pixel(rgb, 110, it) == 0x1F77B4 && pixel(rgb, 125, it) == 0x1F77B4 })
        assertTrue((98..141).all { pixel(rgb, it, 121) == 0 } && (95..121).all { pixel(rgb, 98, it) == 0 })
        // LED 3 was named and has no blob: the model's box, dashed, and no cross.
        assertEquals(0x2CA02C, pixel(rgb, 228, 158))
        assertTrue((228..271).any { pixel(rgb, it, 158) == 0 })
        assertEquals(0, pixel(rgb, 250, 180))
        // A light without a name gets its blob in the colour of an unknown LED.
        assertEquals(0xFFD400, pixel(rgb, 40, 200))
        assertEquals(0, pixel(rgb, 5, 5))
        // Drawing never leaves the frame, with or without a blob.
        val corner = LedCentre(1.0, 1.0, 9, 200, 0, 0, 3, 3, saturated = false, touchesBoxEdge = true, frames = 1)
        val far = LedCentre(318.5, 238.5, 4, 200, 318, 238, 2, 2, saturated = false, touchesBoxEdge = true, frames = 1)
        assertTrue(FrameOverlay.drawCentres(ByteArray(width * height * 3), width, height,
            listOf(LedDetection(1, 1f, -4f, -4f, 6f, 6f), LedDetection(3, 1f, 300f, 220f, 330f, 250f), LedDetection(4, 1f, -9f, 230f, 4f, 260f)),
            listOf(corner, far, null)))
    }

    @Test fun drawingNeverLeavesTheFrame() {
        val corner = blob(2.0, 2.0, 0, 0).copy(width = 6, height = 6)
        val far = blob(318.0, 238.0, 300, 220).copy(width = 20, height = 20)
        val frame = FrameLeds(listOf(LedReading(true, corner), LedReading(true, far), LedReading(false, null), LedReading(false, null)),
            listOf(corner, far), 1.0, 21)
        assertTrue(FrameOverlay.draw(ByteArray(width * height * 3), width, height, frame))
    }
}
