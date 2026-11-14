package com.developer27.qvlp.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Defaults from the app description (blob detection table and quality rules). Grey levels are 8-bit Y values.
 * Every value is saved with each capture, since a real room may need different thresholds than the simulator.
 */
internal data class LedDetectionSettings(
    val ledFrequenciesHz: List<Double> = listOf(1800.0, 2800.0, 3800.0, 4800.0),
    val backgroundSampleStep: Int = 8,
    val smoothingSigmaPx: Double = 1.0,
    val closingRows: Int = 21,
    val thresholdAboveBackground: Int = 25,
    val minBlobAreaPx: Int = 400,
    val borderMarginPx: Int = 17,
    val saturationLevel: Int = 255,
    val minProfileRows: Int = 24,
    val minStripePeriods: Double = 2.0,
    val minFitSnrDb: Double = 8.0,
    val frequencyTolerance: Double = 0.05,
    // "Roughly round": lenient, so an LED seen at an angle still passes.
    val maxBoxAspect: Double = 2.0,
    val minBoxFill: Double = 0.5
) {
    /**
     * The closing must bridge the longest dark stripe. The 21-row default suits the simulator's 30 µs rows;
     * a faster sensor spreads one blink period over more rows, so the window grows with it.
     */
    fun closingRowsFor(rowTimeSeconds: Double?): Int {
        if (rowTimeSeconds == null || rowTimeSeconds <= 0.0 || ledFrequenciesHz.isEmpty()) return closingRows or 1
        val longestPeriodRows = 1.0 / (ledFrequenciesHz.min() * rowTimeSeconds)
        val needed = ceil(0.75 * longestPeriodRows).toInt() or 1
        return maxOf(closingRows or 1, needed).coerceAtMost(MAX_CLOSING_ROWS)
    }

    companion object { const val MAX_CLOSING_ROWS = 201 }
}

internal data class StripeFit(val cyclesPerRow: Double, val snrDb: Double, val periodsVisible: Double) {
    val periodRows: Double get() = 1.0 / cyclesPerRow
}

/** One bright region. [u] is the column and [v] the row of its centre; the top-left pixel centre is (0, 0). */
internal data class LedBlob(
    val u: Double, val v: Double, val areaPx: Int, val peakLevel: Int,
    val left: Int, val top: Int, val width: Int, val height: Int,
    val touchesBorder: Boolean, val saturated: Boolean, val roughlyRound: Boolean,
    val stripe: StripeFit?, val frequencyHz: Double?
)

/** [blob] is the single blob whose stripe frequency matched this LED, even when a quality rule rejected it. */
internal data class LedReading(val detected: Boolean, val blob: LedBlob?)

internal data class FrameLeds(val leds: List<LedReading>, val blobs: List<LedBlob>, val background: Double, val closingRows: Int)

/**
 * Finds LED blobs in one 8-bit luminance frame and names each by its rolling-shutter stripe frequency.
 * Follows `extract` in the project's simulator. Not thread-safe: it reuses its buffers, so use one per thread.
 */
internal class LedDetector(private val settings: LedDetectionSettings = LedDetectionSettings()) {
    private var smooth = ShortArray(0)
    private var scratch = ShortArray(0)
    private var prefix = ShortArray(0)
    private var suffix = ShortArray(0)
    private var runRow = IntArray(1024)
    private var runStart = IntArray(1024)
    private var runEnd = IntArray(1024)
    private var runParent = IntArray(1024)
    private var runs = 0
    private var crop = ByteArray(0)

    /**
     * [luma] holds at least width × height bytes, row by row. Without a row time, blobs are found but not named.
     * Only the areas around bright pixels are processed; the result is the same as [detectWhole].
     */
    fun detect(luma: ByteArray, width: Int, height: Int, rowTimeSeconds: Double?): FrameLeds =
        run(luma, width, height, rowTimeSeconds, regions = true)

    /** The reference: every pixel of the frame goes through smoothing and closing. */
    internal fun detectWhole(luma: ByteArray, width: Int, height: Int, rowTimeSeconds: Double?): FrameLeds =
        run(luma, width, height, rowTimeSeconds, regions = false)

