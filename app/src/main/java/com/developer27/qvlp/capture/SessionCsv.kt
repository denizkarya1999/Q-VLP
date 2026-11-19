package com.developer27.qvlp.capture

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** Builds a session's final.csv. Called only on SessionFiles.io. Captures are never modified. */
internal object SessionCsv {
    const val LEDS = 4
    /** A position's LED counts as detected when it was valid in at least this share of the frames. */
    const val MIN_VALID_SHARE = 0.7
    // Raised whenever the session files change shape, so existing sessions are rebuilt once.
    private const val EXPORT_VERSION = 3

    /** final.csv: the fingerprint database, one row per position. */
    val finalColumns = listOf("position_id") + (1..LEDS).flatMap { led -> listOf("u_px", "v_px", "detected").map { "LED_${led}_$it" } }
    val finalHeader = finalColumns.joinToString(",")

    /** One LED in one processed frame. */
    data class LedPoint(val detected: Boolean, val u: Double?, val v: Double?)

    /** [valid] of [frames] frames passed every quality rule; [u] and [v] are their median centre. */
    data class LedFingerprint(val processed: Boolean, val valid: Int, val frames: Int, val u: Double?, val v: Double?) {
        val detected: Boolean get() = processed && frames > 0 && valid >= MIN_VALID_SHARE * frames
    }

    /** One entry per LED, from the processed frames of one capture. No frames means it was never processed. */
    fun fingerprint(frames: List<List<LedPoint>>): List<LedFingerprint> = (0 until LEDS).map { led ->
        val valid = frames.mapNotNull { frame -> frame.getOrNull(led)?.takeIf { it.detected && it.u != null && it.v != null } }
        LedFingerprint(frames.isNotEmpty(), valid.size, frames.size, median(valid.map { it.u!! }), median(valid.map { it.v!! }))
    }

    /** Coordinates are written only for a detected LED: a wrong centre is worse than a missing one. */
    fun finalRow(positionId: String, leds: List<LedFingerprint>): String = (listOf(positionId) + leds.flatMap { led ->
        when {
            !led.processed -> listOf("", "", "")
            led.detected -> listOf(CaptureProcessing.number(led.u), CaptureProcessing.number(led.v), "1")
            else -> listOf("", "", "0")
        }
    }).joinToString(",")

    /** The LEDs of one processed frame's metadata file. */
    fun points(metadata: JSONObject): List<LedPoint> = (1..LEDS).map { led ->
        LedPoint(metadata.optInt("LED_${led}_detected", 0) == 1,
            metadata.optDouble("LED_${led}_u_px").takeIf { it.isFinite() }, metadata.optDouble("LED_${led}_v_px").takeIf { it.isFinite() })
    }

    /**
     * Every frame a processed capture lists in its mapping file, read from the metadata files it names.
     * Empty when the capture has not been processed.
     */
    fun readProcessed(processed: File): List<List<LedPoint>> =
        read(File(processed, SessionLayout.PROCESSED_METADATA), File(processed, SessionLayout.PROCESSED_MAPPING))

    /** The same for the post-inference files of an Inference Mode capture: the LEDs the two models found and named. */
    fun readInference(inference: File): List<List<LedPoint>> =
        read(File(inference, SessionLayout.INFERENCE_METADATA), File(inference, SessionLayout.INFERENCE_MAPPING))

    /**
     * The same for an Inference Mode capture made with Enable CV Algorithms: the blob centroid of each named
     * LED. They are in the processed frame metadata, and only the frames the models ran on count, which are
     * the ones the inference mapping lists. A frame's metadata file has the same name in both folders.
     */
    fun readCentroids(processed: File, inference: File): List<List<LedPoint>> =
        read(File(processed, SessionLayout.PROCESSED_METADATA), File(inference, SessionLayout.INFERENCE_MAPPING))

    private fun read(folder: File, mapping: File): List<List<LedPoint>> =
        SessionLayout.readMapping(mapping).mapNotNull { (_, metadata) ->
            runCatching { points(JSONObject(File(folder, metadata).readText())) }.getOrNull()
        }

    /** [mapping] is the file whose presence and contents say the frames behind [read] are complete. */
    private class Source(val name: String, val mapping: File, val read: () -> List<List<LedPoint>>)

