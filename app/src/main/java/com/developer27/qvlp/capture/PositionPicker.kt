package com.developer27.qvlp.capture

import android.content.SharedPreferences
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.developer27.qvlp.R

/** The two dialogs that take a position ID. A position is only ever typed; there is no list to import. */
internal class PositionPicker(private val activity: AppCompatActivity, private val prefs: SharedPreferences,
                              private val changed: () -> Unit) {

    /** Asks for the ID of the position to capture. */
    fun show() {
        val current = ResearchSettings.position(prefs).id.takeIf { ResearchSettings.hasPosition(prefs) }
        ask(R.string.choose_position, R.string.use_position, activity.getString(R.string.position_entry_help), current, { null }) {
            ResearchSettings.select(prefs, it)
            changed()
        }
    }

    /** Asks which already captured position to capture again. [chosen] receives it; nothing is selected otherwise. */
    fun replace(captured: Set<Int>, suggested: Int?, chosen: (Position) -> Unit) {
        val help = activity.getString(R.string.replace_position_help) +
            if (captured.isEmpty()) "" else "\n\n" + activity.getString(R.string.replace_position_captured, captured.sorted().joinToString(", "))
        ask(R.string.replace_position, R.string.replace_position_confirm, help, suggested,
            { id -> activity.getString(R.string.replace_position_missing, id).takeIf { id !in captured } }, chosen)
    }

    /** One ID field. [problem] names what is wrong with an otherwise valid ID, or returns null to accept it. */
    private fun ask(title: Int, confirm: Int, help: String, initial: Int?, problem: (Int) -> String?, chosen: (Position) -> Unit) {
        val builder = AlertDialog.Builder(activity)
        val view = android.view.LayoutInflater.from(builder.context).inflate(R.layout.dialog_position, null)
        val id = view.findViewById<EditText>(R.id.positionIdInput)
        val error = view.findViewById<TextView>(R.id.positionError)
        view.findViewById<TextView>(R.id.positionHelp).text = help
        initial?.let { id.setText(it.toString()) }
        val dialog = builder.setTitle(title).setView(view).setPositiveButton(confirm, null)
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val number = id.text.toString().trim().toIntOrNull()?.takeIf { it in 0..ResearchSettings.MAX_POSITION_ID }
                val message = if (number == null) activity.getString(R.string.position_invalid) else problem(number)
                if (number == null || message != null) {
                    error.text = message
                    error.visibility = android.view.View.VISIBLE
                } else {
                    chosen(Position(number))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
