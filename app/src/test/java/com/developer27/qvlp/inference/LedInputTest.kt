package com.developer27.qvlp.inference

import org.junit.Assert.*
import org.junit.Test

class LedInputTest {
    @Test fun aFrameBecomesRedGreenAndBluePlanesFromZeroToOne() {
        val rgb = ByteArray(LedInput.SIZE)
        // First pixel: red 255, green 0, blue 51. Last pixel: grey 128.
        rgb[0] = 255.toByte(); rgb[1] = 0; rgb[2] = 51
        for (channel in 0..2) rgb[LedInput.SIZE - 3 + channel] = 128.toByte()
        val input = FloatArray(LedInput.SIZE)
        LedInput.fromRgb(rgb, input)
        assertEquals(1f, input[0], 0f)
        assertEquals(0f, input[LedInput.PLANE], 0f)
        assertEquals(0.2f, input[2 * LedInput.PLANE], 1e-6f)
        for (plane in 1..3) assertEquals(128f / 255f, input[plane * LedInput.PLANE - 1], 1e-6f)
    }

    @Test fun aBitmapPixelGivesTheSameValuesAsTheSameFramePixel() {
        val argb = IntArray(LedInput.PLANE)
        argb[7] = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50
        val input = FloatArray(LedInput.SIZE)
        LedInput.fromPixels(argb, input)
        assertEquals(200f / 255f, input[7], 1e-6f)
        assertEquals(100f / 255f, input[LedInput.PLANE + 7], 1e-6f)
        assertEquals(50f / 255f, input[2 * LedInput.PLANE + 7], 1e-6f)
    }

    @Test fun aCameraFrameIsUsedAsItIs() {
        assertTrue(Letterbox.fit(1280, 960).unchanged)
        val found = LedDetection(3, 0.8f, 10f, 20f, 50f, 60f)
        assertEquals(found, Letterbox.fit(1280, 960).toSource(found))
    }

    @Test fun anotherPictureIsFittedAndItsBoxesMovedBack() {
        // Half-size picture: doubled to fill the input exactly.
        val half = Letterbox.fit(640, 480)
        assertEquals(Letterbox(2f, 0f, 0f), half)
        assertEquals(LedDetection(1, 0.9f, 50f, 100f, 70f, 120f), half.toSource(LedDetection(1, 0.9f, 100f, 200f, 140f, 240f)))
        // A square picture fits the height and is centred, with grey at the sides.
        val square = Letterbox.fit(960, 960)
        assertEquals(1f, square.scale, 0f)
        assertEquals(160f, square.offsetX, 0f)
        assertEquals(0f, square.offsetY, 0f)
        assertEquals(40f, square.toSource(LedDetection(1, 0.9f, 200f, 0f, 240f, 40f)).left, 1e-4f)
        // A wide picture fits the width and is centred, with grey above and below.
        val wide = Letterbox.fit(2560, 960)
        assertEquals(0.5f, wide.scale, 0f)
        assertEquals(240f, wide.offsetY, 0f)
    }
}
