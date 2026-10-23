package com.developer27.qvlp

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.CameraCapabilities
import com.developer27.qvlp.camera.CameraProfile
import com.developer27.qvlp.camera.CameraSettingsStore
import com.developer27.qvlp.camera.CaptureSettings

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var capabilities: CameraCapabilities
        private lateinit var store: CameraSettingsStore
        private lateinit var profile: CameraProfile
        private lateinit var exposure: EditTextPreference
        private lateinit var iso: EditTextPreference
        private lateinit var resolution: ListPreference
        private lateinit var fps: ListPreference

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            exposure = requireNotNull(findPreference("exposure_us"))
            iso = requireNotNull(findPreference("iso"))
            resolution = requireNotNull(findPreference("resolution"))
            fps = requireNotNull(findPreference("frame_rate"))
            val status = requireNotNull(findPreference<Preference>("camera_status"))
            val reset = requireNotNull(findPreference<Preference>("reset_camera"))
            try {
                val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = requireNotNull(requireActivity().intent.getStringExtra(EXTRA_CAMERA_ID)) {
                    "No camera selected. Return to the camera screen and try again."
                }
                capabilities = CameraCapabilities.read(id, manager.getCameraCharacteristics(id))
                store = CameraSettingsStore(PreferenceManager.getDefaultSharedPreferences(requireContext()), id)
                profile = store.load(capabilities)
                status.title = getString(R.string.selected_camera, id)
                status.summary = getString(R.string.manual_camera_summary)
            } catch (error: Exception) {
                status.title = getString(R.string.camera_settings_unavailable)
                status.summary = error.message ?: getString(R.string.camera_settings_unavailable)
                listOf(exposure, iso, resolution, fps, reset).forEach { it.isEnabled = false }
                return
            }

            exposure.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                it.selectAll()
            }
            iso.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.selectAll()
            }
            exposure.setOnPreferenceChangeListener { _, value ->
                val nanos = CaptureSettings.parseExposureMicros(value as? String)
                val max = capabilities.maximumExposureNs(profile.stream, profile.fps)
                if (nanos == null || nanos !in capabilities.exposureRangeNs.lower..max) {
                    message(exposureRangeMessage())
                } else {
                    save(profile.copy(exposureNs = nanos))
                }
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
            resolution.setOnPreferenceChangeListener { _, value ->
                val stream = capabilities.streams.firstOrNull { it.key == value }
                if (stream != null) {
                    val rate = profile.fps.takeIf { it in stream.frameRates }
                        ?: capabilities.defaultFrameRate(stream)
                    val maxExposure = capabilities.maximumExposureNs(stream, rate)
                    if (profile.exposureNs > maxExposure) {
                        message(getString(R.string.shorten_exposure, CaptureSettings.exposureMicros(maxExposure)))
                    } else {
                        val rateChanged = rate != profile.fps
                        save(profile.copy(stream = stream, fps = rate))
                        if (rateChanged) message(getString(R.string.fps_adjusted, rate))
                    }
                }
                false
            }
            fps.setOnPreferenceChangeListener { _, value ->
                val rate = (value as? String)?.toIntOrNull()
                if (rate != null && rate in profile.stream.frameRates) {
                    val maxExposure = capabilities.maximumExposureNs(profile.stream, rate)
                    if (profile.exposureNs > maxExposure) {
                        message(getString(R.string.shorten_exposure, CaptureSettings.exposureMicros(maxExposure)))
                    } else {
                        save(profile.copy(fps = rate))
                    }
                }
                false
            }
            reset.setOnPreferenceClickListener {
                save(capabilities.defaults())
                message(getString(R.string.camera_defaults_restored))
                true
            }
            refresh()
        }

        private fun save(value: CameraProfile) {
            profile = value
            store.save(value)
            refresh()
        }

        private fun exposureRangeMessage(): String = getString(
            R.string.exposure_range,
            CaptureSettings.exposureMicros(capabilities.exposureRangeNs.lower),
            CaptureSettings.exposureMicros(capabilities.maximumExposureNs(profile.stream, profile.fps)),
            profile.fps
        )

        private fun refresh() {
            exposure.text = CaptureSettings.exposureMicros(profile.exposureNs)
            exposure.summary = getString(R.string.exposure_summary, exposure.text, exposureRangeMessage())
            exposure.dialogMessage = exposureRangeMessage()
            iso.text = profile.iso.toString()
            iso.summary = getString(R.string.iso_summary, profile.iso,
                capabilities.isoRange.lower, capabilities.isoRange.upper)
            iso.dialogMessage = getString(R.string.iso_range, capabilities.isoRange.lower, capabilities.isoRange.upper)
            resolution.entries = capabilities.streams.map { it.label }.toTypedArray()
            resolution.entryValues = capabilities.streams.map { it.key }.toTypedArray()
            resolution.value = profile.stream.key
            resolution.summary = getString(R.string.resolution_summary, profile.stream.label)
            fps.entries = profile.stream.frameRates.map { getString(R.string.fps_value, it) }.toTypedArray()
            fps.entryValues = profile.stream.frameRates.map { it.toString() }.toTypedArray()
            fps.value = profile.fps.toString()
            fps.summary = getString(R.string.fps_summary, profile.fps)
        }

        private fun message(text: String) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }
    }
}
