package com.developer27.qvlp.capture

import android.content.SharedPreferences
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

    /** A position ID is enough to capture; coordinates are optional. */
    fun hasPosition(p: SharedPreferences): Boolean = p.getString("position_id", null)?.toIntOrNull() != null
}
