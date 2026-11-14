package com.developer27.qvlp.capture

import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.developer27.qvlp.R

/** A capture is always a number of frames; a duration saved by an earlier version is ignored. */
internal object CaptureLengthPreferences {
    private const val FRAMES = "capture_frame_count"

    fun read(prefs: SharedPreferences): CaptureLength =
        CaptureLength.parse(prefs.getString(FRAMES, null)) ?: CaptureLength()

    fun bind(fragment: PreferenceFragmentCompat) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
        val frames = requireNotNull(fragment.findPreference<EditTextPreference>(FRAMES))
        fun refresh() {
            val value = read(prefs).frames
            frames.text = value.toString()
            frames.summary = fragment.resources.getQuantityString(R.plurals.capture_length_frames, value, value)
        }
        frames.dialogMessage = fragment.getString(R.string.capture_frame_range,
            CaptureLength.RANGE.first, CaptureLength.RANGE.last)
        frames.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER; it.selectAll() }
        frames.setOnPreferenceChangeListener { _, raw ->
            val length = CaptureLength.parse(raw as? String)
            if (length == null) {
                Toast.makeText(fragment.requireContext(), frames.dialogMessage, Toast.LENGTH_LONG).show()
            } else {
                frames.text = length.frames.toString()
                refresh()
            }
            false
        }
        refresh()
    }
}
