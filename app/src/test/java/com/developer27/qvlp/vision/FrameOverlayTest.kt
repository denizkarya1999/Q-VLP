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
        assertTrue(FrameOverlay.drawInferred(rgb, width, height, listOf(second, fourth)))
        // The cross is at the centre the metadata gives, 119.5 and 99.5, rounded.
        assertEquals(0x1F77B4, pixel(rgb, 120, 100))
        // A solid box just outside the pixels the model's box covers, columns 100 to 139 and rows 80 to 119.
        assertTrue((98..141).all { pixel(rgb, it, 78) == 0x1F77B4 && pixel(rgb, it, 121) == 0x1F77B4 })
        assertTrue((78..121).all { pixel(rgb, 98, it) == 0x1F77B4 && pixel(rgb, 141, it) == 0x1F77B4 })
        assertEquals(0, pixel(rgb, 105, 85))
        assertEquals(0x9467BD, pixel(rgb, 250, 180))   // LED 4
        assertEquals(0x9467BD, pixel(rgb, 228, 158))   // its box holds the part-covered edge pixels too
        assertEquals(0, pixel(rgb, 5, 5))
        // Found by the detection model, not named. In a frame this small its name would run across LED 4's box.
        val alone = ByteArray(width * height * 3)
        assertTrue(FrameOverlay.drawInferred(alone, width, height, listOf(unnamed)))
        assertEquals(0xFFD400, pixel(alone, 40, 200))
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
        assertTrue(FrameOverlay.drawCentres(rgb, width, height, listOf(second, third), listOf(blob, null)))
        // The cross is at the blob's centre, rounded, not at the centre of the model's box (119.5, 99.5).
        assertEquals(0x1F77B4, pixel(rgb, 117, 102))
        assertEquals(0x1F77B4, pixel(rgb, 117, 110))   // the cross reaches past the small blob
        assertEquals(0, pixel(rgb, 120, 100))
        // A solid box just outside the blob's own pixels; the model's larger box is not drawn.
        assertTrue((110..125).all { pixel(rgb, it, 93) == 0x1F77B4 && pixel(rgb, it, 110) == 0x1F77B4 })
        assertTrue((93..110).all { pixel(rgb, 110, it) == 0x1F77B4 && pixel(rgb, 125, it) == 0x1F77B4 })
        // (Checked between the two lines of text, which in a frame this small run across the box's edges.)
        assertTrue((89..115).all { pixel(rgb, 98, it) == 0 && pixel(rgb, 141, it) == 0 })
        // LED 3 was named and has no blob: the model's box, dashed, and no cross.
        assertEquals(0x2CA02C, pixel(rgb, 228, 158))
        assertTrue((228..271).any { pixel(rgb, it, 158) == 0 })
        assertEquals(0, pixel(rgb, 250, 180))
        assertEquals(0, pixel(rgb, 5, 5))
        // A light without a name gets its blob in the colour of an unknown LED.
        val alone = ByteArray(width * height * 3)
        assertTrue(FrameOverlay.drawCentres(alone, width, height, listOf(unnamed), listOf(speck)))
        assertEquals(0xFFD400, pixel(alone, 40, 200))
        // Drawing never leaves the frame, with or without a blob.
        val corner = LedCentre(1.0, 1.0, 9, 200, 0, 0, 3, 3, saturated = false, touchesBoxEdge = true, frames = 1)
        val far = LedCentre(318.5, 238.5, 4, 200, 318, 238, 2, 2, saturated = false, touchesBoxEdge = true, frames = 1)
        assertTrue(FrameOverlay.drawCentres(ByteArray(width * height * 3), width, height,
            listOf(LedDetection(1, 1f, -4f, -4f, 6f, 6f), LedDetection(3, 1f, 300f, 220f, 330f, 250f), LedDetection(4, 1f, -9f, 230f, 4f, 260f)),
            listOf(corner, far, null)))
    }

    /** How many pixels of [colour] the rows [rows] hold in a frame [across] pixels wide. */
    private fun count(rgb: ByteArray, across: Int, rows: IntRange, colour: Int) = rows.sumOf { y ->
        (0 until across).count { x ->
            val i = (y * across + x) * 3
            (((rgb[i].toInt() and 0xFF) shl 16) or ((rgb[i + 1].toInt() and 0xFF) shl 8) or (rgb[i + 2].toInt() and 0xFF)) == colour
        }
    }

    @Test fun writesTheCentreUnderTheBoxAndTheConfidenceBesideTheName() {
        assertEquals("{868.41,286.95}", centreLabel(868.412, 286.953))
        assertEquals("{0.00,959.50}", centreLabel(0.0, 959.5))
        val blob = LedCentre(318.412, 221.953, 140, 210, 312, 215, 12, 14, saturated = false, touchesBoxEdge = false, frames = 5)
        assertEquals("{318.41,221.95}", blob.label)
        assertTrue(FrameOverlay.writable("LED 1 ? | 100.0%") && FrameOverlay.writable("LED ? | 62.4%"))
        assertTrue(FrameOverlay.writable(centreLabel(1234567.89, 0.0)) && FrameOverlay.writable("?"))
        val across = 640
        val down = 480
        val led = LedDetection(1, 0.99f, 300f, 200f, 340f, 240f)
        // The box and the cross cover rows 213 to 231: the name is written in rows 195 to 208, the centre in 236 to 249.
        val with = ByteArray(across * down * 3)
        assertTrue(FrameOverlay.drawCentres(with, across, down, listOf(led), listOf(blob)))
        val nameDots = count(with, across, 195..208, 0xD62728)
        assertTrue(nameDots > 0)
        assertTrue(count(with, across, 236..249, 0xD62728) > 0)
        assertEquals(0, count(with, across, 250 until down, 0xD62728) + count(with, across, 0..194, 0xD62728))
        // An LED whose box holds no blob has no centre to write, and one found with the models alone has none either.
        val without = ByteArray(across * down * 3)
        assertTrue(FrameOverlay.drawCentres(without, across, down, listOf(led), listOf(null)))
        assertEquals(0, count(without, across, 248 until down, 0xD62728))
        val models = ByteArray(across * down * 3)
        assertTrue(FrameOverlay.drawInferred(models, across, down, listOf(led)))
        assertEquals(0, count(models, across, 248 until down, 0xD62728))
        assertTrue(count(models, across, 0..197, 0xD62728) > 0)
        // LED detection's own blobs get their centre too, and a name without a confidence: fewer dots than with one.
        val found = LedBlob(318.412, 221.953, 140, 210, 312, 215, 12, 14, touchesBorder = false, saturated = false,
            roughlyRound = true, stripe = null, frequencyHz = null)
        val detected = ByteArray(across * down * 3)
        assertTrue(FrameOverlay.draw(detected, across, down, FrameLeds(listOf(LedReading(true, found)) + List(3) { LedReading(false, null) }, listOf(found), 1.0, 21)))
        assertTrue(count(detected, across, 236..249, 0xD62728) > 0)
        assertTrue(count(detected, across, 195..208, 0xD62728) in 1 until nameDots)
        assertEquals(count(with, across, 236..249, 0xD62728), count(detected, across, 236..249, 0xD62728))
    }

    @Test fun keepsBothLinesInsideTheFrame() {
        val across = 640
        val down = 480
        fun blob(top: Int) = LedCentre(318.0, top + 7.0, 140, 210, 312, top, 12, 14, saturated = false, touchesBoxEdge = false, frames = 5)
        // At the top both lines are under the box; at the bottom both are above it.
        val top = ByteArray(across * down * 3)
        FrameOverlay.drawCentres(top, across, down, listOf(LedDetection(2, 0.9f, 300f, 0f, 340f, 30f)), listOf(blob(4)))
        assertTrue(count(top, across, 25..38, 0x1F77B4) > 0 && count(top, across, 43..56, 0x1F77B4) > 0)
        assertEquals(0, count(top, across, 39..42, 0x1F77B4) + count(top, across, 57 until down, 0x1F77B4))
        val bottom = ByteArray(across * down * 3)
        FrameOverlay.drawCentres(bottom, across, down, listOf(LedDetection(2, 0.9f, 300f, 450f, 340f, 480f)), listOf(blob(460)))
        assertTrue(count(bottom, across, 422..435, 0x1F77B4) > 0 && count(bottom, across, 440..453, 0x1F77B4) > 0)
        assertEquals(0, count(bottom, across, 0..421, 0x1F77B4) + count(bottom, across, 436..439, 0x1F77B4))
    }

    @Test fun drawingNeverLeavesTheFrame() {
        val corner = blob(2.0, 2.0, 0, 0).copy(width = 6, height = 6)
        val far = blob(318.0, 238.0, 300, 220).copy(width = 20, height = 20)
        val frame = FrameLeds(listOf(LedReading(true, corner), LedReading(true, far), LedReading(false, null), LedReading(false, null)),
            listOf(corner, far), 1.0, 21)
        assertTrue(FrameOverlay.draw(ByteArray(width * height * 3), width, height, frame))
    }
}
