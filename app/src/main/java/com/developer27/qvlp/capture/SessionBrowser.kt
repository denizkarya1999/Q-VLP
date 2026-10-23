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
    private val chooseFolder = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) SessionPublisher.selectFolder(activity.applicationContext, uri) { result -> ui {
            result.exceptionOrNull()?.let { message(it.message ?: "Cannot use this folder.") }
                ?: message("Auto-save is ready. Each finished position saves to its session folder.")
        } }
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

    fun resumeSaving() = SessionPublisher.request(activity.applicationContext)

    fun storage(retry: Boolean = false) {
        val configured = PublicSessionStorage.configured(activity)
        val error = PublicSessionStorage.error(activity)
        AlertDialog.Builder(activity).setTitle("Auto-save")
            .setMessage("Each finished position saves automatically to its session folder in Internal storage → Pictures → Q-VLP. Repeat keeps a separate attempt in the same session.\n\n" +
                (if (configured) "You can move to the next position while saving finishes."
                else "Choose Pictures or its Q-VLP folder once, then tap Use this folder and Allow.") +
                (if (error == null) "" else "\n\nSave needs attention: $error\nYour recordings are safe in the app."))
            .apply {
                if (!configured) setPositiveButton("Choose folder") { _, _ -> chooseFolder.launch(PublicSessionStorage.initialUri) }
                else if (error != null || retry) {
                    setPositiveButton("Retry") { _, _ -> SessionPublisher.request(activity.applicationContext) }
                    setNeutralButton("Choose folder") { _, _ -> chooseFolder.launch(PublicSessionStorage.initialUri) }
                }
            }
            .setNegativeButton("Close", null).show()
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
                    .setNeutralButton("Save location") { _, _ -> storage() }
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
            .setMessage("${session.label}\n\nAuto-save folder: Pictures/Q-VLP/${session.id}/\nframes.csv combines the latest completed attempt for each position. Earlier and interrupted attempts stay in their original folders. Share / Save ZIP creates an optional compressed copy." +
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
