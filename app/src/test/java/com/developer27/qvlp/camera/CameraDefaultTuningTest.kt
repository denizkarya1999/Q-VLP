package com.developer27.qvlp.camera

import org.junit.Assert.*
import org.junit.Test

class CameraDefaultTuningTest {
    @Test fun usesFixedDefaultsOnlyForTheTargetPixelModels() {
        assertTrue(CameraDefaultTuning.prefersFixedControls("Google", "Pixel 8a"))
        assertTrue(CameraDefaultTuning.prefersFixedControls("google", "Pixel 8"))
        assertFalse(CameraDefaultTuning.prefersFixedControls("Other", "Pixel 8a"))
        assertFalse(CameraDefaultTuning.prefersFixedControls("Google", "Pixel 7"))
        assertFalse(CameraDefaultTuning.prefersFixedControls("Google", "Pixel 8 Pro"))
    }
}
