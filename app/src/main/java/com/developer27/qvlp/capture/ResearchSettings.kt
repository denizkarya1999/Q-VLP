package com.developer27.qvlp.capture

import android.content.SharedPreferences
import org.json.JSONObject

/** A place where frames are captured. Only its ID is entered; nothing else is recorded about it. */
internal data class Position(val id: Int) {
    fun json(): JSONObject = JSONObject().put("position_id", id)
}

internal object ResearchSettings {
    const val MAX_POSITION_ID = 999999

    fun position(p: SharedPreferences) =
        Position(p.getString("position_id", "0")?.toIntOrNull()?.coerceIn(0, MAX_POSITION_ID) ?: 0)

    /** A position ID is all a capture needs. */
    fun hasPosition(p: SharedPreferences): Boolean = p.getString("position_id", null)?.toIntOrNull() != null

    fun select(p: SharedPreferences, position: Position) {
        p.edit().putString("position_id", position.id.toString()).apply()
    }

    /** No position is selected until the operator enters one. */
    fun clear(p: SharedPreferences) {
        p.edit().remove("position_id").apply()
    }

    /** The positions on either side of the selected one, by ID. */
    fun next(p: SharedPreferences): Position? = position(p).id.takeIf { it < MAX_POSITION_ID }?.let { Position(it + 1) }
    fun previous(p: SharedPreferences): Position? = position(p).id.takeIf { it > 0 }?.let { Position(it - 1) }
}
