package com.developer27.qvlp.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixedCameraTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private fun rearId(): String {
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
        }
        assumeTrue("A rear camera is required", id != null)
        return requireNotNull(id)
    }

    @Test fun resolvesAStablePhysicalRearLensWithItsOwnCapabilities() {
        val id = rearId()
        val fixed = FixedCamera.resolve(manager, id, null)
        val reopened = FixedCamera.resolve(manager, id, fixed.physicalId)
        assertEquals(fixed.physicalId, reopened.physicalId)
        assertTrue(fixed.rear)
        assertTrue(fixed.capabilities.streams.isNotEmpty())
        if (fixed.physicalOutputs) {
            assumeTrue(Build.VERSION.SDK_INT >= 28)
            assertTrue(manager.getCameraCharacteristics(id).physicalCameraIds.contains(fixed.physicalId))
            assertEquals(manager.getCameraCharacteristics(fixed.physicalId)[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE],
                fixed.characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE])
        } else assertEquals(id, fixed.physicalId)
    }

    @Test fun aMissingPinNeverFallsBackToTheLogicalCamera() {
        assertTrue(runCatching { FixedCamera.resolve(manager, rearId(), "missing-lens") }.isFailure)
    }

    @Test fun rememberingAPinSeparatesOldSessionsAndKeepsItOnReopen() {
        val prefs = context.getSharedPreferences("fixed_camera_test", Context.MODE_PRIVATE)
        try {
            prefs.edit().clear().putString("active_research_session", "old-session").commit()
            val fixed = FixedCamera.resolve(manager, rearId(), null)
            fixed.remember(prefs)
            assertEquals(fixed.physicalId, prefs.getString(FixedCamera.preferenceKey(fixed.cameraId), null))
            assertFalse(prefs.contains("active_research_session"))
            prefs.edit().putString("active_research_session", "new-session").commit()
            fixed.remember(prefs)
            assertEquals("new-session", prefs.getString("active_research_session", null))
            val reset = ResearchCameraReset.select(manager, fixed.cameraId, prefs)
            assertEquals(fixed.physicalId, reset.physicalId)
        } finally { prefs.edit().clear().commit() }
    }
}
