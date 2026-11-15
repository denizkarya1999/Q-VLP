package com.developer27.qvlp

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.FixedCamera
import com.developer27.qvlp.camera.CameraCapabilities
import com.developer27.qvlp.camera.CameraProfile
import com.developer27.qvlp.camera.CameraSettingsStore
import com.developer27.qvlp.camera.CaptureSettings
import com.developer27.qvlp.capture.CaptureLengthPreferences

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        findViewById<android.widget.TextView>(R.id.settingsTitle).setText(R.string.settings)
        findViewById<android.widget.Button>(R.id.settingsDone).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    /** Nothing is restored until the operator confirms. The camera screen then reopens with the defaults. */
    internal fun confirmFactoryReset() {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.factory_reset_title)
            .setMessage(R.string.factory_reset_message)
            .setPositiveButton(R.string.factory_reset_confirm) { _, _ ->
                runCatching { FactoryReset.apply(applicationContext) }.fold(
                    onSuccess = {
                        Toast.makeText(this, R.string.factory_reset_done, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onFailure = { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() })
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_PREFERENCE_KEY = "preference_key"
        const val MODE_PREFERENCE = "app_mode"
        /** Data Collection is the default and, until inference exists, the only mode that can be chosen. */
        val MODE_KEYS = listOf("data_collection_mode", "inference_mode")
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            requireActivity().intent.getStringExtra(EXTRA_PREFERENCE_KEY)?.let { scrollToPreference(it) }
        }
        private lateinit var capabilities: CameraCapabilities
        private lateinit var store: CameraSettingsStore
        private lateinit var profile: CameraProfile
        private lateinit var shutterRate: ListPreference
        private lateinit var iso: EditTextPreference

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            // Bound first: the reset must work even when the camera's own settings cannot be shown.
            requireNotNull(findPreference<Preference>("factory_reset")).setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).confirmFactoryReset()
                true
            }
            bindModes()
            CaptureLengthPreferences.bind(this)
            shutterRate = requireNotNull(findPreference("shutter_rate"))
            iso = requireNotNull(findPreference("iso"))
            val status = requireNotNull(findPreference<Preference>("camera_status"))
            try {
                val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = requireNotNull(requireActivity().intent.getStringExtra(EXTRA_CAMERA_ID)) {
                    "No camera selected. Return to the camera screen and try again."
                }
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val fixed = FixedCamera.resolve(manager, id, prefs.getString(FixedCamera.preferenceKey(id), null))
                capabilities = fixed.capabilities
                store = CameraSettingsStore(PreferenceManager.getDefaultSharedPreferences(requireContext()), id)
                profile = store.load(capabilities)
                status.isVisible = false
            } catch (error: Exception) {
                status.isVisible = true
                status.title = getString(R.string.camera_settings_unavailable)
                status.summary = error.message ?: getString(R.string.camera_settings_unavailable)
                listOf(shutterRate, iso).forEach { it.isEnabled = false }
                return
            }

            iso.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.selectAll()
            }
            shutterRate.setOnPreferenceChangeListener { _, value ->
                // The exposure is one period of the rate. Only rates this camera supports are listed.
                (value as? String)?.toIntOrNull()?.takeIf { it in shutterRates() }
                    ?.let { save(profile.copy(exposureNs = CaptureSettings.exposureNanos(it))) }
                false // Persist the validated profile together, not the raw preference value.
            }
            iso.setOnPreferenceChangeListener { _, value ->
                val sensitivity = (value as? String)?.trim()?.toIntOrNull()
                if (sensitivity == null || !capabilities.isoRange.contains(sensitivity)) {
                    message(getString(R.string.iso_range, capabilities.isoRange.lower, capabilities.isoRange.upper))
                } else {
                    save(profile.copy(iso = sensitivity))
                }
                false
            }
            refresh()
        }

        /** The modes behave as radio buttons: choosing one clears the other, and the choice is remembered. */
        private fun bindModes() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val modes = MODE_KEYS.map { requireNotNull(findPreference<CheckBoxPreference>(it)) }
            fun show(selected: String) = modes.forEach { it.isChecked = it.key == selected }
            modes.forEach { mode ->
                mode.setOnPreferenceChangeListener { _, checked ->
                    // Tapping the selected mode leaves it selected; a mode is only left by choosing another.
                    if (checked == true) {
                        prefs.edit().putString(MODE_PREFERENCE, mode.key).apply()
                        show(mode.key)
                    }
                    false
                }
            }
            // A mode that cannot be chosen yet is never restored as the selection.
            show(modes.firstOrNull { it.isEnabled && it.key == prefs.getString(MODE_PREFERENCE, null) }?.key ?: MODE_KEYS.first())
        }

        private fun save(value: CameraProfile) {
            profile = value
            store.save(value)
            refresh()
        }

        private fun shutterRates(): List<Int> = CaptureSettings.shutterRates(
            capabilities.exposureRangeNs.lower, capabilities.maximumExposureNs(profile.stream, profile.fps))

        private fun refresh() {
            val rates = shutterRates()
            val rate = CaptureSettings.shutterRateHz(profile.exposureNs)
            shutterRate.entries = rates.map { getString(R.string.shutter_rate_value, it) }.toTypedArray()
            shutterRate.entryValues = rates.map { it.toString() }.toTypedArray()
            // An exposure saved before this setting existed may fall between two listed rates.
            shutterRate.value = rates.firstOrNull { CaptureSettings.exposureNanos(it) == profile.exposureNs }?.toString()
            shutterRate.summary = getString(R.string.shutter_rate_value, rate)
            iso.text = profile.iso.toString()
            iso.summary = getString(R.string.iso_summary, profile.iso)
            iso.dialogMessage = getString(R.string.iso_range, capabilities.isoRange.lower, capabilities.isoRange.upper)
        }

        private fun message(text: String) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }
    }
}
