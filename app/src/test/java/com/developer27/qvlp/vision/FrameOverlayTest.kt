package com.developer27.qvlp.vision

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

    @Test fun drawingNeverLeavesTheFrame() {
        val corner = blob(2.0, 2.0, 0, 0).copy(width = 6, height = 6)
        val far = blob(318.0, 238.0, 300, 220).copy(width = 20, height = 20)
        val frame = FrameLeds(listOf(LedReading(true, corner), LedReading(true, far), LedReading(false, null), LedReading(false, null)),
            listOf(corner, far), 1.0, 21)
        assertTrue(FrameOverlay.draw(ByteArray(width * height * 3), width, height, frame))
    }
}
