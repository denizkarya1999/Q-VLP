package com.developer27.qvlp

/** The two modes of Settings → Modes, as they are stored. */
internal object Modes {
    const val DATA_COLLECTION = "data_collection_mode"
    const val INFERENCE = "inference_mode"
    /** A new installation starts in Inference Mode, and a factory reset returns to it. */
    const val DEFAULT = INFERENCE

    /** The mode in use: the one the operator chose last ([stored]), or the default when none was chosen. */
    fun chosen(stored: String?): String = if (stored == DATA_COLLECTION || stored == INFERENCE) stored else DEFAULT
}
