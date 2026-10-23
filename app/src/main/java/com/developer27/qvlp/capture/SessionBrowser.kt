package com.developer27.qvlp.capture

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.developer27.qvlp.R
import com.developer27.qvlp.databinding.DialogSessionsTitleBinding
import java.io.File

internal class SessionBrowser(private val activity: AppCompatActivity, private val busy: (Boolean) -> Unit,
                              private val sessionChanged: (resetPosition: Boolean) -> Unit) {
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
                (if (error == null) "" else "\n\nStorage needs attention: $error"))
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
                val selected = linkedSetOf<SavedSession>()
                var selecting = false
                val builder = AlertDialog.Builder(activity)
                val title = DialogSessionsTitleBinding.inflate(android.view.LayoutInflater.from(builder.context))
                androidx.core.view.ViewCompat.setAccessibilityHeading(title.sessionDialogTitle, true)
                val labels = sessions.map { it.label }.toTypedArray()
                val attributes = builder.context.obtainStyledAttributes(intArrayOf(android.R.attr.listChoiceIndicatorMultiple))
                val checkMark = try { attributes.getDrawable(0)?.constantState } finally { attributes.recycle() }
                val rows = object : android.widget.ArrayAdapter<String>(builder.context,
                    android.R.layout.simple_list_item_multiple_choice, labels) {
                    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View =
                        super.getView(position, convertView, parent).apply {
                            (this as android.widget.CheckedTextView).checkMarkDrawable =
                                if (selecting) checkMark?.newDrawable(resources)?.mutate() else null
                        }
                }
                builder.setCustomTitle(title.root)
                    .setNeutralButton("Close", null)
                    .setNegativeButton("New Session") { _, _ ->
                        SessionFiles.newSession(activity.applicationContext)
                        sessionChanged(true)
                        message("The next capture will start a new session.")
                    }
                    .setPositiveButton(activity.getString(R.string.delete_session_count, 0), null)
                if (sessions.isEmpty()) builder.setMessage("Start a capture to save your first session.")
                else builder.setAdapter(rows, null)
                val dialog = builder.create()
                fun updateSelection() {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        text = activity.getString(R.string.delete_session_count, selected.size)
                        isEnabled = selected.isNotEmpty()
                    }
                }
                dialog.setOnShowListener {
                    updateSelection()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        confirmDelete(selected.toList()) { dialog.dismiss() }
                    }
                    dialog.listView?.apply {
                        setOnItemClickListener { _, _, index, _ ->
                            val session = sessions[index]
                            if (!selecting) details(session) else {
                                if (isItemChecked(index)) selected.add(session) else selected.remove(session)
                                updateSelection()
                            }
                        }
                        setOnItemLongClickListener { _, _, index, _ ->
                            if (!selecting) {
                                selecting = true
                                choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
                                title.sessionSelectionHint.setText(R.string.session_selection_active)
                                // Keep the adapter: replacing it during a hold resets ListView's gesture state.
                                rows.notifyDataSetChanged()
                            }
                            setItemChecked(index, true)
                            selected.add(sessions[index])
                            updateSelection()
                            true
                        }
                    }
                }
                dialog.show()
            }
        }
    }

    private fun details(session: SavedSession) {
        AlertDialog.Builder(activity).setTitle("Session details")
            .setMessage("${session.label}\n\nFolder: Pictures/Q-VLP/${session.id}/\n" +
                if (session.deleting) "Deletion is pending. Close these details, select the session and tap Delete to retry."
                else "frames.csv combines the latest completed attempt for each position. Earlier and interrupted attempts stay in their original folders. Share / Save ZIP creates an optional compressed copy." +
                    if (session.warnings.isBlank()) "" else "\n\n${session.warnings}")
            .apply {
                if (!session.deleting) {
                    setPositiveButton("Share") { _, _ -> export(session, share = true) }
                    setNeutralButton("Save ZIP") { _, _ -> export(session, share = false) }
                }
            }
            .setNegativeButton("Close", null).show()
    }

    private fun confirmDelete(sessions: List<SavedSession>, confirmed: () -> Unit) {
        if (sessions.isEmpty()) return
        val names = sessions.joinToString("\n\n") { "${it.label.substringBefore('\n')}\n${it.id}" }
        AlertDialog.Builder(activity).setTitle(if (sessions.size == 1) "Delete session?" else "Delete ${sessions.size} sessions?")
            .setMessage("$names\n\nThis permanently removes all captures in the selected sessions from the app and Pictures/Q-VLP. ZIPs you saved or shared elsewhere are kept.\n\nThis cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> confirmed(); delete(sessions) }
            .show()
    }

    private fun delete(sessions: List<SavedSession>) {
        busy(true)
        val preparing = sessions.map { it.id }.toMutableSet()
        val results = mutableListOf<Result<Unit>>()
        fun prepared(id: String) {
            if (preparing.remove(id) && preparing.isEmpty()) busy(false)
        }
        message(if (sessions.size == 1) "Deleting session…" else "Deleting ${sessions.size} sessions…")
        sessions.forEach { session ->
            SessionPublisher.delete(activity.applicationContext, session.id, prepared = { active -> ui {
                prepared(session.id)
                if (active) sessionChanged(false)
                if (pendingZip?.name?.startsWith("${session.id}_") == true) pendingZip = null
            } }, completed = { result -> ui {
                prepared(session.id)
                results.add(result)
                if (results.size == sessions.size) {
                    val count = results.count { it.isSuccess }
                    val error = results.firstOrNull { it.isFailure }?.exceptionOrNull()
                    message(if (error != null) "$count of ${sessions.size} deleted. ${error.message} Open Sessions to retry."
                        else if (count == 1) "Session deleted." else "$count sessions deleted.")
                }
            } })
        }
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
