package com.developer27.qvlp.camera

/**
 * What a capture needs from the camera. The rolling shutter rate, ISO and FPS rate are the operator's choice:
 * every value Settings accepts is accepted here, so choosing one never stops a capture.
 */
internal object ResearchCapturePolicy {
    const val MIN_FPS = 30
    // Ceil(1 second / 30): allow only integer-nanosecond rounding.
    const val MAX_FRAME_DURATION_NS = 33_333_334L
    // A nominal 30 fps sensor mode may quantize timing to whole sensor lines.
    // Pixel 8a reports 33,340,371 ns (29.9937 fps). Bound rounding to 0.05%
    // of the 30 fps period; do not change requested rates or recorded metadata.
    const val MAX_CONFIRMED_FRAME_DURATION_NS = MAX_FRAME_DURATION_NS + MAX_FRAME_DURATION_NS / 2_000
    // A sensor rounds a frame up to whole lines of about 0.03 ms. At 30 fps and faster the 0.05% above
    // covers that on the tested phones and is kept as it is. A slower rate the operator chose gets a
    // tenth of a millisecond, which no whole line exceeds.
    const val CHOSEN_RATE_ROUNDING_NS = 100_000L

    /** The period of [fps], rounded up to a whole nanosecond. */
    fun frameDurationNs(fps: Int): Long = CaptureSettings.frameDurationNanos(fps)

    /** The longest frame the camera may report while it runs at [fps]. */
    fun maxConfirmedFrameDurationNs(fps: Int): Long {
        val period = frameDurationNs(fps)
        return period + if (fps >= MIN_FPS) period / 2_000 else maxOf(period / 2_000, CHOSEN_RATE_ROUNDING_NS)
    }

    // An output must match the sensor aspect ratio, or be a 4:3 output of a slightly wider sensor.
    // That case keeps every sensor row and crops under 3% of the width. The Pixel 8a front sensor
    // is 4208 × 3072 (1.370), so its 4:3 outputs are 0.037 narrower; 16:9 and 3:2 remain excluded.
    const val ASPECT_RATIO_TOLERANCE = 0.02
    const val MAX_WIDTH_CROP_ASPECT_DIFFERENCE = 0.04

    fun coversSensor(width: Int, height: Int, sensorAspectRatio: Double): Boolean {
        val narrowerBy = sensorAspectRatio - width.toDouble() / height
        return narrowerBy > -ASPECT_RATIO_TOLERANCE && narrowerBy < MAX_WIDTH_CROP_ASPECT_DIFFERENCE
    }

    /** Whether a reported frame is slower than the exact period of [fps] and still within its rounding. */
    fun usesFrameTimingTolerance(frameDurationNs: Long, fps: Int = MIN_FPS): Boolean =
        frameDurationNs > frameDurationNs(fps) && frameDurationNs <= maxConfirmedFrameDurationNs(fps)

    enum class Issue(val message: String) {
        ZOOM("Research capture requires 1× zoom. Return zoom to 1× with the − button."),
        RESOLUTION("This camera has no resolution that matches its sensor shape at 30 fps or faster, which research capture requires. Switch to the other camera."),
        EXPOSURE("Choose a rolling shutter rate in Settings."),
        ISO("Choose a fixed manual ISO supported by this camera."),
        FRAME_RATE("Choose an FPS rate this camera supports in Settings."),
        FOCUS("This camera cannot lock or fix its focus, which research capture requires. Switch to the other camera."),
        WHITE_BALANCE("This camera cannot lock its white balance or use a fixed preset, which research capture requires. Switch to the other camera."),
        CONFIRMING("Waiting for the camera to confirm stable research settings."),
        MISSING_MEASUREMENTS("The camera has not reported exposure, ISO and frame duration; research settings cannot be confirmed."),
        REPORTED_FRAME_RATE("The camera reports a frame rate below the selected FPS rate, beyond the allowed timing rounding. Reopen the app or switch to the other camera."),
        REPORTED_GEOMETRY("Waiting for the camera to confirm the requested 1× sensor crop.")
    }

    fun settingsIssue(zoom: Float, fixedResolution: Boolean, exposureNs: Long,
                      fixedIso: Boolean, fps: Int, fixedFocus: Boolean, fixedWhiteBalance: Boolean): Issue? = when {
        !zoom.isFinite() || kotlin.math.abs(zoom - 1f) > 0.0001f -> Issue.ZOOM
        !fixedResolution -> Issue.RESOLUTION
        exposureNs < 1 -> Issue.EXPOSURE
        !fixedIso -> Issue.ISO
        fps < 1 -> Issue.FRAME_RATE
        !fixedFocus -> Issue.FOCUS
        !fixedWhiteBalance -> Issue.WHITE_BALANCE
        else -> null
    }

    data class Reading(val exposureNs: Long?, val iso: Int?, val frameDurationNs: Long?, val geometryMatches: Boolean)

    /** Two identical manual readings are needed after opening or changing settings. */
    class Confirmation {
        private var previous: Reading? = null
        private var matching = 0
        var issue: Issue? = Issue.CONFIRMING
            private set

        fun reset() {
            previous = null
            matching = 0
            issue = Issue.CONFIRMING
        }

        /** [fps] is the FPS rate the camera was asked for. */
        fun observe(reading: Reading, fps: Int = MIN_FPS): Issue? {
            val invalid = when {
                reading.exposureNs == null || reading.exposureNs <= 0 || reading.iso == null || reading.iso <= 0 ||
                    reading.frameDurationNs == null || reading.frameDurationNs <= 0 -> Issue.MISSING_MEASUREMENTS
                reading.frameDurationNs > maxConfirmedFrameDurationNs(fps) -> Issue.REPORTED_FRAME_RATE
                !reading.geometryMatches -> Issue.REPORTED_GEOMETRY
                else -> null
            }
            matching = if (invalid == null) { if (reading == previous) matching + 1 else 1 } else 0
            previous = reading
            issue = invalid ?: Issue.CONFIRMING.takeIf { matching < 2 }
            return issue
        }
    }
}
