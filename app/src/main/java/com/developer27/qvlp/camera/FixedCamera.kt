package com.developer27.qvlp.camera

import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.os.Build
import android.view.Surface

/** Both surfaces and their metadata must belong to the same persisted physical camera. */
internal data class FixedCamera(
    val cameraId: String,
    val physicalId: String,
    val physicalOutputs: Boolean,
    val characteristics: CameraCharacteristics
) {
    val capabilities: CameraCapabilities get() = CameraCapabilities.read(cameraId, characteristics)
    val rear: Boolean get() = characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
    val description: String get() = if (rear) "Rear lens $physicalId · fixed for preview and capture" else "Front camera"

    fun outputs(surfaces: List<Surface>): List<OutputConfiguration> = surfaces.map { surface ->
        OutputConfiguration(surface).apply {
            if (physicalOutputs) {
                if (Build.VERSION.SDK_INT >= 28) setPhysicalCameraId(physicalId)
                else error("Physical lens pinning requires Android 9 or newer.")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun result(total: TotalCaptureResult): CaptureResult? = when {
        !physicalOutputs -> total
        Build.VERSION.SDK_INT >= 31 -> total.physicalCameraTotalResults[physicalId]
        Build.VERSION.SDK_INT >= 28 -> total.physicalCameraResults[physicalId]
        else -> null
    }

    fun remember(preferences: SharedPreferences) {
        if (!rear || preferences.getString(preferenceKey(cameraId), null) == physicalId) return
        preferences.edit().putString(preferenceKey(cameraId), physicalId)
            // Never append captures from the newly pinned lens to an unpinned session.
            .remove("active_research_session").apply()
    }

    companion object {
        fun preferenceKey(cameraId: String) = "camera_${cameraId}_physical_id"

        fun resolve(manager: CameraManager, cameraId: String, savedId: String?): FixedCamera {
            val logical = manager.getCameraCharacteristics(cameraId)
            val rear = logical[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
            val multi = logical[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
            if (!rear || !multi) {
                require(!rear || savedId == null || savedId == cameraId) { "The pinned rear lens is unavailable." }
                return FixedCamera(cameraId, cameraId, false, logical)
            }
            val physicalIds = if (Build.VERSION.SDK_INT >= 28) logical.physicalCameraIds
                else error("Physical lens pinning requires Android 9 or newer.")
            val physical = physicalIds.sorted().mapNotNull { id ->
                runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
                    ?.takeIf { it[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
                    ?.let { id to it }
            }.toMap()
            val id = PhysicalLensChoice.choose(geometry(logical), physical.mapValues { geometry(it.value) }, savedId)
            // Validate this lens itself. Never silently fall back to another physical camera.
            return FixedCamera(cameraId, id, true, physical.getValue(id)).also { it.capabilities }
        }

        private fun geometry(c: CameraCharacteristics): PhysicalLensChoice.Geometry? {
            val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.minOrNull() ?: return null
            val size = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
            return PhysicalLensChoice.Geometry(focal.toDouble(), size.width.toDouble(), size.height.toDouble())
        }
    }
}
