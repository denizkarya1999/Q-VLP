package com.developer27.qvlp.camera

import java.math.BigDecimal

internal object CaptureSettings {
    const val DEFAULT_EXPOSURE_NS = 100_000L
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
