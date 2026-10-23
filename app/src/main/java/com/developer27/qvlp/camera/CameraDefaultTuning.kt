package com.developer27.qvlp.camera

/** Device defaults only; capabilities and reported results still gate capture. */
internal object CameraDefaultTuning {
    // A starting point for ceiling LEDs, adjustable to the actual camera-to-LED distance.
    const val FIXED_FOCUS_DIOPTERS = 0.5f

    fun prefersFixedControls(manufacturer: String, model: String): Boolean =
        manufacturer.equals("Google", ignoreCase = true) && model in setOf("Pixel 8", "Pixel 8a")
}
