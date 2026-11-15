package com.developer27.qvlp.capture

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class BurstLocation(val sessionId: String, val directory: File)
internal data class SavedSession(val id: String, val label: String, val warnings: String, val deleting: Boolean = false)

internal object SessionFiles {
    val io = Executors.newSingleThreadExecutor()
    @Volatile var activeCapture: File? = null

    /** A capture still being written, or the processed folder that belongs to it. Neither may be published yet. */
    fun isActive(folder: File): Boolean = activeCapture.let { active ->
        active != null && (folder == active || folder == SessionLayout.processedFolder(active))
    }
    private const val ACTIVE = "active_research_session"
    @Volatile private var adopted = false

    /** Every session is reached through here, so sessions saved in the earlier layout are moved over first. */
    private fun root(context: Context): File {
        val root = File(context.filesDir, "sessions").apply { mkdirs() }
        if (!adopted) synchronized(this) {
            if (!adopted) {
                root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }.forEach { SessionLayout.adopt(it) }
                adopted = true
            }
        }
        return root
    }
    fun validId(id: String) = id.matches(Regex("session_[0-9a-f-]+"))
    private fun deletions(context: Context) = context.getSharedPreferences("session_deletions", Context.MODE_PRIVATE)
    fun isDeleting(context: Context, id: String) = deletions(context).contains(id)
    fun pendingDeletions(context: Context): Set<String> = deletions(context).all.keys.filter(::validId).toSet()

    /** Persist intent before removing either copy. Called on io, after earlier capture/export work. */
    fun beginDeletion(context: Context, id: String): Boolean {
        require(validId(id)) { "Invalid session." }
        val session = File(root(context), id)
        check(session.isDirectory || isDeleting(context, id)) { "Session not found." }
        check(activeCapture?.let(SessionLayout::session) != session) { "Finish or cancel the recording before deleting its session." }
        check(deletions(context).edit().putLong(id, System.currentTimeMillis()).commit()) { "Cannot remember the deletion." }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val active = prefs.getString(ACTIVE, null) == id
        if (active) check(prefs.edit().remove(ACTIVE).remove("last_capture_position").commit()) { "Cannot start a new session." }
        return active
    }

    /** Public deletion must succeed first. Keep the marker until all cleanup has succeeded. */
    fun deletePrivateFiles(context: Context, id: String) {
        require(validId(id) && isDeleting(context, id))
        val session = File(root(context), id)
        check(!session.exists() || session.deleteRecursively()) { "Cannot remove all session files. Retry deletion." }
        File(context.cacheDir, "session_exports").listFiles().orEmpty()
            .filter { it.name.startsWith("${id}_") }.forEach {
                check(it.delete()) { "Cannot remove the cached ZIP. Retry deletion." }
            }
    }

    fun finishDeletion(context: Context, id: String) {
        require(validId(id))
        check(deletions(context).edit().remove(id).commit()) { "Cannot finish deleting the session. Retry deletion." }
    }

    fun directories(context: Context): List<File> = root(context).listFiles().orEmpty()
        .filter { it.isDirectory && validId(it.name) }

    fun activeSessionId(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(ACTIVE, null)

    /** The positions that already have a capture in the session now being recorded. */
    fun capturedPositions(context: Context): Set<Int> {
        val id = activeSessionId(context)?.takeIf(::validId) ?: return emptySet()
        return SessionLayout.captures(File(root(context), id)).mapNotNull { SessionLayout.positionId(it.name) }.toSet()
    }

    fun newSession(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(ACTIVE).apply()
        SessionPublisher.sessionChanged()
    }

    /** Called on the capture I/O worker. */
    fun createBurst(context: Context, position: Position): BurstLocation {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val prior = prefs.getString(ACTIVE, null)?.takeIf(::validId)
        var session = prior?.takeUnless { isDeleting(context, it) }?.let { File(root(context), it) }
        if (session == null || !session.isDirectory) {
            session = File(root(context), "session_${UUID.randomUUID()}")
            check(session.mkdirs()) { "Cannot create the session folder." }
            writeJson(File(session, "session.json"), JSONObject().apply {
                put("schema_version", 1)
                put("processing_stage", "led_detection")
                put("session_id", session.name)
                put("created_at", Instant.now().toString())
                put("device_manufacturer", Build.MANUFACTURER)
                put("device_model", Build.MODEL)
                put("android_api", Build.VERSION.SDK_INT)
                put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                put("image_format", "PNG: lossless 8-bit RGB, converted from the camera's YUV 4:2:0 with full-range BT.601")
                put("image_orientation", "sensor-native; no pixel rotation or rescaling")
                put("unknown_values", "CSV empty cells; JSON null")
            })
            prefs.edit().putString(ACTIVE, session.name).apply()
            SessionPublisher.sessionChanged()
        }
        // Captures a second apart can never collide; anything closer gets a suffix.
        val name = SessionLayout.captureName(position.id, LocalDateTime.now())
        val originals = SessionLayout.originals(session)
        val directory = generateSequence(0) { it + 1 }.map { File(originals, if (it == 0) name else "$name-$it") }.first { !it.exists() }
        check(File(directory, SessionLayout.FRAMES).mkdirs()) { "Cannot create the capture folder." }
        return BurstLocation(session.name, directory)
    }

    fun list(context: Context): List<SavedSession> = (directories(context) +
        pendingDeletions(context).map { File(root(context), it) }).distinct()
        .sortedByDescending { File(it, "session.json").lastModified() }
        .map { dir ->
            if (isDeleting(context, dir.name)) return@map SavedSession(dir.name,
                "Deletion pending · Select to retry\n${dir.name}", "", deleting = true)
            val info = runCatching { readJson(File(dir, "session.json")) }.getOrNull()
            val captures = SessionLayout.captures(dir)
            val count = captures.sumOf { capture ->
                File(capture, SessionLayout.FRAMES).list().orEmpty().count { it.endsWith(".png") }.takeIf { it > 0 }
                    // Captures recorded by earlier versions keep their frames beside burst.json.
                    ?: capture.list().orEmpty().count { it.startsWith("frame_") && (it.endsWith(".png") || it.endsWith(".yuv")) }
            }
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
        check(!isDeleting(context, id)) { "This session is being deleted." }
        val session = File(root(context), id)
        check(session.isDirectory) { "Session not found." }
        SessionCsv.update(session)
        val exports = File(context.cacheDir, "session_exports").apply { mkdirs() }
        val file = File(exports, "${id}_${UUID.randomUUID()}.zip")
        val partial = File(exports, "${file.name}.part")
        try {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                session.walkTopDown().filter { it.isFile && SessionLayout.isOutput(it) }.forEach { source ->
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
