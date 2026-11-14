package com.developer27.qvlp.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Which camera opens first and which one the Switch button selects. */
internal object CameraChoice {
    // Replaces the rear-only "selected_capture_camera", so existing installs also start on the front camera.
    const val PREFERENCE_KEY = "capture_camera"

    enum class Facing { FRONT, REAR, OTHER }

    /** Cameras in the order the device lists them. */
    fun facings(manager: CameraManager): Map<String, Facing> = manager.cameraIdList.associateWith {
        when (manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING]) {
            CameraCharacteristics.LENS_FACING_FRONT -> Facing.FRONT
            CameraCharacteristics.LENS_FACING_BACK -> Facing.REAR
            else -> Facing.OTHER
        }
    }

    /** Keep the operator's choice; otherwise front, then rear, then any camera. */
    fun initial(cameras: Map<String, Facing>, saved: String?): String? =
        saved?.takeIf { it in cameras } ?: cameras.keys.minByOrNull { cameras.getValue(it).ordinal }

    /** The first camera facing the other way, or null when the device has none. */
    fun opposite(cameras: Map<String, Facing>, current: String?): String? {
        val wanted = if (cameras[current] == Facing.FRONT) Facing.REAR else Facing.FRONT
        return cameras.entries.firstOrNull { it.value == wanted && it.key != current }?.key
    }
}
