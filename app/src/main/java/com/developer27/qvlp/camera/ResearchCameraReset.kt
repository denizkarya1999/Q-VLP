package com.developer27.qvlp.camera

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import com.developer27.qvlp.capture.SessionFiles
import kotlin.math.abs

internal data class ResearchCameraSelection(val cameraId: String, val profile: CameraProfile, val physicalId: String? = null)

/** Resolves a complete supported preset before changing any preference. */
internal object ResearchCameraReset {
    fun select(manager: CameraManager, preferredId: String?, preferences: SharedPreferences? = null): ResearchCameraSelection {
        val failures = mutableListOf<String>()
        for (id in manager.cameraIdList.sortedBy { if (it == preferredId) 0 else 1 }) {
            val result = runCatching {
                val characteristics = manager.getCameraCharacteristics(id)
                if (characteristics[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK)
                    return@runCatching null
                val fixed = FixedCamera.resolve(manager, id, preferences?.getString(FixedCamera.preferenceKey(id), null))
                ResearchCameraSelection(id, defaults(fixed.capabilities), fixed.physicalId)
            }
            result.getOrNull()?.let { return it }
            result.exceptionOrNull()?.message?.let { failures.add(it) }
        }
        error("No rear camera supports all research settings. " +
            (failures.distinct().joinToString(" ").ifEmpty { "A compatible rear camera is required." }))
    }

    fun defaults(capabilities: CameraCapabilities): CameraProfile {
        require(capabilities.exposureRangeNs.lower <= ResearchCapturePolicy.MAX_EXPOSURE_NS) {
            "This camera cannot expose for 100 µs or less."
        }
        val stream = capabilities.streams.filter {
            abs(it.size.width.toDouble() / it.size.height - capabilities.sensorAspectRatio) < 0.02 &&
                it.frameRates.any { fps -> supportsResearchCadence(it, fps) }
        }.minByOrNull { abs(it.size.width.toLong() * it.size.height - 1280L * 960) }
            ?: error("No full-sensor-aspect-ratio resolution supports at least 30 fps.")
        val fps = stream.frameRates.filter { supportsResearchCadence(stream, it) }.min()
        val controls = capabilities.controls
        val preferred = controls.defaults(capabilities.preferFixedControlDefaults)
        val fixedFocusModes = listOf(FocusMode.LOCK, FocusMode.MANUAL, FocusMode.FIXED)
        val focus = (listOf(preferred.focusMode) + fixedFocusModes)
            .firstOrNull { it in controls.focusModes && it in fixedFocusModes }
            ?: error("This camera cannot lock or fix focus.")
        val wb = preferred.whiteBalanceMode.takeIf { isFixedWhiteBalance(it) }
            ?: controls.whiteBalanceOptions.firstOrNull { isFixedWhiteBalance(it.mode) }?.mode
            ?: error("This camera cannot lock white balance or use a fixed preset.")
        return CameraProfile(
            minOf(ResearchCapturePolicy.MAX_EXPOSURE_NS, capabilities.maximumExposureNs(stream, fps)),
            capabilities.isoRange.lower, stream, fps, FocusWhiteBalanceProfile(focus, preferred.focusDistance, wb)
        )
    }

    private fun supportsResearchCadence(stream: StreamOption, fps: Int): Boolean =
        fps >= ResearchCapturePolicy.MIN_FPS && stream.frameDurationNs(fps) <= ResearchCapturePolicy.MAX_FRAME_DURATION_NS

    fun apply(context: Context, preferences: SharedPreferences, selection: ResearchCameraSelection) {
        CameraSettingsStore(preferences, selection.cameraId).save(selection.profile)
        selection.physicalId?.let { preferences.edit().putString(FixedCamera.preferenceKey(selection.cameraId), it).apply() }
        preferences.edit()
            .putString("selected_capture_camera", selection.cameraId)
            .putFloat("camera_${selection.cameraId}_zoom", 1f)
            .putString(PreviewDisplaySize.PREFERENCE_KEY, PreviewDisplaySize.ORIGINAL.key)
            .apply()
        // Keep existing captures and their labels; the next capture belongs to a new session.
        SessionFiles.newSession(context)
    }

    fun isFixedWhiteBalance(mode: Int): Boolean = mode == FocusWhiteBalanceCapabilities.WB_LOCK || mode in setOf(
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
        CaptureRequest.CONTROL_AWB_MODE_SHADE, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT,
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
    )
}
