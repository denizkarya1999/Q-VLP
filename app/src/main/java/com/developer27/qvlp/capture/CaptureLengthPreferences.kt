package com.developer27.qvlp.capture

import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.developer27.qvlp.R

internal object CaptureLengthPreferences {
    private const val MODE = "capture_length_mode"
    private const val FRAMES = "capture_frame_count"
    private const val SECONDS = "capture_duration_seconds"

    fun read(prefs: SharedPreferences): CaptureLength {
        val mode = CaptureLengthMode.values().firstOrNull { it.key == prefs.getString(MODE, null) }
            ?: CaptureLengthMode.FRAMES
        return readValue(prefs, mode)
    }

    private fun readValue(prefs: SharedPreferences, mode: CaptureLengthMode): CaptureLength =
        CaptureLength.parse(mode, prefs.getString(if (mode == CaptureLengthMode.FRAMES) FRAMES else SECONDS, null))
            ?: CaptureLength(mode, if (mode == CaptureLengthMode.FRAMES) CaptureLength.DEFAULT_FRAMES else CaptureLength.DEFAULT_SECONDS)

    fun bind(fragment: PreferenceFragmentCompat) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
        val mode = requireNotNull(fragment.findPreference<ListPreference>(MODE))
        val frames = requireNotNull(fragment.findPreference<EditTextPreference>(FRAMES))
        val seconds = requireNotNull(fragment.findPreference<EditTextPreference>(SECONDS))
        mode.entries = arrayOf(fragment.getString(R.string.capture_by_frames), fragment.getString(R.string.capture_by_duration))
        mode.entryValues = CaptureLengthMode.values().map { it.key }.toTypedArray()
        fun refresh(selected: CaptureLengthMode) {
            mode.value = selected.key
            mode.summary = mode.entry
            frames.isVisible = selected == CaptureLengthMode.FRAMES
            seconds.isVisible = selected == CaptureLengthMode.DURATION
            listOf(frames to CaptureLengthMode.FRAMES, seconds to CaptureLengthMode.DURATION).forEach { (pref, kind) ->
                val value = readValue(prefs, kind).value
                pref.text = value.toString()
                pref.summary = fragment.resources.getQuantityString(if (kind == CaptureLengthMode.FRAMES) R.plurals.capture_length_frames else R.plurals.capture_length_seconds, value, value)
            }
        }
        mode.setOnPreferenceChangeListener { _, raw ->
            CaptureLengthMode.values().firstOrNull { it.key == raw }?.let { refresh(it) }
            false
        }
        listOf(frames to CaptureLengthMode.FRAMES, seconds to CaptureLengthMode.DURATION).forEach { (pref, kind) ->
            val range = CaptureLength.range(kind)
            pref.dialogMessage = fragment.getString(if (kind == CaptureLengthMode.FRAMES) R.string.capture_frame_range else R.string.capture_duration_range, range.first, range.last)
            pref.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER; it.selectAll() }
            pref.setOnPreferenceChangeListener { _, raw ->
                val length = CaptureLength.parse(kind, raw as? String)
                if (length == null) {
                    Toast.makeText(fragment.requireContext(), pref.dialogMessage, Toast.LENGTH_LONG).show()
                } else {
                    pref.text = length.value.toString()
                    refresh(kind)
                }
                false
            }
        }
        refresh(read(prefs).mode)
    }
}