    /**
     * Where a capture's row of final.csv comes from. A Data Collection capture: LED detection. An Inference
     * Mode capture: the two LED models' results, and with [centroids] (Enable CV Algorithms was ticked) the
     * blob centroid found inside each of their boxes.
     */
    private fun source(capture: File, centroids: Boolean): Source {
        val processed = SessionLayout.processedFolder(capture)
        val inference = SessionLayout.inferenceFolder(capture)
        val inferenceMapping = File(inference, SessionLayout.INFERENCE_MAPPING)
        return when {
            !inferenceMapping.isFile -> Source("led_detection", File(processed, SessionLayout.PROCESSED_MAPPING)) { readProcessed(processed) }
            centroids -> Source("led_blob_centroid", inferenceMapping) { readCentroids(processed, inference) }
            else -> Source("led_recognition_model", inferenceMapping) { readInference(inference) }
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2.0
    }

    /** [centroids]: the capture's record says its LED positions are blob centroids. */
    private data class Attempt(val folder: File, val position: Int, val finished: Instant, val centroids: Boolean)
    // A capture never changes once processed, so its fingerprint is read from its files only once per run.
    private val fingerprints = HashMap<String, Pair<String, List<LedFingerprint>>>()

    private fun stamp(file: File) = "${file.exists()}:${file.length()}:${file.lastModified()}"

    fun update(session: File) {
        val metadataFile = File(session, "session.json")
        val metadata = SessionFiles.readJson(metadataFile)
        val folders = SessionLayout.captures(session).filter { !SessionFiles.isActive(it) }.sortedBy { it.name }
        val signature = sha(folders.joinToString("\n") { folder ->
            "${folder.name}:${stamp(File(folder, "burst.json"))}:${stamp(File(SessionLayout.processedFolder(folder), SessionLayout.PROCESSED_MAPPING))}" +
                ":${stamp(File(SessionLayout.inferenceFolder(folder), SessionLayout.INFERENCE_MAPPING))}"
        }.toByteArray())
        val output = File(session, SessionLayout.FINAL)
        val previous = metadata.optJSONObject("export")
        if (previous?.optInt("version") == EXPORT_VERSION && previous.optString("source_signature") == signature &&
            output.isFile && previous.optString("final_csv_sha256") == sha(output)) return

        val excluded = JSONArray()
        val completed = folders.mapNotNull { folder ->
            val data = runCatching { SessionFiles.readJson(File(folder, "burst.json")) }.getOrNull()
            if (data?.optString("state") != "complete") {
                excluded.put(JSONObject().put("attempt", folder.name).put("reason", data?.optString("state") ?: "unreadable_metadata"))
                null
            } else Attempt(folder, data.getJSONObject("position").getInt("position_id"), Instant.parse(data.getString("finished_at")),
                data.optJSONObject("led_recognition")?.optString("centre") == PostInferenceFiles.CENTRE_BLOB)
        }
        // A position captured again is replaced: only its latest completed capture counts.
        val selected = completed.groupBy { it.position }.mapValues { (_, attempts) ->
            attempts.maxWith(compareBy<Attempt> { it.finished }.thenBy { it.folder.name })
        }
        completed.filter { selected[it.position] != it }.forEach {
            excluded.put(JSONObject().put("attempt", it.folder.name).put("reason", "replaced_by_later_capture"))
        }
        val attempts = selected.values.sortedBy { it.position }
        val selection = JSONArray()
        val lines = ArrayList<String>()
        lines.add(finalHeader)
        attempts.forEach { attempt ->
            val source = source(attempt.folder, attempt.centroids)
            val version = "${source.name}:${stamp(source.mapping)}"
            val leds = fingerprints[source.mapping.path]?.takeIf { it.first == version }?.second
                ?: fingerprint(source.read()).also { fingerprints[source.mapping.path] = version to it }
            lines.add(finalRow(attempt.position.toString(), leds))
            selection.put(JSONObject().put("position_id", attempt.position).put("attempt", attempt.folder.name)
                .put("source", source.name)
                .put("frames", leds.firstOrNull()?.frames ?: 0).put("finished_at", attempt.finished.toString()))
        }
        val atomic = AtomicFile(output)
        val stream = atomic.startWrite()
        try {
            stream.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        // CSV first, then its hash and selection. An interrupted update is rebuilt on reopening.
        metadata.remove("frame_export")
        metadata.put("export", JSONObject().put("version", EXPORT_VERSION).put("file", SessionLayout.FINAL)
            .put("repeat_policy", "latest_completed_capture_per_position").put("source_signature", signature)
            .put("final_csv_sha256", sha(output)).put("selected_attempts", selection).put("excluded_attempts", excluded))
        SessionFiles.writeJson(metadataFile, metadata)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sha(file: File): String = sha(file.readBytes())
}
