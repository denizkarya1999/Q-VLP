package com.developer27.qvlp.capture

import android.content.SharedPreferences
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.developer27.qvlp.R

internal class PositionPicker(private val activity: AppCompatActivity, private val prefs: SharedPreferences,
                              private val busy: (Boolean) -> Unit, private val changed: (Boolean) -> Unit) {
    private val openFile = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        busy(true)
        val context = activity.applicationContext
        SessionFiles.io.execute {
            val result = runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open this file.")
                PositionCatalog.import(input, prefs).also { SessionPublisher.sessionChanged() }
            }
            activity.runOnUiThread {
                if (!activity.isDestroyed && !activity.isFinishing) {
                    busy(false)
                    result.fold(onSuccess = { count ->
                        changed(true)
                        Toast.makeText(activity, "$count positions loaded. Select your floor mark.", Toast.LENGTH_LONG).show()
                        show()
                    }, onFailure = {
                        AlertDialog.Builder(activity).setTitle(R.string.positions_import_failed)
                            .setMessage(it.message).setPositiveButton(android.R.string.ok, null).show()
                    })
                }
            }
        }
    }

    fun show() {
        val positions = PositionCatalog.read(prefs)
        if (positions.isEmpty()) { manual(); return }
        val current = ResearchSettings.position(prefs)
        AlertDialog.Builder(activity).setTitle(R.string.choose_position)
            .setSingleChoiceItems(positions.map { "Position ${it.id}   ·   X ${it.x} m, Y ${it.y} m" }.toTypedArray(),
                positions.indexOfFirst { it.id == current.id && current.source == "positions.csv" }) { dialog, index ->
                PositionCatalog.select(prefs, positions[index])
                changed(false)
                dialog.dismiss()
            }
            .setPositiveButton(R.string.import_positions) { _, _ -> importFile() }
            .setNeutralButton(R.string.enter_position) { _, _ -> manual() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun importFile() {
        // CSV MIME types vary by provider; the contents are validated before saving.
        runCatching { openFile.launch(arrayOf("*/*")) }.onFailure {
            Toast.makeText(activity, "No file picker is available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    /** Asks which already captured position to capture again. [chosen] receives it; nothing is selected otherwise. */
    fun replace(captured: Set<Int>, suggested: Int?, chosen: (Position) -> Unit) {
        val builder = AlertDialog.Builder(activity)
        val view = android.view.LayoutInflater.from(builder.context).inflate(R.layout.dialog_position, null)
        val id = view.findViewById<EditText>(R.id.positionIdInput)
        val error = view.findViewById<TextView>(R.id.positionError)
        view.findViewById<TextView>(R.id.positionHelp).text = activity.getString(R.string.replace_position_help) +
            if (captured.isEmpty()) "" else "\n\n" + activity.getString(R.string.replace_position_captured, captured.sorted().joinToString(", "))
        suggested?.let { id.setText(it.toString()) }
        val dialog = builder.setTitle(R.string.replace_position).setView(view)
            .setPositiveButton(R.string.replace_position_confirm, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = id.text.toString().trim().toIntOrNull()
                val problem = when {
                    number == null || number !in 0..999999 -> activity.getString(R.string.position_invalid)
                    number !in captured -> activity.getString(R.string.replace_position_missing, number)
                    else -> null
                }
                if (problem != null) {
                    error.text = problem
                    error.visibility = android.view.View.VISIBLE
                } else {
                    chosen(PositionCatalog.position(prefs, requireNotNull(number)))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun manual() {
        val builder = AlertDialog.Builder(activity)
        val view = android.view.LayoutInflater.from(builder.context).inflate(R.layout.dialog_position, null)
        val id = view.findViewById<EditText>(R.id.positionIdInput)
        val error = view.findViewById<TextView>(R.id.positionError)
        val current = ResearchSettings.position(prefs)
        if (ResearchSettings.hasPosition(prefs)) id.setText(current.id.toString())
        val dialog = builder.setTitle(R.string.choose_position).setView(view)
            .setPositiveButton(R.string.use_position, null)
            .setNeutralButton(R.string.import_positions) { _, _ -> importFile() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = id.text.toString().trim().toIntOrNull()
                if (number == null || number !in 0..999999) {
                    error.setText(R.string.position_invalid)
                    error.visibility = android.view.View.VISIBLE
                } else {
                    // Only the ID is typed. An imported survey still supplies its coordinates for that ID.
                    PositionCatalog.select(prefs, PositionCatalog.position(prefs, number))
                    changed(false)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
