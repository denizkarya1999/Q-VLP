package com.developer27.qvlp.capture

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

internal data class YuvFrame(
    val timestamp: Long, val width: Int, val height: Int, val bytes: ByteArray,
    val crop: List<Int>, val rowStrides: List<Int>, val pixelStrides: List<Int>
) {
    companion object {
        fun byteCount(width: Int, height: Int): Long = width.toLong() * height +
            2L * ((width + 1) / 2) * ((height + 1) / 2)

        /**
         * Copies one plane into [target] at [offset] without padding, and returns the next free offset.
         * Rows are read in bulk: this runs on the camera thread for every frame, and reading interleaved
         * chroma one byte at a time took two thirds of the frame interval and delayed the first frames.
         */
        fun copyPlane(source: ByteBuffer, rowStride: Int, pixelStride: Int, columns: Int, rows: Int,
                      target: ByteArray, offset: Int): Int {
            val base = source.position()
            val span = (columns - 1) * pixelStride + 1
            require(rows == 0 || base.toLong() + (rows - 1L) * rowStride + span <= source.limit()) {
                "Camera supplied an incomplete YUV plane."
            }
            var out = offset
            if (pixelStride == 1 && rowStride == columns) {
                source.get(target, out, rows * columns)
                return out + rows * columns
            }
            val row = if (pixelStride == 1) null else ByteArray(span)
            for (index in 0 until rows) {
                source.position(base + index * rowStride)
                if (row == null) {
                    source.get(target, out, columns)
                    out += columns
                } else {
                    source.get(row, 0, span)
                    var from = 0
                    for (column in 0 until columns) { target[out++] = row[from]; from += pixelStride }
                }
            }
            return out
        }

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
                out = copyPlane(plane.buffer.duplicate(), plane.rowStride, plane.pixelStride,
                    if (index == 0) width else (width + 1) / 2, if (index == 0) height else (height + 1) / 2, packed, out)
            }
            val crop = image.cropRect
            return YuvFrame(image.timestamp, width, height, packed,
                listOf(crop.left, crop.top, crop.right, crop.bottom),
                image.planes.map { it.rowStride }, image.planes.map { it.pixelStride })
        }
    }
}
