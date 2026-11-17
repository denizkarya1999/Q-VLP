package com.developer27.qvlp.camera

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResearchPresetTest {
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
        val profile = ResearchPreset.defaults(capabilities())
        assertEquals(Size(1280, 960), profile.stream.size)
        assertEquals(166_666L, profile.exposureNs) // 6000 Hz
        assertEquals(100, profile.iso)
        // The default FPS rate is 1 fps; this camera's slowest listed rate is 15.
        assertEquals(15, profile.fps)
        assertEquals(1, ResearchPreset.defaults(capabilities().copy(streams = listOf(stream(1280, 960, listOf(1, 15, 30))))).fps)
        assertEquals(FocusMode.LOCK, profile.controls.focusMode)
        assertEquals(FocusWhiteBalanceCapabilities.WB_LOCK, profile.controls.whiteBalanceMode)
    }

    @Test fun pixelDefaultsUseFixedControlsForBothInitialSetupAndReset() {
        val caps = capabilities().copy(preferFixedControlDefaults = true)
        for (profile in listOf(caps.defaults(), ResearchPreset.defaults(caps))) {
            assertEquals(Size(1280, 960), profile.stream.size)
            assertEquals(166_666L, profile.exposureNs)
            assertEquals(100, profile.iso)
            assertEquals(FocusMode.MANUAL, profile.controls.focusMode)
            assertEquals(0.5f, profile.controls.focusDistance, 0f)
            assertEquals(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, profile.controls.whiteBalanceMode)
        }
    }

    @Test fun fixedControlDefaultsRespectTheFocusRangeAndSupportedModes() {
        val caps = capabilities().copy(preferFixedControlDefaults = true,
            controls = controls.copy(maxFocusDistance = 0.25f))
        assertEquals(0.25f, ResearchPreset.defaults(caps).controls.focusDistance, 0f)
        val fallback = caps.copy(controls = controls.copy(manualFocus = false,
            whiteBalanceModes = listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)))
        val profile = ResearchPreset.defaults(fallback)
        assertEquals(FocusMode.LOCK, profile.controls.focusMode)
        assertEquals(FocusWhiteBalanceCapabilities.WB_LOCK, profile.controls.whiteBalanceMode)
        assertEquals(0f, profile.controls.focusDistance, 0f)
    }

    @Test fun prefersTheNativeAspectRatioOverTheNominal1280By960Size() {
        val caps = capabilities().copy(sensorAspectRatio = 16.0 / 9.0,
            streams = listOf(stream(1280, 960), stream(1280, 720)))
        assertEquals(Size(1280, 720), ResearchPreset.defaults(caps).stream.size)
    }

    @Test fun startsAtTheShortestExposureWhenTheDefaultIsOutOfRange() {
        assertEquals(200_000L, ResearchPreset.defaults(capabilities().copy(exposureRangeNs = Range(200_000L, 1_000_000_000L))).exposureNs)
    }

    @Test fun refusesSlowOrCroppedFallbacks() {
        assertTrue(runCatching { ResearchPreset.defaults(capabilities().copy(streams = listOf(stream(1280, 960, listOf(15, 24))))) }.isFailure)
        assertTrue(runCatching { ResearchPreset.defaults(capabilities().copy(streams = listOf(stream(1920, 1080)))) }.isFailure)
    }

    @Test fun refusesAReportedThirtyFpsOptionWithASlowerMinimumDuration() {
        val slow = stream(1280, 960, listOf(30)).copy(minFrameDurationNs = 33_333_335L)
        assertTrue(runCatching { ResearchPreset.defaults(capabilities().copy(streams = listOf(slow))) }.isFailure)
    }

    @Test fun refusesUnlockedControlFallbacks() {
        assertTrue(runCatching { ResearchPreset.defaults(capabilities().copy(
            controls = controls.copy(focusLock = false, manualFocus = false))) }.isFailure)
        assertTrue(runCatching { ResearchPreset.defaults(capabilities().copy(
            controls = controls.copy(whiteBalanceLock = false, whiteBalanceModes = listOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)))) }.isFailure)
    }

    @Test fun acceptsFixedFocusAndAFixedWhiteBalancePreset() {
        val caps = capabilities().copy(controls = controls.copy(fixedFocus = true, focusLock = false, manualFocus = false,
            whiteBalanceLock = false, whiteBalanceModes = listOf(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)))
        val profile = ResearchPreset.defaults(caps)
        assertEquals(FocusMode.FIXED, profile.controls.focusMode)
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, profile.controls.whiteBalanceMode)
    }
}
