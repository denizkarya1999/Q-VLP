package com.developer27.qvlp

import android.content.Context
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.CameraChoice
import com.developer27.qvlp.capture.SessionFiles

/**
 * Puts the settings back the way a new installation starts: the front camera, 1× zoom, the default rolling
 * shutter rate (6000 Hz), ISO 100 and 1 fps on every camera, 30 frames, Data Collection Mode, Generate Post-Inference
 * Files ticked, Enable CV Algorithms unticked and no position chosen.
 * Recorded sessions and the auto-save folder are not settings and are kept.
 */
internal object FactoryReset {
    private val CAMERA_SETTING = Regex("camera_[^_]+_(exposure_ns|iso|fps_rate|zoom|physical_id)")
    private val SETTINGS = setOf(CameraChoice.PREFERENCE_KEY, "capture_frame_count", SettingsActivity.MODE_PREFERENCE,
        SettingsActivity.CV_ALGORITHMS_PREFERENCE, SettingsActivity.POST_INFERENCE_FILES_PREFERENCE, MainActivity.SESSION_MODE,
        "position_id", "last_capture_position")

    @Volatile private var done = false

    /** The stored values a reset removes. Anything else the app remembers is left as it is. */
    fun keys(stored: Set<String>): Set<String> = stored.filter { it in SETTINGS || CAMERA_SETTING.matches(it) }.toSet()

    fun apply(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = preferences.edit()
        keys(preferences.all.keys).forEach { editor.remove(it) }
        check(editor.commit()) { "Could not restore the settings." }
        // Frames taken with other settings never share a session.
        SessionFiles.newSession(context.applicationContext)
        done = true
    }

    /** True once after a reset, so the camera screen forgets the result and position it was showing. */
    fun consume(): Boolean = done.also { done = false }
}
