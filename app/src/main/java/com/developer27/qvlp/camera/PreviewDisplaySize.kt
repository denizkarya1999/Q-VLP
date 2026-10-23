package com.developer27.qvlp.camera

import android.content.SharedPreferences
import com.developer27.qvlp.R

/** Display-only sizes. A null scale keeps the original full-screen TextureView mapping. */
enum class PreviewDisplaySize(val key: String, val label: Int, val fittedScale: Float?) {
    ORIGINAL("original", R.string.preview_size_original, null),
    FIT("fit", R.string.preview_size_fit, 1f),
    MEDIUM("medium", R.string.preview_size_medium, 0.75f),
    SMALL("small", R.string.preview_size_small, 0.5f);

    companion object {
        const val PREFERENCE_KEY = "preview_display_size"

        fun read(preferences: SharedPreferences): PreviewDisplaySize {
            val saved = preferences.getString(PREFERENCE_KEY, ORIGINAL.key)
            return values().firstOrNull { it.key == saved } ?: ORIGINAL
        }
    }
}
