package com.developer27.qvlp.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import com.developer27.qvlp.R

internal enum class FocusMode(val label: Int) {
    LOCK(R.string.auto_then_lock), AUTO(R.string.continuous_auto),
    MANUAL(R.string.manual_fixed), FIXED(R.string.fixed_focus_lens), DEVICE(R.string.device_controlled)
}

internal data class WhiteBalanceOption(val mode: Int, val label: Int)

internal data class FocusWhiteBalanceProfile(
    val focusMode: FocusMode,
    val focusDistance: Float,
    val whiteBalanceMode: Int
)

internal data class FocusWhiteBalanceCapabilities(
    val fixedFocus: Boolean,
    val maxFocusDistance: Float,
    val focusCalibrated: Boolean,
    val manualFocus: Boolean,
    val focusLock: Boolean,
    val continuousFocusMode: Int?,
    val afOff: Boolean,
    val whiteBalanceLock: Boolean,
    val manualWhiteBalance: Boolean,
    val whiteBalanceModes: List<Int>
) {
    val focusModes: List<FocusMode> get() = buildList {
        if (fixedFocus) add(FocusMode.FIXED) else {
            if (focusLock) add(FocusMode.LOCK)
            if (manualFocus) add(FocusMode.MANUAL)
            if (continuousFocusMode != null) add(FocusMode.AUTO)
        }
        if (isEmpty()) add(FocusMode.DEVICE)
    }

    val whiteBalanceOptions: List<WhiteBalanceOption> get() = buildList {
        if (whiteBalanceLock) add(WhiteBalanceOption(WB_LOCK, R.string.auto_then_lock))
        listOf(
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_AUTO, R.string.auto_unlocked),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, R.string.wb_daylight),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, R.string.wb_cloudy),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_SHADE, R.string.wb_shade),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, R.string.wb_incandescent),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, R.string.wb_fluorescent),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT, R.string.wb_warm_fluorescent),
            WhiteBalanceOption(CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, R.string.wb_twilight)
        ).filterTo(this) { it.mode in whiteBalanceModes }
        if (isEmpty()) add(WhiteBalanceOption(WB_DEVICE, R.string.device_controlled))
    }

    fun defaults(): FocusWhiteBalanceProfile {
        val wb = when {
            whiteBalanceLock -> WB_LOCK
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT in whiteBalanceModes -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            else -> whiteBalanceOptions.first().mode
        }
        return FocusWhiteBalanceProfile(focusModes.first(), 0f, wb)
    }

    companion object {
        const val WB_LOCK = -1
        const val WB_DEVICE = -2

        fun read(c: CameraCharacteristics): FocusWhiteBalanceCapabilities {
            val requests = c.availableCaptureRequestKeys
            val results = c.availableCaptureResultKeys
            val afModes = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.toList().orEmpty()
            val awbModes = c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.toList().orEmpty()
            val maxFocus = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]
            val aeOn = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES]
                ?.contains(CaptureRequest.CONTROL_AE_MODE_ON) == true
            val afOff = CaptureRequest.CONTROL_AF_MODE_OFF in afModes
            val manualFocus = maxFocus != null && maxFocus.isFinite() && maxFocus > 0f && afOff &&
                CaptureRequest.LENS_FOCUS_DISTANCE in requests &&
                listOf(CaptureResult.LENS_FOCUS_DISTANCE, CaptureResult.LENS_STATE).all { it in results }
            val postProcessing = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
            return FocusWhiteBalanceCapabilities(
                fixedFocus = maxFocus == 0f,
                maxFocusDistance = maxFocus?.takeIf { it.isFinite() && it >= 0f } ?: 0f,
                focusCalibrated = c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]
                    == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
                manualFocus = manualFocus,
                focusLock = aeOn && CaptureRequest.CONTROL_AF_MODE_AUTO in afModes &&
                    CaptureRequest.CONTROL_AF_TRIGGER in requests && CaptureResult.CONTROL_AF_STATE in results,
                continuousFocusMode = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO).firstOrNull { it in afModes },
                afOff = afOff,
                whiteBalanceLock = aeOn && CaptureRequest.CONTROL_AWB_MODE_AUTO in awbModes &&
                    c[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true &&
                    CaptureRequest.CONTROL_AWB_LOCK in requests &&
                    listOf(CaptureResult.CONTROL_AWB_LOCK, CaptureResult.CONTROL_AWB_STATE).all { it in results },
                manualWhiteBalance = postProcessing && CaptureRequest.CONTROL_AWB_MODE_OFF in awbModes &&
                    listOf(CaptureRequest.COLOR_CORRECTION_GAINS, CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                        CaptureRequest.COLOR_CORRECTION_MODE).all { it in requests } &&
                    listOf(CaptureResult.COLOR_CORRECTION_GAINS, CaptureResult.COLOR_CORRECTION_TRANSFORM,
                        CaptureResult.COLOR_CORRECTION_MODE).all { it in results },
                whiteBalanceModes = awbModes
            )
        }
    }
}
