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
                PositionCatalog.import(input, prefs)
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

    private fun manual() {
        val builder = AlertDialog.Builder(activity)
        val view = android.view.LayoutInflater.from(builder.context).inflate(R.layout.dialog_position, null)
        val id = view.findViewById<EditText>(R.id.positionIdInput)
        val x = view.findViewById<EditText>(R.id.positionXInput)
        val y = view.findViewById<EditText>(R.id.positionYInput)
        val error = view.findViewById<TextView>(R.id.positionError)
        val current = ResearchSettings.position(prefs)
        id.setText(current.id.toString())
        x.setText(current.x?.toString().orEmpty())
        y.setText(current.y?.toString().orEmpty())
        val dialog = builder.setTitle(R.string.choose_position).setView(view)
            .setPositiveButton(R.string.use_position, null)
            .setNeutralButton(R.string.import_positions) { _, _ -> importFile() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = id.text.toString().trim().toIntOrNull()
                val px = x.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val py = y.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (number == null || number !in 0..999999 || px == null || py == null || !px.isFinite() || !py.isFinite()) {
                    error.setText(R.string.position_invalid)
                    error.visibility = android.view.View.VISIBLE
                } else {
                    PositionCatalog.select(prefs, Position(number, px, py))
                    changed(false)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