    private fun run(luma: ByteArray, width: Int, height: Int, rowTimeSeconds: Double?, regions: Boolean): FrameLeds {
        require(width > 8 && height > 8 && luma.size >= width * height) { "The frame is smaller than its stated size." }
        val rowTime = rowTimeSeconds?.takeIf { it.isFinite() && it > 0.0 }
        val background = background(luma, width, height)
        val window = settings.closingRowsFor(rowTime)
        val areas = if (regions) brightRegions(luma, width, height, background, window) else null
        val blobs = if (areas == null) blobs(luma, width, height, background, window, rowTime)
            else areas.flatMap { area -> region(luma, width, height, area, background, window, rowTime) }
                .sortedWith(compareBy({ it.v }, { it.u }))
        return FrameLeds(identify(blobs), blobs, background, window)
    }

    /** Every blob of one image, in that image's own coordinates. */
    private fun blobs(luma: ByteArray, width: Int, height: Int, background: Double, window: Int, rowTime: Double?): List<LedBlob> {
        smooth(luma, width, height)
        val pad = (background * SCALE).toInt().toShort()
        slide(smooth, scratch, width, height, window, pad, max = true)
        slide(scratch, smooth, width, height, window, pad, max = false)
        label(width, height, ((background + settings.thresholdAboveBackground) * SCALE).toInt())
        return measure(luma, width, height, rowTime)
    }

