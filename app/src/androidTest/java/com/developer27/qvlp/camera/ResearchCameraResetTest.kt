package com.developer27.qvlp.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResearchCameraResetTest {
    private val controls = FocusWhiteBalanceCapabilities(
        fixedFocus = false, maxFocusDistance = 10f, focusCalibrated = true, manualFocus = true,
        focusLock = true, continuousFocusMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        afOff = true, whiteBalanceLock = true, manualWhiteBalance = true,
        whiteBalanceModes = listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
    )
    private fun stream(width: Int, height: Int, fps: List<Int> = listOf(15, 30, 60)) =
        StreamOption(Size(width, height), 16_666_667L, fps)
    private fun capabilities() = CameraCapabilities("rear", Range(10_000L, 1_000_000_000L), Range(50, 3200),
        listOf(stream(1920, 1080), stream(1280, 960), stream(640, 480)), 4.0 / 3.0, controls)

    @Test fun selectsTheCompleteResearchPreset() {
        val profile = ResearchCameraReset.defaults(capabilities())
        assertEquals(Size(1280, 960), profile.stream.size)
        assertEquals(100_000L, profile.exposureNs)
        assertEquals(50, profile.iso)
        assertEquals(30, profile.fps)
        assertEquals(FocusMode.LOCK, profile.controls.focusMode)
        assertEquals(FocusWhiteBalanceCapabilities.WB_LOCK, profile.controls.whiteBalanceMode)
    }

    @Test fun prefersTheNativeAspectRatioOverTheNominal1280By960Size() {
        val caps = capabilities().copy(sensorAspectRatio = 16.0 / 9.0,
            streams = listOf(stream(1280, 960), stream(1280, 720)))
        assertEquals(Size(1280, 720), ResearchCameraReset.defaults(caps).stream.size)
    }

    @Test fun refusesExposureClampingAboveTheResearchLimit() {
        assertTrue(runCatching { ResearchCameraReset.defaults(capabilities().copy(exposureRangeNs = Range(200_000L, 1_000_000_000L))) }.isFailure)
    }

    @Test fun refusesSlowOrCroppedFallbacks() {
        assertTrue(runCatching { ResearchCameraReset.defaults(capabilities().copy(streams = listOf(stream(1280, 960, listOf(15, 24))))) }.isFailure)
        assertTrue(runCatching { ResearchCameraReset.defaults(capabilities().copy(streams = listOf(stream(1920, 1080)))) }.isFailure)
    }

    @Test fun refusesAReportedThirtyFpsOptionWithASlowerMinimumDuration() {
        val slow = stream(1280, 960, listOf(30)).copy(minFrameDurationNs = 33_333_335L)
        assertTrue(runCatching { ResearchCameraReset.defaults(capabilities().copy(streams = listOf(slow))) }.isFailure)
    }

    @Test fun refusesUnlockedControlFallbacks() {
        assertTrue(runCatching { ResearchCameraReset.defaults(capabilities().copy(
            controls = controls.copy(focusLock = false, manualFocus = false))) }.isFailure)
        assertTrue(runCatching { ResearchCameraReset.defaults(capabilities().copy(
            controls = controls.copy(whiteBalanceLock = false, whiteBalanceModes = listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)))) }.isFailure)
    }

    @Test fun acceptsFixedFocusAndAFixedWhiteBalancePreset() {
        val caps = capabilities().copy(controls = controls.copy(fixedFocus = true, focusLock = false, manualFocus = false,
            whiteBalanceLock = false, whiteBalanceModes = listOf(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)))
        val profile = ResearchCameraReset.defaults(caps)
        assertEquals(FocusMode.FIXED, profile.controls.focusMode)
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, profile.controls.whiteBalanceMode)
    }

    @Test fun resetsCameraPreferencesWithoutErasingSurveyOrCaptureLength() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("factory_reset_test", Context.MODE_PRIVATE)
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        val activeSession = defaults.getString("active_research_session", null)
        try {
            prefs.edit().putString("selected_capture_camera", "front").putFloat("camera_rear_zoom", 2f)
                .putString("capture_length_mode", "duration").putString("capture_duration_seconds", "7")
                .putString("rx_x_m", "1.2").putString("lens_height_m", "1.5").apply()
            ResearchCameraReset.apply(context, prefs, ResearchCameraSelection("rear", ResearchCameraReset.defaults(capabilities())))
            assertEquals("rear", prefs.getString("selected_capture_camera", null))
            assertEquals(1f, prefs.getFloat("camera_rear_zoom", 0f))
            assertEquals("1280x960", prefs.getString("camera_rear_resolution", null))
            assertEquals("duration", prefs.getString("capture_length_mode", null))
            assertEquals("7", prefs.getString("capture_duration_seconds", null))
            assertEquals("1.2", prefs.getString("rx_x_m", null))
            assertEquals("1.5", prefs.getString("lens_height_m", null))
        } finally {
            prefs.edit().clear().commit()
            defaults.edit().putString("active_research_session", activeSession).commit()
        }
    }
}
