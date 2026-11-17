package com.developer27.qvlp.camera

import java.math.BigDecimal

internal object CaptureSettings {
    /** A new installation starts at a 6000 Hz rolling shutter rate and ISO 100. */
    const val DEFAULT_SHUTTER_RATE_HZ = 6000
    const val DEFAULT_EXPOSURE_NS = 1_000_000_000L / DEFAULT_SHUTTER_RATE_HZ
    const val DEFAULT_ISO_RATE = 100
    const val DEFAULT_FPS = 30

    /** Accept microseconds with at most nanosecond precision; reject overflow and zero. */
    fun parseExposureMicros(raw: String?): Long? = try {
        raw?.trim()?.replace(',', '.')?.toBigDecimal()?.movePointRight(3)
            ?.longValueExact()?.takeIf { it > 0 }
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    /**
     * Rolling shutter rates in Hz, as RoTemplate and LiFind list them, extended to the short exposures
     * that keep LED stripes sharp. The exposure time is one period of the rate: 6000 Hz is about 167 µs.
     */
    val SHUTTER_RATES_HZ = listOf(5, 10, 15, 50, 100, 200, 250, 500, 650, 750, 800, 900, 950, 1000, 2000, 3000, 4000,
        5000, 6000, 8000, 10000, 12000, 15000, 20000, 25000, 30000, 40000, 50000)

    fun exposureNanos(rateHz: Int): Long {
        require(rateHz > 0)
        return 1_000_000_000L / rateHz
    }

    fun shutterRateHz(exposureNs: Long): Int {
        require(exposureNs > 0)
        return Math.round(1_000_000_000.0 / exposureNs).toInt()
    }

    /** The listed rates whose exposure the camera can actually use. */
    fun shutterRates(minExposureNs: Long, maxExposureNs: Long): List<Int> =
        SHUTTER_RATES_HZ.filter { exposureNanos(it) in minExposureNs..maxExposureNs }

    fun exposureMicros(nanos: Long): String =
        BigDecimal.valueOf(nanos, 3).stripTrailingZeros().toPlainString()

    fun frameDurationNanos(fps: Int): Long {
        require(fps > 0)
        return (1_000_000_000L + fps - 1) / fps
    }

    // Kept solely to read the old template preferences during a one-time migration.
    const val DEFAULT_SHUTTER_HZ = 6000
    const val DEFAULT_ISO = 1100
    const val MIN_ISO = 50
    const val MAX_ISO = 25600

    fun exposureTimeNanos(rawShutterHz: String?): Long? {
        val shutterHz = rawShutterHz?.toLongOrNull() ?: DEFAULT_SHUTTER_HZ.toLong()
        return if (shutterHz > 0L) 1_000_000_000L / shutterHz else null
    }

    fun normalizeIso(rawIso: String?): Int? {
        return rawIso?.trim()?.toIntOrNull()?.coerceIn(MIN_ISO, MAX_ISO)
    }
}
