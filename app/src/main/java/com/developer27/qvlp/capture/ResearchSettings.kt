package com.developer27.qvlp.capture

import android.content.SharedPreferences
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.developer27.qvlp.R
import org.json.JSONObject

internal data class Position(val id: Int, val x: Double?, val y: Double?, val source: String = "manual") {
    fun json() = JSONObject().put("position_id", id).put("rx_x_m", x ?: JSONObject.NULL)
        .put("rx_y_m", y ?: JSONObject.NULL).put("source", source)
}

internal object ResearchSettings {
    private fun number(p: SharedPreferences, key: String): Double? =
        p.getString(key, "")?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun position(p: SharedPreferences) = Position(
        p.getString("position_id", "0")?.toIntOrNull()?.coerceIn(0, 999999) ?: 0,
        number(p, "rx_x_m"), number(p, "rx_y_m"), p.getString("position_source", "manual") ?: "manual"
    )

    private fun rowTimeMatches(p: SharedPreferences, key: Pair<String, String>?): Boolean = key != null &&
        p.getString("row_time_camera_id", null) == key.first && p.getString("row_time_resolution", null) == key.second &&
        p.getFloat("row_time_zoom", 1f) == p.getFloat("camera_${key.first}_zoom", 1f)

    fun mounting(p: SharedPreferences, key: Pair<String, String>?) = JSONObject().apply {
        listOf("lens_height_m", "lens_offset_x_m", "lens_offset_y_m").forEach {
            put(it, number(p, it) ?: JSONObject.NULL)
        }
        val rowTime = number(p, "row_time_us")?.takeIf { rowTimeMatches(p, key) }
        put("row_time_us", rowTime ?: JSONObject.NULL)
        put("row_time_source", if (rowTime != null) "user_entered" else "unknown")
        put("row_time_camera_id", if (rowTime != null) key?.first else JSONObject.NULL)
        put("row_time_resolution", if (rowTime != null) key?.second else JSONObject.NULL)
        put("row_time_zoom", if (rowTime != null) p.getFloat("row_time_zoom", 1f).toDouble() else JSONObject.NULL)
        put("notes", p.getString("mount_notes", "").orEmpty())
    }

    fun bind(fragment: PreferenceFragmentCompat, profileKey: () -> Pair<String, String>?) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
        val fields = listOf("lens_height_m",
            "lens_offset_x_m", "lens_offset_y_m", "row_time_us", "mount_notes")
        fields.forEach { key ->
            val pref = requireNotNull(fragment.findPreference<EditTextPreference>(key))
            pref.summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf(String::isNotBlank) ?: fragment.getString(R.string.not_provided)
            }
            pref.setOnBindEditTextListener { edit ->
                edit.inputType = when (key) {
                    "mount_notes" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
                edit.setSingleLine(key != "mount_notes")
                if (key == "mount_notes") edit.maxLines = 5
                if (key == "row_time_us" && !rowTimeMatches(prefs, profileKey())) edit.setText("")
                edit.selectAll()
            }
            pref.setOnPreferenceChangeListener { _, value ->
                val raw = (value as? String).orEmpty().trim()
                val n = raw.replace(',', '.').toDoubleOrNull()
                val valid = when (key) {
                    "mount_notes" -> raw.length <= 2000
                    else -> raw.isEmpty() || (n != null && n.isFinite() &&
                        (key !in listOf("lens_height_m", "row_time_us") || n > 0.0))
                }
                if (valid && key == "row_time_us") {
                    val profile = profileKey()
                    if (raw.isNotEmpty() && profile == null) return@setOnPreferenceChangeListener false
                    prefs.edit().putString("row_time_camera_id", profile?.first)
                        .putString("row_time_resolution", profile?.second)
                        .putFloat("row_time_zoom", prefs.getFloat("camera_${profile?.first}_zoom", 1f)).apply()
                }
                if (!valid) Toast.makeText(fragment.requireContext(),
                    fragment.getString(R.string.session_value_invalid), Toast.LENGTH_LONG).show()
                valid
            }
        }
    }

    fun refreshRowTime(fragment: PreferenceFragmentCompat, key: Pair<String, String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(fragment.requireContext())
        fragment.findPreference<EditTextPreference>("row_time_us")?.summaryProvider =
            androidx.preference.Preference.SummaryProvider<EditTextPreference> {
                if (number(prefs, "row_time_us") != null && rowTimeMatches(prefs, key)) "${it.text} µs"
                else fragment.getString(R.string.row_time_unknown_profile)
            }
    }
}
