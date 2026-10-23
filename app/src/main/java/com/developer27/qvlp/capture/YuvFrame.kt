package com.developer27.qvlp.capture

import android.graphics.ImageFormat
import android.media.Image

internal data class YuvFrame(
    val timestamp: Long, val width: Int, val height: Int, val bytes: ByteArray,
    val crop: List<Int>, val rowStrides: List<Int>, val pixelStrides: List<Int>
) {
    companion object {
        fun byteCount(width: Int, height: Int): Long = width.toLong() * height +
            2L * ((width + 1) / 2) * ((height + 1) / 2)

        /** Normalize planar or interleaved Android YUV into tightly packed I420. */
        fun copy(image: Image): YuvFrame {
            require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
            val width = image.width
            val height = image.height
            val count = byteCount(width, height)
            require(count in 1..Int.MAX_VALUE.toLong())
            val packed = ByteArray(count.toInt())
            var out = 0
            image.planes.forEachIndexed { index, plane ->
                val columns = if (index == 0) width else (width + 1) / 2
                val rows = if (index == 0) height else (height + 1) / 2
                val source = plane.buffer.duplicate()
                val base = source.position()
                for (row in 0 until rows) {
                    val start = base + row * plane.rowStride
                    require(start.toLong() + (columns - 1L) * plane.pixelStride < source.limit()) {
                        "Camera supplied an incomplete YUV plane."
                    }
                    if (plane.pixelStride == 1) {
                        source.position(start)
                        source.get(packed, out, columns)
                        out += columns
                    } else {
                        for (column in 0 until columns) packed[out++] = source.get(start + column * plane.pixelStride)
                    }
                }
            }
            val crop = image.cropRect
            return YuvFrame(image.timestamp, width, height, packed,
                listOf(crop.left, crop.top, crop.right, crop.bottom),
                image.planes.map { it.rowStride }, image.planes.map { it.pixelStride })
        }
    }
}
