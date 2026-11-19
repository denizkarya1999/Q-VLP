package com.developer27.qvlp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InferenceModelsTest {
    @Test fun bothModelsRunWhenBothAreTicked() {
        assertEquals(InferenceModels.BOTH, InferenceModels.of(detectionTicked = true, recognitionTicked = true))
    }

    @Test fun withoutTheRecognitionModelTheLedsAreOnlyFound() {
        assertEquals(InferenceModels(detection = true, recognition = false), InferenceModels.of(detectionTicked = true, recognitionTicked = false))
    }

    @Test fun theRecognitionModelDoesNotRunWithoutTheDetectionModel() {
        // Its checkbox keeps its tick while the detection model is unticked, and it has no LEDs to name.
        assertEquals(InferenceModels(detection = false, recognition = false), InferenceModels.of(detectionTicked = false, recognitionTicked = true))
        assertEquals(InferenceModels(detection = false, recognition = false), InferenceModels.of(detectionTicked = false, recognitionTicked = false))
        assertThrows(IllegalArgumentException::class.java) { InferenceModels(detection = false, recognition = true) }
    }
}
