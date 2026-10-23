package com.developer27.qvlp.capture

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/** Surveyed ground truth, kept in file order so a prepared snake path is preserved. */
internal object PositionCatalog {
    private const val KEY = "surveyed_positions"

    fun read(prefs: SharedPreferences): List<Position> = runCatching {
        val rows = JSONArray(prefs.getString(KEY, "[]"))
        (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Position(row.getInt("position_id"), row.getDouble("rx_x_m"), row.getDouble("rx_y_m"), "positions.csv")
        }
    }.getOrDefault(emptyList())

    fun import(input: InputStream, prefs: SharedPreferences): Int {
        val bytes = input.use { it.readBytesLimited(1024 * 1024) }
        val lines = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence()
            .filter { it.isNotBlank() }.toList()
        require(lines.size >= 2) { "The file needs a header and at least one position." }
        require(lines.size <= 10001) { "Use a file with at most 10,000 positions." }
        val header = cells(lines.first())
        val names = listOf("position_id", "rx_x_m", "rx_y_m")
        require(header.size == 3 && header.toSet() == names.toSet()) {
            "Use these three column names: position_id,rx_x_m,rx_y_m. Separate values with commas."
        }
        val seen = mutableSetOf<Int>()
        val positions = lines.drop(1).mapIndexed { index, line ->
            val row = cells(line)
            require(row.size == 3) { "Row ${index + 2} must contain three values." }
            val id = row[header.indexOf("position_id")].toIntOrNull()
            val x = row[header.indexOf("rx_x_m")].toDoubleOrNull()
            val y = row[header.indexOf("rx_y_m")].toDoubleOrNull()
            require(id != null && id in 0..999999 && x != null && y != null && x.isFinite() && y.isFinite()) {
                "Check row ${index + 2}: use a whole position ID and two measured coordinates with decimal points."
            }
            require(seen.add(id)) { "Position $id appears more than once. Give each mark its own ID." }
            Position(id, x, y, "positions.csv")
        }
        // Validate the entire document before replacing anything. Saved sessions stay untouched.
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(prefs.edit().putString(KEY, JSONArray(positions.map { it.json() }).toString())
            .putString("positions_file_sha256", hash).remove("active_research_session")
            .putString("position_id", positions.first().id.toString())
            .putString("rx_x_m", positions.first().x.toString()).putString("rx_y_m", positions.first().y.toString())
            .putString("position_source", "positions.csv").remove("last_capture_position").commit()) {
            "The positions could not be saved. Try importing again."
        }
        return positions.size
    }

    private fun cells(line: String): List<String> = line.split(',').map { raw ->
        val value = raw.trim()
        if (value.startsWith('"') && value.endsWith('"') && value.length >= 2)
            value.substring(1, value.length - 1).trim() else value
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "The position file is too large. Use a CSV under 1 MB." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun select(prefs: SharedPreferences, position: Position) {
        prefs.edit().putString("position_id", position.id.toString())
            .putString("rx_x_m", position.x?.toString().orEmpty())
            .putString("rx_y_m", position.y?.toString().orEmpty())
            .putString("position_source", position.source).apply()
    }

    fun next(prefs: SharedPreferences): Position? {
        val current = ResearchSettings.position(prefs)
        val rows = read(prefs)
        if (current.source == "positions.csv" && rows.isNotEmpty()) {
            val index = rows.indexOfFirst { it.id == current.id }
            return rows.getOrNull(index + 1).takeIf { index >= 0 }
        }
        return if (current.id < 999999) Position(current.id + 1, null, null) else null
    }

    fun snapshot(prefs: SharedPreferences): JSONObject = JSONObject()
        .put("source", "researcher-supplied; never estimated by the phone")
        .put("file_sha256", prefs.getString("positions_file_sha256", null) ?: JSONObject.NULL)
        .put("positions", JSONArray(read(prefs).map { it.json() }))
}