    /**
     * Disjoint rectangles (left, top, right, bottom) that contain every pixel able to end up in a blob, or null
     * when they would cover most of the frame anyway.
     *
     * Smoothing is a weighted average and closing only takes maxima and minima, so a pixel can pass the threshold
     * only if a raw pixel above it lies within their reach: half the closing window plus the smoothing radius.
     * Its value depends on raw pixels up to three half-windows plus two radii away, so each rectangle keeps that
     * margin. Inside it the blobs are then exactly those of the whole frame, and everything outside is background.
     */
    private fun brightRegions(luma: ByteArray, width: Int, height: Int, background: Double, window: Int): List<IntArray>? {
        val limit = floor(background + settings.thresholdAboveBackground).toInt()
        val tilesX = (width + TILE - 1) / TILE
        val tilesY = (height + TILE - 1) / TILE
        val bright = BooleanArray(tilesX * tilesY)
        var found = false
        for (y in 0 until height) {
            val base = y * width
            val tileRow = (y / TILE) * tilesX
            for (x in 0 until width) {
                if ((luma[base + x].toInt() and 0xFF) > limit) { bright[tileRow + x / TILE] = true; found = true }
            }
        }
        if (!found) return emptyList()
        val radius = kernel(settings.smoothingSigmaPx).size / 2
        val marginX = (2 * radius + TILE - 1) / TILE
        val marginY = (3 * (window / 2) + 2 * radius + TILE - 1) / TILE
        val covered = BooleanArray(tilesX * tilesY)
        var count = 0
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) if (bright[ty * tilesX + tx]) {
            for (y in maxOf(0, ty - marginY)..minOf(tilesY - 1, ty + marginY)) {
                for (x in maxOf(0, tx - marginX)..minOf(tilesX - 1, tx + marginX)) {
                    if (!covered[y * tilesX + x]) { covered[y * tilesX + x] = true; count++ }
                }
            }
        }
        if (count > MAX_REGION_SHARE * covered.size) return null
        // Group touching tiles, then merge rectangles until none overlap: a blob must never be split between two.
        val boxes = ArrayList<IntArray>()
        val stack = IntArray(covered.size)
        for (start in covered.indices) {
            if (!covered[start]) continue
            var top = 0
            stack[top++] = start
            covered[start] = false
            val box = intArrayOf(tilesX, tilesY, -1, -1)
            while (top > 0) {
                val tile = stack[--top]
                val tx = tile % tilesX
                val ty = tile / tilesX
                box[0] = minOf(box[0], tx); box[1] = minOf(box[1], ty); box[2] = maxOf(box[2], tx); box[3] = maxOf(box[3], ty)
                if (tx > 0 && covered[tile - 1]) { covered[tile - 1] = false; stack[top++] = tile - 1 }
                if (tx < tilesX - 1 && covered[tile + 1]) { covered[tile + 1] = false; stack[top++] = tile + 1 }
                if (ty > 0 && covered[tile - tilesX]) { covered[tile - tilesX] = false; stack[top++] = tile - tilesX }
                if (ty < tilesY - 1 && covered[tile + tilesX]) { covered[tile + tilesX] = false; stack[top++] = tile + tilesX }
            }
            boxes.add(box)
        }
        var merged = true
        while (merged) {
            merged = false
            search@ for (first in boxes.indices) for (second in first + 1 until boxes.size) {
                val a = boxes[first]
                val b = boxes[second]
                if (a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]) {
                    boxes[first] = intArrayOf(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[2], b[2]), maxOf(a[3], b[3]))
                    boxes.removeAt(second)
                    merged = true
                    break@search
                }
            }
        }
        val areas = boxes.map { intArrayOf(it[0] * TILE, it[1] * TILE, minOf(width, (it[2] + 1) * TILE), minOf(height, (it[3] + 1) * TILE)) }
        return areas.takeIf { list -> list.sumOf { (it[2] - it[0]).toLong() * (it[3] - it[1]) } <= MAX_REGION_SHARE * width * height }
    }

    /** The blobs of one rectangle of the frame, placed back in frame coordinates. */
    private fun region(luma: ByteArray, width: Int, height: Int, area: IntArray, background: Double, window: Int,
                       rowTime: Double?): List<LedBlob> {
        val left = area[0]
        val top = area[1]
        val regionWidth = area[2] - left
        val regionHeight = area[3] - top
        if (crop.size < regionWidth * regionHeight) crop = ByteArray(regionWidth * regionHeight)
        for (row in 0 until regionHeight) {
            System.arraycopy(luma, (top + row) * width + left, crop, row * regionWidth, regionWidth)
        }
        val margin = settings.borderMarginPx
        return blobs(crop, regionWidth, regionHeight, background, window, rowTime).map { blob ->
            val x = blob.left + left
            val y = blob.top + top
            blob.copy(u = blob.u + left, v = blob.v + top, left = x, top = y,
                // The image edge that matters is the frame's, not the rectangle's.
                touchesBorder = x < margin || y < margin || x + blob.width > width - margin || y + blob.height > height - margin)
        }
    }

    /** Median of a sparse sample: LED blobs cover too few pixels to move it. */
    private fun background(luma: ByteArray, width: Int, height: Int): Double {
        val histogram = IntArray(256)
        var count = 0
        var row = 0
        while (row < height) {
            var index = row * width
            val end = index + width
            while (index < end) { histogram[luma[index].toInt() and 0xFF]++; count++; index += settings.backgroundSampleStep }
            row += settings.backgroundSampleStep
        }
        fun valueAt(rank: Int): Int {
            var seen = 0
            for (level in 0..255) { seen += histogram[level]; if (seen > rank) return level }
            return 255
        }
        return (valueAt((count - 1) / 2) + valueAt(count / 2)) / 2.0
    }

    /** Separable Gaussian with reflected borders. The result in [smooth] is the grey level × [SCALE]. */
    private fun smooth(luma: ByteArray, width: Int, height: Int) {
        val size = width * height
        if (smooth.size < size) { smooth = ShortArray(size); scratch = ShortArray(size) }
        val kernel = kernel(settings.smoothingSigmaPx)
        val radius = kernel.size / 2
        for (row in 0 until height) {
            val base = row * width
            for (column in 0 until width) {
                var sum = 0
                if (column >= radius && column < width - radius) {
                    for (tap in kernel.indices) sum += kernel[tap] * (luma[base + column + tap - radius].toInt() and 0xFF)
                } else {
                    for (tap in kernel.indices) sum += kernel[tap] * (luma[base + reflect(column + tap - radius, width)].toInt() and 0xFF)
                }
                scratch[base + column] = ((sum + 128) shr 8).toShort() // grey × 64
            }
        }
        val offsets = IntArray(kernel.size)
        for (row in 0 until height) {
            for (tap in kernel.indices) offsets[tap] = reflect(row + tap - radius, height) * width
            val base = row * width
            for (column in 0 until width) {
                var sum = 0
                for (tap in kernel.indices) sum += kernel[tap] * scratch[offsets[tap] + column]
                smooth[base + column] = ((sum + 32768) shr 16).toShort() // grey × 16
            }
        }
    }

    /**
     * Sliding maximum or minimum along each column (van Herk), over [window] rows centred on the pixel.
     * Rows outside the image count as background, as the app description specifies.
     */
    private fun slide(source: ShortArray, target: ShortArray, width: Int, height: Int, window: Int, pad: Short, max: Boolean) {
        val half = window / 2
        val padded = height + 2 * half
        if (prefix.size < padded * width) { prefix = ShortArray(padded * width); suffix = ShortArray(padded * width) }
        fun value(paddedRow: Int, column: Int): Short {
            val row = paddedRow - half
            return if (row < 0 || row >= height) pad else source[row * width + column]
        }
        for (row in 0 until padded) {
            val base = row * width
            val restart = row % window == 0
            for (column in 0 until width) {
                val current = value(row, column)
                prefix[base + column] = if (restart) current else pick(prefix[base - width + column], current, max)
            }
        }
        for (row in padded - 1 downTo 0) {
            val base = row * width
            val restart = row % window == window - 1 || row == padded - 1
            for (column in 0 until width) {
                val current = value(row, column)
                suffix[base + column] = if (restart) current else pick(suffix[base + width + column], current, max)
            }
        }
        for (row in 0 until height) {
            val first = row * width
            val last = (row + 2 * half) * width
            for (column in 0 until width) target[first + column] = pick(suffix[first + column], prefix[last + column], max)
        }
    }

    private fun pick(a: Short, b: Short, max: Boolean): Short = if ((a > b) == max) a else b

    /** Connected bright runs, joined between neighbouring rows where their columns overlap (4-connectivity). */
    private fun label(width: Int, height: Int, threshold: Int) {
        runs = 0
        var previousFirst = 0
        var previousEnd = 0
        for (row in 0 until height) {
            val base = row * width
            val rowFirst = runs
            var column = 0
            var candidate = previousFirst
            while (column < width) {
                if (smooth[base + column] <= threshold) { column++; continue }
                val start = column
                while (column < width && smooth[base + column] > threshold) column++
                val run = addRun(row, start, column)
                while (candidate < previousEnd && runEnd[candidate] <= start) candidate++
                var above = candidate
                while (above < previousEnd && runStart[above] < column) { union(run, above); above++ }
            }
            previousFirst = rowFirst
            previousEnd = runs
        }
    }

    private fun addRun(row: Int, start: Int, end: Int): Int {
        if (runs == runRow.size) {
            val size = runs * 2
            runRow = runRow.copyOf(size); runStart = runStart.copyOf(size); runEnd = runEnd.copyOf(size); runParent = runParent.copyOf(size)
        }
        runRow[runs] = row; runStart[runs] = start; runEnd[runs] = end; runParent[runs] = runs
        return runs++
    }

    private fun root(run: Int): Int {
        var current = run
        while (runParent[current] != current) { runParent[current] = runParent[runParent[current]]; current = runParent[current] }
        return current
    }

    private fun union(a: Int, b: Int) {
        val first = root(a)
        val second = root(b)
        if (first != second) runParent[maxOf(first, second)] = minOf(first, second)
    }

    private fun measure(luma: ByteArray, width: Int, height: Int, rowTime: Double?): List<LedBlob> {
        val area = HashMap<Int, Int>()
        for (run in 0 until runs) area.merge(root(run), runEnd[run] - runStart[run], Int::plus)
        val kept = area.filterValues { it >= settings.minBlobAreaPx }.keys
        if (kept.isEmpty()) return emptyList()
        val members = HashMap<Int, MutableList<Int>>()
        for (run in 0 until runs) root(run).let { if (it in kept) members.getOrPut(it) { ArrayList() }.add(run) }
        return members.values.map { blob(it, luma, width, height, rowTime) }.sortedWith(compareBy({ it.v }, { it.u }))
    }

    /** [members] are one blob's runs, in row order. */
    private fun blob(members: List<Int>, luma: ByteArray, width: Int, height: Int, rowTime: Double?): LedBlob {
        var area = 0L
        var sumU = 0.0
        var sumV = 0.0
        var left = width
        var right = 0
        var peak = 0
        for (run in members) {
            val start = runStart[run]
            val end = runEnd[run]
            val length = end - start
            area += length
            sumU += length * (start + end - 1) / 2.0
            sumV += length.toDouble() * runRow[run]
            left = minOf(left, start)
            right = maxOf(right, end - 1)
            val base = runRow[run] * width
            for (column in start until end) peak = maxOf(peak, luma[base + column].toInt() and 0xFF)
        }
        val top = runRow[members.first()]
        val bottom = runRow[members.last()]
        val boxWidth = right - left + 1
        val boxHeight = bottom - top + 1
        val u = sumU / area
        val v = sumV / area
        val margin = settings.borderMarginPx
        val aspect = maxOf(boxWidth, boxHeight).toDouble() / minOf(boxWidth, boxHeight)
        val stripe = stripes(members, luma, width, u, boxWidth, top, boxHeight, rowTime)
        return LedBlob(u, v, area.toInt(), peak, left, top, boxWidth, boxHeight,
            touchesBorder = left < margin || top < margin || right >= width - margin || bottom >= height - margin,
            saturated = peak >= settings.saturationLevel,
            roughlyRound = aspect <= settings.maxBoxAspect && area >= settings.minBoxFill * boxWidth * boxHeight,
            stripe = stripe, frequencyHz = if (stripe != null && rowTime != null) stripe.cyclesPerRow / rowTime else null)
    }

    /** Mean raw level per row through the blob's central 80% of columns, then a sinusoid fit. */
    private fun stripes(members: List<Int>, luma: ByteArray, width: Int, u: Double, boxWidth: Int, top: Int,
                        boxHeight: Int, rowTime: Double?): StripeFit? {
        val firstColumn = ceil(u - 0.4 * boxWidth).toInt()
        val lastColumn = floor(u + 0.4 * boxWidth).toInt()
        val counts = IntArray(boxHeight)
        val sums = LongArray(boxHeight)
        for (run in members) {
            val start = maxOf(runStart[run], firstColumn)
            val end = minOf(runEnd[run] - 1, lastColumn)
            if (end < start) continue
            val row = runRow[run]
            val base = row * width
            var sum = 0L
            for (column in start..end) sum += luma[base + column].toInt() and 0xFF
            counts[row - top] += end - start + 1
            sums[row - top] += sum
        }
        val widest = counts.max()
        val rows = (0 until boxHeight).filter { counts[it] > 0 && counts[it] >= 0.5 * widest }
        if (rows.size < settings.minProfileRows) return null
        val r = DoubleArray(rows.size) { rows[it].toDouble() }
        val y = DoubleArray(rows.size) { sums[rows[it]].toDouble() / counts[rows[it]] }
        return fitStripes(r, y, rowTime)
    }

    /** Removes the slow blob-shape trend, then scans stripe rates for the best least-squares sinusoid. */
    internal fun fitStripes(r: DoubleArray, y: DoubleArray, rowTime: Double?): StripeFit? {
        val residual = detrend(r, y)
        val span = r.last() - r.first() + 1.0
        var lowest = 1.5 / span
        var highest = MAX_CYCLES_PER_ROW
        if (rowTime != null && settings.ledFrequenciesHz.isNotEmpty()) {
            lowest = maxOf(lowest, 0.7 * settings.ledFrequenciesHz.min() * rowTime)
            highest = minOf(highest, 1.3 * settings.ledFrequenciesHz.max() * rowTime)
        }
        if (highest <= lowest) return null
        val coarse = scan(r, residual, lowest, highest, 0.0005)
        val fine = scan(r, residual, maxOf(coarse[0] - 0.002, 1e-4), coarse[0] + 0.002, 0.00005)
        val cycles = fine[0]
        val amplitude = hypot(fine[1], fine[2])
        var squares = 0.0
        var mean = 0.0
        val error = DoubleArray(r.size) {
            val angle = 2 * PI * cycles * (r[it] - r[0])
            residual[it] - (fine[1] * cos(angle) + fine[2] * sin(angle))
        }
        for (value in error) mean += value / error.size
        for (value in error) squares += (value - mean) * (value - mean) / error.size
        val snr = 20 * log10(amplitude / (sqrt(squares) + 1e-9))
        return StripeFit(cycles, snr, r.size * cycles)
    }

    /** Returns the best rate and its cosine and sine amplitudes. */
    private fun scan(r: DoubleArray, y: DoubleArray, from: Double, to: Double, step: Double): DoubleArray {
        val best = doubleArrayOf(from, 0.0, 0.0)
        var bestExplained = Double.NEGATIVE_INFINITY
        var cycles = from
        while (cycles < to) {
            var cc = 0.0; var ss = 0.0; var cs = 0.0; var yc = 0.0; var ys = 0.0
            for (index in r.indices) {
                val angle = 2 * PI * cycles * (r[index] - r[0])
                val c = cos(angle)
                val s = sin(angle)
                cc += c * c; ss += s * s; cs += c * s; yc += c * y[index]; ys += s * y[index]
            }
            val determinant = cc * ss - cs * cs + 1e-12
            val a = (ss * yc - cs * ys) / determinant
            val b = (cc * ys - cs * yc) / determinant
            val explained = a * yc + b * ys
            if (explained > bestExplained) { bestExplained = explained; best[0] = cycles; best[1] = a; best[2] = b }
            cycles += step
        }
        return best
    }

    /** Subtracts the least-squares quadratic. */
    private fun detrend(r: DoubleArray, y: DoubleArray): DoubleArray {
        val centre = r.average()
        val scale = maxOf((r.last() - r.first()) / 2.0, 1.0)
        val m = Array(3) { DoubleArray(4) }
        for (index in r.indices) {
            val x = (r[index] - centre) / scale
            val powers = doubleArrayOf(1.0, x, x * x)
            for (row in 0..2) {
                for (column in 0..2) m[row][column] += powers[row] * powers[column]
                m[row][3] += powers[row] * y[index]
            }
        }
        for (pivot in 0..2) {
            val best = (pivot..2).maxBy { abs(m[it][pivot]) }
            m[pivot] = m[best].also { m[best] = m[pivot] }
            if (abs(m[pivot][pivot]) < 1e-12) return DoubleArray(y.size) { y[it] - y.average() }
            for (row in 0..2) if (row != pivot) {
                val factor = m[row][pivot] / m[pivot][pivot]
                for (column in pivot..3) m[row][column] -= factor * m[pivot][column]
            }
        }
        val p = DoubleArray(3) { m[it][3] / m[it][it] }
        return DoubleArray(y.size) {
            val x = (r[it] - centre) / scale
            y[it] - (p[0] + p[1] * x + p[2] * x * x)
        }
    }

    /**
     * A blob is named by the one configured frequency within tolerance of its estimate. An LED is detected only
     * when exactly one blob carries its frequency and that blob passes every quality rule; a doubtful LED is dropped.
     */
    internal fun identify(blobs: List<LedBlob>): List<LedReading> {
        val candidates = settings.ledFrequenciesHz.map { ArrayList<LedBlob>() }
        for (blob in blobs) {
            val frequency = blob.frequencyHz ?: continue
            val stripe = blob.stripe ?: continue
            if (stripe.periodsVisible < settings.minStripePeriods || stripe.snrDb < settings.minFitSnrDb) continue
            val matches = settings.ledFrequenciesHz.indices.filter {
                abs(frequency - settings.ledFrequenciesHz[it]) <= settings.frequencyTolerance * settings.ledFrequenciesHz[it]
            }
            if (matches.size == 1) candidates[matches[0]].add(blob)
        }
        return candidates.map { found ->
            val blob = found.singleOrNull()
            LedReading(blob != null && !blob.touchesBorder && !blob.saturated && blob.roughlyRound, blob)
        }
    }

    private companion object {
        const val SCALE = 16
        const val MAX_CYCLES_PER_ROW = 0.45
        const val TILE = 16
        // Past this share of the frame, cutting it into rectangles saves nothing.
        const val MAX_REGION_SHARE = 0.6

        fun reflect(index: Int, size: Int): Int = when {
            index < 0 -> -index - 1
            index >= size -> 2 * size - index - 1
            else -> index
        }

        /** Fixed-point weights summing to 2^14, truncated at 4σ as SciPy does. */
        fun kernel(sigma: Double): IntArray {
            val radius = (4.0 * sigma + 0.5).toInt().coerceAtLeast(1)
            val weights = DoubleArray(2 * radius + 1) { exp(-0.5 * ((it - radius) / sigma) * ((it - radius) / sigma)) }
            val total = weights.sum()
            val fixed = IntArray(weights.size) { (weights[it] / total * 16384).roundToInt() }
            fixed[radius] += 16384 - fixed.sum()
            return fixed
        }
    }
}
