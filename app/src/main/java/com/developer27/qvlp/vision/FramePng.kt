package com.developer27.qvlp.vision

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Turns a camera frame into a lossless colour PNG. PNG rather than JPEG: compression must not blur the LED stripes.
 * The camera delivers luminance and half-resolution colour (I420), so the picture is converted to RGB once.
 */
internal object FramePng {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Bytes of a tightly packed I420 frame: the Y plane, then U and V at half resolution. */
    fun i420Size(width: Int, height: Int): Int = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)

    // Full-range BT.601, which is what Android cameras deliver, as lookup tables: every frame has over a
    // million pixels, so the conversion does no multiplication or branching per pixel.
    private val RED_FROM_V = IntArray(256) { (91881 * (it - 128) + 32768) shr 16 }
    private val BLUE_FROM_U = IntArray(256) { (116130 * (it - 128) + 32768) shr 16 }
    private val GREEN_FROM_U = IntArray(256) { 22554 * (it - 128) }
    private val GREEN_FROM_V = IntArray(256) { 46802 * (it - 128) }
    private const val CLAMP_OFFSET = 256
    private val CLAMP = ByteArray(256 + 2 * CLAMP_OFFSET) { (it - CLAMP_OFFSET).coerceIn(0, 255).toByte() }

    /** One pixel. A grey pixel (u = v = 128) keeps its exact luminance in all three channels. */
    fun rgb(y: Int, u: Int, v: Int, target: ByteArray, offset: Int) {
        target[offset] = CLAMP[y + RED_FROM_V[v] + CLAMP_OFFSET]
        target[offset + 1] = CLAMP[y - ((GREEN_FROM_U[u] + GREEN_FROM_V[v] + 32768) shr 16) + CLAMP_OFFSET]
        target[offset + 2] = CLAMP[y + BLUE_FROM_U[u] + CLAMP_OFFSET]
    }

    /** Fills [target] with width × height RGB pixels. [i420] holds at least [i420Size] bytes. */
    fun toRgb(i420: ByteArray, width: Int, height: Int, target: ByteArray) {
        require(width > 0 && height > 0 && i420.size >= i420Size(width, height) && target.size >= width * height * 3) {
            "The frame is smaller than its stated size."
        }
        val chromaWidth = (width + 1) / 2
        val uPlane = width * height
        val vPlane = uPlane + chromaWidth * ((height + 1) / 2)
        val clamp = CLAMP
        for (y in 0 until height) {
            val chroma = (y / 2) * chromaWidth
            var luma = y * width
            val rowEnd = luma + width
            var out = luma * 3
            // Each colour sample is shared by two pixels in a row, so its part is worked out once for both.
            for (sample in 0 until chromaWidth) {
                val u = i420[uPlane + chroma + sample].toInt() and 0xFF
                val v = i420[vPlane + chroma + sample].toInt() and 0xFF
                val red = RED_FROM_V[v] + CLAMP_OFFSET
                val green = CLAMP_OFFSET - ((GREEN_FROM_U[u] + GREEN_FROM_V[v] + 32768) shr 16)
                val blue = BLUE_FROM_U[u] + CLAMP_OFFSET
                var level = i420[luma++].toInt() and 0xFF
                target[out] = clamp[level + red]; target[out + 1] = clamp[level + green]; target[out + 2] = clamp[level + blue]
                out += 3
                if (luma < rowEnd) {
                    level = i420[luma++].toInt() and 0xFF
                    target[out] = clamp[level + red]; target[out + 1] = clamp[level + green]; target[out + 2] = clamp[level + blue]
                    out += 3
                }
            }
        }
    }

    /** The PNG file for width × height RGB pixels. */
    fun encode(rgb: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && rgb.size >= width * height * 3) { "The picture is smaller than its stated size." }
        val stride = width * 3
        // Each row is its filter type (0, none) followed by its pixels, copied in bulk: no work per byte.
        val rows = ByteArray(height * (stride + 1))
        for (y in 0 until height) System.arraycopy(rgb, y * stride, rows, y * (stride + 1) + 1, stride)
        val packed = ByteArrayOutputStream(maxOf(4096, rows.size / 4))
        val deflater = Deflater(COMPRESSION)
        try {
            deflater.setInput(rows)
            deflater.finish()
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
        } finally {
            deflater.end()
        }
        val file = ByteArrayOutputStream(packed.size() + 64)
        val data = DataOutputStream(file)
        data.write(SIGNATURE)
        val header = ByteArrayOutputStream(13)
        DataOutputStream(header).apply {
            writeInt(width); writeInt(height)
            writeByte(8) // bits per sample
            writeByte(2) // RGB
            writeByte(0); writeByte(0); writeByte(0) // deflate, standard filtering, not interlaced
        }
        chunk(data, "IHDR", header.toByteArray())
        chunk(data, "IDAT", packed.toByteArray())
        chunk(data, "IEND", ByteArray(0))
        data.flush()
        return file.toByteArray()
    }

    private fun chunk(output: DataOutputStream, type: String, body: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(body.size)
        output.write(name)
        output.write(body)
        output.writeInt(CRC32().apply { update(name); update(body) }.value.toInt())
    }

    // The fastest setting: every frame is encoded before the result is shown, and camera frames gain little from more.
    private const val COMPRESSION = Deflater.BEST_SPEED
}
