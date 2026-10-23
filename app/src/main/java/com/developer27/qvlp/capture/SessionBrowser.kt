package com.developer27.qvlp.capture

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

internal class SessionBrowser(private val activity: AppCompatActivity, private val busy: (Boolean) -> Unit,
                              private val sessionChanged: () -> Unit) {
    private var pendingZip: File? = null
    private var saving = false
    private val chooseFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            saving = true
            busy(true)
            message("Saving sessions to Pictures/Q-VLP…")
            SessionFiles.io.execute {
                val result = runCatching {
                    PublicSessionStorage.select(activity.applicationContext, uri)
                    PublicSessionStorage.syncAll(activity.applicationContext, verify = true).getOrThrow()
                }
                ui {
                    saving = false
                    busy(false)
                    if (result.isSuccess) message("${result.getOrNull()} session(s) saved to Pictures/Q-VLP.")
                    else AlertDialog.Builder(activity).setTitle("Folder save needs attention")
                        .setMessage("${result.exceptionOrNull()?.message}\n\nYour original files are safe in the app. Open Sessions → Storage to retry.")
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }
    private val saveFile = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = pendingZip
        pendingZip = null
        if (uri != null && source != null) {
            busy(true)
            SessionFiles.io.execute {
                val result = runCatching {
                    val out = activity.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Cannot open the selected destination.")
                    out.buffered().use { target -> source.inputStream().buffered().use { it.copyTo(target) } }
                }
                ui {
                    busy(false)
                    message(result.exceptionOrNull()?.let { "Export failed: ${it.message}" }
                        ?: "Saved. Connect your phone by USB to copy this file to your computer.")
                }
            }
        }
    }

    fun restore(state: Bundle?) {
        state?.getString("pending_session_zip")?.let { path ->
            val file = File(path)
            val allowed = File(activity.cacheDir, "session_exports").canonicalFile
            if (file.canonicalFile.parentFile == allowed && file.isFile) pendingZip = file
        }
    }
    fun save(state: Bundle) { pendingZip?.let { state.putString("pending_session_zip", it.absolutePath) } }

    fun readyForCapture(): Boolean {
        if (PublicSessionStorage.configured(activity)) return true
        storage()
        return false
    }

    /** Retry interrupted publication when the app returns; the capture worker uses the same queue. */
    fun resumeSaving() {
        if (PublicSessionStorage.configured(activity) && !saving) saveSessions(announce = false)
    }

    private fun storage() {
        val configured = PublicSessionStorage.configured(activity)
        val error = PublicSessionStorage.error(activity)
        AlertDialog.Builder(activity).setTitle("Session Storage")
            .setMessage("Sessions save automatically to Internal storage → Pictures → Q-VLP after each capture. Raw frames, measurements and repeated attempts keep their own folders.\n\n" +
                (if (configured) "Your existing sessions are also copied there. The app keeps a recovery copy. Save now verifies all public files and retries unfinished copies."
                else "Choose Pictures or its Q-VLP folder, then tap Use this folder and Allow. Your existing sessions will be copied there too.") +
                (if (error == null) "" else "\n\nLast save needs attention: $error\nYour original files are safe in the app."))
            .setPositiveButton(if (configured) "Save now" else "Choose folder") { _, _ ->
                if (configured) saveSessions(verify = true, announce = true) else chooseFolder.launch(PublicSessionStorage.initialUri)
            }
            .apply { if (configured) setNeutralButton("Choose folder") { _, _ -> chooseFolder.launch(PublicSessionStorage.initialUri) } }
            .setNegativeButton("Close", null).show()
    }

    private fun saveSessions(verify: Boolean = false, announce: Boolean) {
        if (saving) return
        saving = true
        busy(true)
        if (announce) message("Saving sessions to Pictures/Q-VLP…")
        SessionFiles.io.execute {
            val result = PublicSessionStorage.syncAll(activity.applicationContext, verify)
            ui {
                saving = false
                busy(false)
                result.exceptionOrNull()?.let {
                    AlertDialog.Builder(activity).setTitle("Folder save needs attention")
                        .setMessage("${it.message}\n\nYour original files are safe in the app. Open Sessions → Storage to retry.")
                        .setPositiveButton(android.R.string.ok, null).show()
                } ?: if (announce) message("${result.getOrNull()} session(s) saved to Pictures/Q-VLP.") else Unit
            }
        }
    }

    fun show() {
        busy(true)
        SessionFiles.io.execute {
            val result = runCatching { SessionFiles.list(activity.applicationContext) }
            ui {
                busy(false)
                val sessions = result.getOrElse { message("Cannot list sessions: ${it.message}"); return@ui }
                val dialog = AlertDialog.Builder(activity).setTitle("Saved Sessions")
                    .setNegativeButton("Close", null)
                    .setNeutralButton("Storage") { _, _ -> storage() }
                    .setPositiveButton("New Session") { _, _ ->
                        SessionFiles.newSession(activity.applicationContext)
                        sessionChanged()
                        message("The next capture will start a new session.")
                    }
                if (sessions.isEmpty()) dialog.setMessage("Start a capture to save your first session.")
                else dialog.setItems(sessions.map { it.label }.toTypedArray()) { _, index -> actions(sessions[index]) }
                dialog.show()
            }
        }
    }

    private fun actions(session: SavedSession) {
        AlertDialog.Builder(activity).setTitle("Session")
            .setMessage("${session.label}\n\nBrowse raw session folders in Internal storage → Pictures → Q-VLP, or use Share / Save ZIP for a compressed copy. Open Storage to set up or retry automatic saving." +
                if (session.warnings.isBlank()) "" else "\n\n${session.warnings}")
            .setPositiveButton("Share") { _, _ -> export(session, share = true) }
            .setNeutralButton("Save ZIP") { _, _ -> export(session, share = false) }
            .setNegativeButton("Close", null).show()
    }

    private fun export(session: SavedSession, share: Boolean) {
        busy(true)
        message("Preparing your session…")
        SessionFiles.io.execute {
            val result = runCatching { SessionFiles.archive(activity.applicationContext, session.id) }
            ui {
                busy(false)
                val file = result.getOrElse { message("Export failed: ${it.message}"); return@ui }
                try {
                    if (share) {
                        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.sessionfiles", file)
                        val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .apply { clipData = ClipData.newRawUri("Q-VLP session", uri) }
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        activity.startActivity(Intent.createChooser(intent, "Share Q-VLP session"))
                    } else {
                        pendingZip = file
                        saveFile.launch("${session.id}.zip")
                    }
                } catch (e: Exception) {
                    message("Could not open export: ${e.message}")
                }
            }
        }
    }

    private fun ui(action: () -> Unit) = activity.runOnUiThread {
        if (!activity.isDestroyed && !activity.isFinishing) action()
    }
    private fun message(value: String) = Toast.makeText(activity, value, Toast.LENGTH_LONG).show()
}
