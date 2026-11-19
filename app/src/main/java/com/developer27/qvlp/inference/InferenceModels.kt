package com.developer27.qvlp.inference

/**
 * The models that run, as ticked in Settings → Options → Inference Models: the LED Detection Model, which
 * finds the LEDs, and the OOK-Based LED Recognition Model, which names them. The recognition model names the
 * LEDs the detection model finds, so it never runs without it.
 */
internal data class InferenceModels(val detection: Boolean, val recognition: Boolean) {
    init {
        require(detection || !recognition) { "The recognition model cannot run without the detection model." }
    }

    companion object {
        val BOTH = InferenceModels(detection = true, recognition = true)

        /** The models that run when the two checkboxes are set like this. */
        fun of(detectionTicked: Boolean, recognitionTicked: Boolean) =
            InferenceModels(detectionTicked, detectionTicked && recognitionTicked)
    }
}
