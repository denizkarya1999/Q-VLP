package com.developer27.qvlp.camera

import android.hardware.camera2.CaptureRequest
import kotlin.math.abs

/**
 * What the app fixes for research capture. Resolution, frame rate, focus and white balance are not settings:
 * the operator only chooses the rolling shutter rate and ISO.
 */
internal object ResearchPreset {
    /** The fixed values, with the two adjustable ones at their defaults: 10000 Hz and the lowest ISO. */
    fun defaults(capabilities: CameraCapabilities): CameraProfile {
        require(capabilities.exposureRangeNs.lower <= ResearchCapturePolicy.MAX_EXPOSURE_NS) {
            "This camera cannot expose for 100 µs or less."
        }
        val (stream, fps) = streamAndRate(capabilities)
        return CameraProfile(
            minOf(ResearchCapturePolicy.MAX_EXPOSURE_NS, capabilities.maximumExposureNs(stream, fps)),
            capabilities.isoRange.lower, stream, fps, controls(capabilities)
        )
    }

    /**
     * The one resolution and frame rate used on this camera: the size closest to the planned 1280 × 960 that
     * covers the sensor, at 30 fps. The pixel positions in a fingerprint depend on it, so it never changes.
     */
    fun streamAndRate(capabilities: CameraCapabilities): Pair<StreamOption, Int> {
        val stream = capabilities.streams.filter {
            ResearchCapturePolicy.coversSensor(it.size.width, it.size.height, capabilities.sensorAspectRatio) &&
                it.frameRates.any { fps -> supportsResearchCadence(it, fps) }
        }.minByOrNull { abs(it.size.width.toLong() * it.size.height - 1280L * 960) }
            ?: error("No resolution matching the sensor aspect ratio supports at least 30 fps.")
        return stream to stream.frameRates.filter { supportsResearchCadence(stream, it) }.min()
    }

    /**
     * Focus and white balance are not operator settings: the app holds both still with a fixed or locked mode.
     * Fails when the camera has none, since such a camera cannot be used for research capture.
     */
    fun controls(capabilities: CameraCapabilities): FocusWhiteBalanceProfile {
        val controls = capabilities.controls
        val preferred = controls.defaults(capabilities.preferFixedControlDefaults)
        val fixedFocusModes = listOf(FocusMode.LOCK, FocusMode.MANUAL, FocusMode.FIXED)
        val focus = (listOf(preferred.focusMode) + fixedFocusModes)
            .firstOrNull { it in controls.focusModes && it in fixedFocusModes }
            ?: error("This camera cannot lock or fix focus.")
        val wb = preferred.whiteBalanceMode.takeIf { isFixedWhiteBalance(it) }
            ?: controls.whiteBalanceOptions.firstOrNull { isFixedWhiteBalance(it.mode) }?.mode
            ?: error("This camera cannot lock white balance or use a fixed preset.")
        return FocusWhiteBalanceProfile(focus, preferred.focusDistance, wb)
    }

    private fun supportsResearchCadence(stream: StreamOption, fps: Int): Boolean =
        fps >= ResearchCapturePolicy.MIN_FPS && stream.frameDurationNs(fps) <= ResearchCapturePolicy.MAX_FRAME_DURATION_NS

    fun isFixedWhiteBalance(mode: Int): Boolean = mode == FocusWhiteBalanceCapabilities.WB_LOCK || mode in setOf(
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_SHADE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT,
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
    )
}
