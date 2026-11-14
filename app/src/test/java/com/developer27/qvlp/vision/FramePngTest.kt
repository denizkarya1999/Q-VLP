package com.developer27.qvlp.vision

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.roundToInt

class FramePngTest {
    private class Decoded(val width: Int, val height: Int, val bitDepth: Int, val colourType: Int, val rgb: ByteArray)

    /** Reads the file by the PNG specification: signature, CRC-checked chunks, inflate, then undo each row filter. */
    private fun decode(bytes: ByteArray): Decoded {
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), bytes.copyOf(8))
        val buffer = ByteBuffer.wrap(bytes, 8, bytes.size - 8)
        val chunks = ArrayList<Pair<String, ByteArray>>()
        while (buffer.hasRemaining()) {
            val length = buffer.int
            val type = ByteArray(4).also { buffer.get(it) }
            val body = ByteArray(length).also { buffer.get(it) }
            assertEquals("CRC of ${String(type)}", CRC32().apply { update(type); update(body) }.value.toInt(), buffer.int)
            chunks.add(String(type, Charsets.US_ASCII) to body)
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map { it.first })
        val header = ByteBuffer.wrap(chunks[0].second)
        val width = header.int
        val height = header.int
        val bitDepth = header.get().toInt()
        val colourType = header.get().toInt()
        assertEquals(listOf(0, 0, 0), List(3) { header.get().toInt() })
        val stride = width * 3 + 1
        val raw = ByteArray(height * stride)
        val inflater = Inflater().apply { setInput(chunks[1].second) }
        var filled = 0
        while (filled < raw.size && !inflater.finished()) filled += inflater.inflate(raw, filled, raw.size - filled)
        assertEquals(raw.size, filled)
        assertTrue(inflater.finished())
        val rgb = ByteArray(width * height * 3)
        for (y in 0 until height) {
            // Filter 0 stores the row as it is; filter 1 ("Sub") stores each byte minus the pixel to its left.
            val filter = raw[y * stride].toInt()
            assertTrue("filter of row $y", filter == 0 || filter == 1)
            for (index in 0 until width * 3) {
                val left = if (filter == 0 || index < 3) 0 else rgb[y * width * 3 + index - 3].toInt()
                rgb[y * width * 3 + index] = (raw[y * stride + 1 + index] + left).toByte()
            }
        }
        return Decoded(width, height, bitDepth, colourType, rgb)
    }

    /** The textbook conversion in floating point, to check the fixed-point one against. */
    private fun reference(y: Int, u: Int, v: Int): List<Int> = listOf(
        y + 1.402 * (v - 128), y - 0.344136 * (u - 128) - 0.714136 * (v - 128), y + 1.772 * (u - 128)
    ).map { it.roundToInt().coerceIn(0, 255) }

    private fun frame(width: Int, height: Int, value: (plane: Int, x: Int, y: Int) -> Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val bytes = ByteArray(FramePng.i420Size(width, height))
        for (y in 0 until height) for (x in 0 until width) bytes[y * width + x] = value(0, x, y).toByte()
        for (plane in 1..2) for (y in 0 until chromaHeight) for (x in 0 until chromaWidth) {
            bytes[width * height + (plane - 1) * chromaWidth * chromaHeight + y * chromaWidth + x] = value(plane, x, y).toByte()
        }
        return bytes
    }

    private fun rgbOf(i420: ByteArray, width: Int, height: Int) = ByteArray(width * height * 3).also { FramePng.toRgb(i420, width, height, it) }

    @Test fun convertsColourCloseToTheTextbookFormulaForEveryInput() {
        val pixel = ByteArray(3)
        for (y in 0..255 step 5) for (u in 0..255 step 5) for (v in 0..255 step 5) {
            FramePng.rgb(y, u, v, pixel, 0)
            val expected = reference(y, u, v)
            for (channel in 0..2) {
                assertEquals("y=$y u=$u v=$v channel $channel", expected[channel].toDouble(), (pixel[channel].toInt() and 0xFF).toDouble(), 1.0)
            }
        }
    }

    @Test fun aGreyPixelKeepsItsExactLuminanceInAllThreeChannels() {
        val pixel = ByteArray(3)
        for (y in 0..255) {
            FramePng.rgb(y, 128, 128, pixel, 0)
            assertEquals(listOf(y, y, y), pixel.map { it.toInt() and 0xFF })
        }
        val width = 9
        val grey = rgbOf(frame(width, 5) { plane, x, y -> if (plane == 0) x * 20 + y else 128 }, width, 5)
        for (y in 0 until 5) for (x in 0 until width) {
            assertEquals(listOf(x * 20 + y, x * 20 + y, x * 20 + y), grey.copyOfRange((y * width + x) * 3, (y * width + x) * 3 + 3).map { it.toInt() and 0xFF })
        }
    }

    @Test fun convertsAnOddSizedFrameWithColourSharedByEachTwoByTwoBlock() {
        val width = 37
        val height = 23
        val random = Random(5)
        val i420 = frame(width, height) { plane, x, y -> if (plane == 0) (x * 7 + y * 3) % 256 else 60 + random.nextInt(140) }
        val rgb = rgbOf(i420, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaSize = chromaWidth * ((height + 1) / 2)
        val expected = ByteArray(3)
        for (y in 0 until height) for (x in 0 until width) {
            val chroma = (y / 2) * chromaWidth + x / 2
            FramePng.rgb(i420[y * width + x].toInt() and 0xFF, i420[width * height + chroma].toInt() and 0xFF,
                i420[width * height + chromaSize + chroma].toInt() and 0xFF, expected, 0)
            assertArrayEquals("pixel $x,$y", expected, rgb.copyOfRange((y * width + x) * 3, (y * width + x) * 3 + 3))
        }
    }

    @Test fun writesAValidRgbPngThatDecodesToTheSamePixels() {
        val width = 37
        val height = 23
        val random = Random(9)
        val rgb = ByteArray(width * height * 3) { random.nextInt(256).toByte() }
        val image = decode(FramePng.encode(rgb, width, height))
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        assertEquals(8, image.bitDepth)
        assertEquals(2, image.colourType)
        assertArrayEquals(rgb, image.rgb)
    }

    @Test fun aDarkFrameWithOneLedIsFarSmallerThanTheRawFrame() {
        val width = 1280
        val height = 960
        val i420 = frame(width, height) { plane, x, y ->
            if (plane != 0) 128 else if (x in 600..680 && y in 300..380) (if (y % 18 < 9) 200 else 40) else 0
        }
        val rgb = rgbOf(i420, width, height)
        val bytes = FramePng.encode(rgb, width, height)
        assertArrayEquals(rgb, decode(bytes).rgb)
        assertTrue("PNG is ${bytes.size} bytes", bytes.size < width * height / 10)
    }

    @Test fun refusesAFrameShorterThanItsStatedSize() {
        assertTrue(runCatching { FramePng.toRgb(ByteArray(20), 4, 4, ByteArray(48)) }.isFailure)
        assertTrue(runCatching { FramePng.encode(ByteArray(20), 4, 4) }.isFailure)
    }
}
