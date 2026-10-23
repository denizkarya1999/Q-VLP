package com.developer27.qvlp.capture

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class BurstLocation(val sessionId: String, val directory: File, val mounting: JSONObject)
internal data class SavedSession(val id: String, val label: String, val warnings: String)

internal object SessionFiles {
    val io = Executors.newSingleThreadExecutor()
    @Volatile var activeCapture: File? = null
    private const val ACTIVE = "active_research_session"
    private fun root(context: Context) = File(context.filesDir, "sessions").apply { mkdirs() }
    private fun validId(id: String) = id.matches(Regex("session_[0-9a-f-]+"))

    fun directories(context: Context): List<File> = root(context).listFiles().orEmpty()
        .filter { it.isDirectory && validId(it.name) }

    fun newSession(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(ACTIVE).apply()
    }

    /** Called on the capture I/O worker. Mount changes create a new session automatically. */
    fun createBurst(context: Context, position: Position, mounting: JSONObject): BurstLocation {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prior = prefs.getString(ACTIVE, null)?.takeIf(::validId)
        var session = prior?.let { File(root(context), it) }
        val previousMount = session?.let { runCatching { readJson(File(it, "session.json")).getJSONObject("mounting") }.getOrNull() }
        if (session == null || previousMount?.toString() != mounting.toString()) {
            session = File(root(context), "session_${UUID.randomUUID()}")
            check(session.mkdirs()) { "Cannot create the session folder." }
            writeJson(File(session, "session.json"), JSONObject().apply {
                put("schema_version", 1)
                put("processing_stage", "pre_cv")
                put("session_id", session.name)
                put("created_at", Instant.now().toString())
                put("device_manufacturer", Build.MANUFACTURER)
                put("device_model", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
                put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                put("mounting", mounting)
                put("survey", PositionCatalog.snapshot(prefs))
                put("image_format", "I420: 8-bit Y, then U, then V; no row padding or compression")
                put("image_orientation", "sensor-native; no pixel rotation or rescaling")
                put("unknown_values", "CSV empty cells; JSON null")
            })
            val survey = PositionCatalog.read(prefs)
            if (survey.isNotEmpty()) File(session, "positions.csv").bufferedWriter(Charsets.UTF_8).use { csv ->
                csv.appendLine("position_id,rx_x_m,rx_y_m")
                survey.forEach { csv.appendLine("${it.id},${it.x},${it.y}") }
            }
            prefs.edit().putString(ACTIVE, session.name).apply()
            File(session, "README.txt").writeText("""
                Q-VLP pre-CV capture session

                session.json records mounting details and the device. Each position_* folder
                is a separate capture attempt. Repeat preserves earlier attempts.
                burst.json records requested settings, actual per-frame metadata and warnings.
                frames.csv uses sensor timestamps; exposure_time_s is in seconds and fps is
                measured from consecutive saved-frame timestamps. The first fps cell is empty.
                LED columns are empty because no CV processing has run. Empty is not zero.

                frame_00000.yuv corresponds to frame_id 0 in that folder's CSV/JSON.
                Files are uncompressed I420: width*height Y bytes, followed by U then V,
                each ceil(width/2)*ceil(height/2) bytes. Dimensions are recorded per frame.
                Android row padding and interleaving have been removed. Pixels have not
                been rotated, scaled or JPEG encoded. Source crop/strides are in burst.json.

                Unknown mounting measurements and row time are null in JSON and empty in CSV.
                Row time is user-supplied for a specific camera and resolution, not estimated.
                A non-complete state indicates an interrupted attempt; saved frames remain usable.
            """.trimIndent() + "\n")
        }
        val directory = File(session, "position_${position.id}_${UUID.randomUUID()}")
        check(directory.mkdirs()) { "Cannot create the capture folder." }
        return BurstLocation(session.name, directory, mounting)
    }

    fun list(context: Context): List<SavedSession> = directories(context)
        .sortedByDescending { File(it, "session.json").lastModified() }
        .map { dir ->
            val info = runCatching { readJson(File(dir, "session.json")) }.getOrNull()
            val captures = dir.listFiles().orEmpty().filter { it.isDirectory }
            val count = captures.sumOf { it.listFiles()?.count { f -> f.extension == "yuv" } ?: 0 }
            val pending = captures.count { runCatching { readJson(File(it, "burst.json")).optString("state") != "complete" }.getOrDefault(true) }
            val issues = captures.flatMap { capture ->
                runCatching {
                    val data = readJson(File(capture, "burst.json"))
                    val warnings = data.optJSONArray("warnings") ?: org.json.JSONArray()
                    val position = data.optJSONObject("position")?.optInt("position_id")
                    (0 until warnings.length()).map { "Position $position: ${warnings.getString(it)}" }
                }.getOrElse { listOf("Incomplete capture metadata in ${capture.name}.") }
            }
            val date = runCatching {
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(java.util.Locale.getDefault()).withZone(ZoneId.systemDefault())
                    .format(Instant.parse(info?.optString("created_at")))
            }.getOrDefault("Saved session")
            SavedSession(dir.name, "$date\n${captures.size} captures · $count frames" +
                (if (pending > 0) " · $pending interrupted/incomplete" else "") +
                (if (issues.isNotEmpty()) " · ${issues.size} warnings" else "") +
                "\n${PublicSessionStorage.status(context, dir)}",
                issues.take(20).joinToString("\n\n") + if (issues.size > 20) "\n\nMore warnings are included in the ZIP." else "")
        }

    fun archive(context: Context, id: String): File {
        require(validId(id))
        val session = File(root(context), id)
        check(session.isDirectory) { "Session not found." }
        val exports = File(context.cacheDir, "session_exports").apply { mkdirs() }
        val file = File(exports, "${id}_${UUID.randomUUID()}.zip")
        val partial = File(exports, "${file.name}.part")
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                session.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") &&
                    !it.name.endsWith(".bak") && !it.name.endsWith(".new") }.forEach { source ->
                    zip.putNextEntry(ZipEntry("$id/${source.relativeTo(session).invariantSeparatorsPath}"))
                    source.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            check(partial.renameTo(file)) { "Cannot finish the ZIP file." }
            return file
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
    }

    fun readJson(file: File) = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })

    fun writeJson(file: File, data: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(data.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}
