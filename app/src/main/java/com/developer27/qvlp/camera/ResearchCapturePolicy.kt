package com.developer27.qvlp.camera

/** Research limits shared by the reset preset and the capture gate. */
internal object ResearchCapturePolicy {
    const val MAX_EXPOSURE_NS = 100_000L
    const val MIN_FPS = 30
    // Ceil(1 second / 30): allow only integer-nanosecond rounding.
    const val MAX_FRAME_DURATION_NS = 33_333_334L
    // A nominal 30 fps sensor mode may quantize timing to whole sensor lines.
    // Pixel 8a reports 33,340,371 ns (29.9937 fps). Bound rounding to 0.05%
    // of the 30 fps period; do not change requested rates or recorded metadata.
    const val MAX_CONFIRMED_FRAME_DURATION_NS = MAX_FRAME_DURATION_NS + MAX_FRAME_DURATION_NS / 2_000

    fun usesFrameTimingTolerance(frameDurationNs: Long): Boolean =
        frameDurationNs > MAX_FRAME_DURATION_NS && frameDurationNs <= MAX_CONFIRMED_FRAME_DURATION_NS

    enum class Issue(val message: String) {
        REAR_CAMERA("Select a rear camera. Factory Reset in Settings selects a compatible rear camera."),
        ZOOM("Research capture requires 1× zoom. Use Factory Reset in Settings or return zoom to 1×."),
        RESOLUTION("Choose a fixed resolution with the full sensor aspect ratio and at least 30 fps."),
        EXPOSURE("Research capture requires a manual exposure of 100 µs or less."),
        ISO("Choose a fixed manual ISO supported by this camera."),
        FRAME_RATE("Research capture requires a target frame rate of at least 30 fps."),
        FOCUS("Choose locked, manual or fixed focus. Continuous autofocus cannot be used for research capture."),
        WHITE_BALANCE("Choose locked white balance or a fixed preset. Automatic unlocked white balance cannot be used for research capture."),
        CONFIRMING("Waiting for the camera to confirm stable research settings."),
        MISSING_MEASUREMENTS("The camera has not reported exposure, ISO and frame duration; research settings cannot be confirmed."),
        REPORTED_EXPOSURE("The camera reports an exposure above 100 µs. Shorten exposure in Settings."),
        REPORTED_FRAME_RATE("The camera reports a frame rate below nominal 30 fps, beyond the allowed 0.05% timing rounding. Increase the target frame rate or choose another resolution."),
        REPORTED_GEOMETRY("Waiting for the camera to confirm the requested 1× sensor crop.")
    }

    fun settingsIssue(rear: Boolean, zoom: Float, fixedResolution: Boolean, exposureNs: Long,
                      fixedIso: Boolean, fps: Int, fixedFocus: Boolean, fixedWhiteBalance: Boolean): Issue? = when {
        !rear -> Issue.REAR_CAMERA
        !zoom.isFinite() || kotlin.math.abs(zoom - 1f) > 0.0001f -> Issue.ZOOM
        !fixedResolution -> Issue.RESOLUTION
        exposureNs !in 1..MAX_EXPOSURE_NS -> Issue.EXPOSURE
        !fixedIso -> Issue.ISO
        fps < MIN_FPS -> Issue.FRAME_RATE
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

        fun observe(reading: Reading): Issue? {
            val invalid = when {
                reading.exposureNs == null || reading.exposureNs <= 0 || reading.iso == null || reading.iso <= 0 ||
                    reading.frameDurationNs == null || reading.frameDurationNs <= 0 -> Issue.MISSING_MEASUREMENTS
                reading.exposureNs > MAX_EXPOSURE_NS -> Issue.REPORTED_EXPOSURE
                reading.frameDurationNs > MAX_CONFIRMED_FRAME_DURATION_NS -> Issue.REPORTED_FRAME_RATE
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
