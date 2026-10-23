package com.developer27.qvlp.capture

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Called only on SessionFiles.io. Originals are immutable; only the session index is replaced. */
internal object SessionCsv {
    val columns = listOf("session_id", "position_id", "rx_x_m", "rx_y_m", "frame_id", "timestamp_ns",
        "exposure_time_s", "iso", "fps", "sensor_width_px", "sensor_height_px", "row_time_us") +
        (1..4).flatMap { led -> listOf("detected", "img_x_px", "img_y_px", "freq_est_hz", "stripe_period_rows",
            "blob_area_px", "peak_level", "touches_border").map { "led${led}_$it" } }
    val header = columns.joinToString(",")
    private data class Attempt(val folder: File, val data: JSONObject, val position: Int, val finished: Instant)

    fun update(session: File) {
        val metadataFile = File(session, "session.json")
        val metadata = SessionFiles.readJson(metadataFile)
        val folders = session.listFiles().orEmpty().filter { it.isDirectory && it != SessionFiles.activeCapture && it.name.startsWith("position_") }
            .sortedBy { it.name }
        val signature = sha(folders.flatMap { folder -> listOf("burst.json", "frames.csv").map { name ->
            val file = File(folder, name)
            "${folder.name}/$name:${file.exists()}:${file.length()}:${file.lastModified()}"
        } }.joinToString("\n").toByteArray())
        val output = File(session, "frames.csv")
        val previous = metadata.optJSONObject("frame_export")
        if (previous?.optInt("version") == 1 && previous.optString("source_signature") == signature &&
            output.isFile && previous.optString("csv_sha256") == sha(output)) return

        val excluded = JSONArray()
        val completed = folders.mapNotNull { folder ->
            val data = runCatching { SessionFiles.readJson(File(folder, "burst.json")) }.getOrNull()
            if (data?.optString("state") != "complete") {
                excluded.put(JSONObject().put("attempt", folder.name).put("reason", data?.optString("state") ?: "unreadable_metadata"))
                null
            } else Attempt(folder, data, data.getJSONObject("position").getInt("position_id"),
                Instant.parse(data.getString("finished_at")))
        }
        val selected = completed.groupBy { it.position }.mapValues { (_, attempts) ->
            attempts.maxWith(compareBy<Attempt> { it.finished }.thenBy { it.folder.name })
        }
        completed.filter { selected[it.position] != it }.forEach {
            excluded.put(JSONObject().put("attempt", it.folder.name).put("reason", "earlier_completed_attempt"))
        }
        val survey = metadata.optJSONObject("survey")?.optJSONArray("positions") ?: JSONArray()
        val order = (0 until survey.length()).associate { survey.getJSONObject(it).getInt("position_id") to it }
        val attempts = selected.values.sortedWith(compareBy<Attempt> { order[it.position] ?: Int.MAX_VALUE }.thenBy { it.position })
        val selection = JSONArray()
        val atomic = AtomicFile(output)
        val stream = atomic.startWrite()
        try {
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            writer.appendLine(header)
            attempts.forEach { attempt ->
                val source = File(attempt.folder, "frames.csv")
                check(source.isFile) { "Missing frames.csv in ${attempt.folder.name}. Original recordings are preserved." }
                var count = 0
                val ids = hashSetOf<Int>()
                source.bufferedReader(Charsets.UTF_8).use { input ->
                    check(input.readLine() == header) { "Unexpected CSV columns in ${attempt.folder.name}." }
                    input.lineSequence().forEach { line ->
                        val fields = line.split(',')
                        check(fields.size == columns.size && fields[0] == session.name && fields[1].toIntOrNull() == attempt.position) {
                            "Invalid session or position in ${attempt.folder.name}/frames.csv."
                        }
                        val frameId = fields[4].toIntOrNull()
                        check(frameId != null && frameId >= 0 && ids.add(frameId)) { "Invalid or repeated frame ID in ${attempt.folder.name}." }
                        writer.appendLine(line)
                        count++
                    }
                }
                check(count == attempt.data.getInt("frames_saved")) { "Frame count does not match ${attempt.folder.name}." }
                selection.put(JSONObject().put("position_id", attempt.position).put("attempt", attempt.folder.name)
                    .put("frames", count).put("finished_at", attempt.finished.toString()))
            }
            writer.flush()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        // CSV first, then its hash and selection. An interrupted update is rebuilt on reopening.
        metadata.put("frame_export", JSONObject().put("version", 1).put("file", "frames.csv")
            .put("repeat_policy", "latest_completed_attempt_per_position").put("source_signature", signature)
            .put("csv_sha256", sha(output)).put("selected_attempts", selection).put("excluded_attempts", excluded))
        SessionFiles.writeJson(metadataFile, metadata)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
