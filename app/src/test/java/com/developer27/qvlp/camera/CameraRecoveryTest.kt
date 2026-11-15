package com.developer27.qvlp.camera

import com.developer27.qvlp.camera.CameraRecovery.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRecoveryTest {
    @Test fun aWorkingCameraIsLeftAlone() {
        val recovery = CameraRecovery()
        repeat(5) { assertEquals(Step.NONE, recovery.report(problem = false, certain = true, onDefaultCamera = true)) }
    }

    @Test fun aCertainProblemResetsTheSettingsAtOnce() {
        // Zoomed in on the rear camera: capture needs 1x, so the camera goes back to its defaults.
        val recovery = CameraRecovery()
        assertEquals(Step.RESET_SETTINGS, recovery.report(problem = true, certain = true, onDefaultCamera = false))
    }

    @Test fun aProblemThatSurvivesTheResetFallsBackToTheDefaultCameraAndIsThenShown() {
        val recovery = CameraRecovery()
        assertEquals(Step.RESET_SETTINGS, recovery.report(true, true, onDefaultCamera = false))
        assertEquals(Step.USE_DEFAULT_CAMERA, recovery.report(true, true, onDefaultCamera = false))
        // Now on the default camera with default settings: nothing is left to try.
        repeat(3) { assertEquals(Step.SHOW, recovery.report(true, true, onDefaultCamera = true)) }
    }

    @Test fun onTheDefaultCameraThereIsOnlyTheSettingsResetBeforeTheProblemIsShown() {
        val recovery = CameraRecovery()
        assertEquals(Step.RESET_SETTINGS, recovery.report(true, true, onDefaultCamera = true))
        assertEquals(Step.SHOW, recovery.report(true, true, onDefaultCamera = true))
    }

    @Test fun aSettlingCameraIsGivenTimeBeforeAnythingIsReset() {
        val recovery = CameraRecovery(patience = 3)
        assertEquals(Step.NONE, recovery.report(true, certain = false, onDefaultCamera = true))
        assertEquals(Step.NONE, recovery.report(true, certain = false, onDefaultCamera = true))
        // It cleared by itself: the count starts again.
        assertEquals(Step.NONE, recovery.report(false, certain = false, onDefaultCamera = true))
        assertEquals(Step.NONE, recovery.report(true, certain = false, onDefaultCamera = true))
        assertEquals(Step.NONE, recovery.report(true, certain = false, onDefaultCamera = true))
        assertEquals(Step.RESET_SETTINGS, recovery.report(true, certain = false, onDefaultCamera = true))
        // After the reset the camera settles again, and a brief report does not show the notice.
        assertEquals(Step.NONE, recovery.report(true, certain = false, onDefaultCamera = true))
        assertEquals(Step.NONE, recovery.report(true, certain = false, onDefaultCamera = true))
        assertEquals(Step.SHOW, recovery.report(true, certain = false, onDefaultCamera = true))
    }

    @Test fun onceTheCameraWorksTheNextProblemStartsFromTheFirstStep() {
        val recovery = CameraRecovery()
        assertEquals(Step.RESET_SETTINGS, recovery.report(true, true, onDefaultCamera = true))
        recovery.restart() // The camera reported ready.
        assertEquals(Step.RESET_SETTINGS, recovery.report(true, true, onDefaultCamera = true))
        assertEquals(Step.SHOW, recovery.report(true, true, onDefaultCamera = true))
        recovery.restart() // The operator switched camera or came back from Settings.
        assertEquals(Step.RESET_SETTINGS, recovery.report(true, true, onDefaultCamera = true))
    }
}
