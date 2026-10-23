package com.developer27.qvlp

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
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
import com.developer27.qvlp.camera.FocusMode
import com.developer27.qvlp.camera.FocusWhiteBalanceCapabilities
import com.developer27.qvlp.camera.PreviewDisplaySize
import com.developer27.qvlp.capture.ResearchSettings

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        findViewById<android.widget.TextView>(R.id.settingsTitle).setText(
            if (intent.getBooleanExtra(EXTRA_MOUNTING, false)) R.string.mounting_setup else R.string.settings)
        findViewById<android.widget.Button>(R.id.settingsDone).setOnClickListener { finish() }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment()).commit()
        }
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_PREFERENCE_KEY = "preference_key"
        const val EXTRA_MOUNTING = "mounting"
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            requireActivity().intent.getStringExtra(EXTRA_PREFERENCE_KEY)?.let { scrollToPreference(it) }
        }
        private lateinit var capabilities: CameraCapabilities
        private lateinit var store: CameraSettingsStore
        private lateinit var profile: CameraProfile
        private lateinit var exposure: EditTextPreference
        private lateinit var iso: EditTextPreference
        private lateinit var resolution: ListPreference
        private lateinit var fps: ListPreference
        private lateinit var focus: ListPreference
        private lateinit var focusDistance: EditTextPreference
        private lateinit var whiteBalance: ListPreference
        private lateinit var previewDisplaySize: ListPreference

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val mounting = requireActivity().intent.getBooleanExtra(EXTRA_MOUNTING, false)
            setPreferencesFromResource(if (mounting) R.xml.mount_preferences else R.xml.root_preferences, rootKey)
            if (mounting) {
                runCatching {
                    val manager = requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = requireNotNull(requireActivity().intent.getStringExtra(EXTRA_CAMERA_ID))
                    capabilities = CameraCapabilities.read(id, manager.getCameraCharacteristics(id))
                    store = CameraSettingsStore(PreferenceManager.getDefaultSharedPreferences(requireContext()), id)
                    profile = store.load(capabilities)
                }
                ResearchSettings.bind(this) {
                    if (::capabilities.isInitialized && ::profile.isInitialized) capabilities.cameraId to profile.stream.key else null
                }
                if (::capabilities.isInitialized && ::profile.isInitialized)
                    ResearchSettings.refreshRowTime(this, capabilities.cameraId to profile.stream.key)
                return
            }
            previewDisplaySize = requireNotNull(findPreference(PreviewDisplaySize.PREFERENCE_KEY))
            previewDisplaySize.entries = PreviewDisplaySize.values().map { getString(it.label) }.toTypedArray()
            previewDisplaySize.entryValues = PreviewDisplaySize.values().map { it.key }.toTypedArray()
            previewDisplaySize.value = PreviewDisplaySize.read(
                PreferenceManager.getDefaultSharedPreferences(requireContext())).key
            previewDisplaySize.summaryProvider = Preference.SummaryProvider<ListPreference> {
                getString(R.string.preview_size_summary, it.entry)
            }
            previewDisplaySize.setOnPreferenceChangeListener { _, value ->
                PreviewDisplaySize.values().any { it.key == value }
            }
            findPreference<Preference>("capture_help")?.setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.how_to_capture).setMessage(R.string.capture_help)
                    .setPositiveButton(android.R.string.ok, null).show()
                true
            }
            findPreference<Preference>("mounting_setup")?.setOnPreferenceClickListener {
                startActivity(android.content.Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(EXTRA_CAMERA_ID, requireActivity().intent.getStringExtra(EXTRA_CAMERA_ID))
                    .putExtra(EXTRA_MOUNTING, true))
                true
            }
            exposure = requireNotNull(findPreference("exposure_us"))
            iso = requireNotNull(findPreference("iso"))
            resolution = requireNotNull(findPreference("resolution"))
            fps = requireNotNull(findPreference("frame_rate"))
            focus = requireNotNull(findPreference("focus_mode"))
            focusDistance = requireNotNull(findPreference("focus_distance"))
            whiteBalance = requireNotNull(findPreference("white_balance_mode"))
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
                status.isVisible = false
            } catch (error: Exception) {
                status.isVisible = true
                status.title = getString(R.string.camera_settings_unavailable)
                status.summary = error.message ?: getString(R.string.camera_settings_unavailable)
                listOf(exposure, iso, resolution, fps, focus, focusDistance, whiteBalance, reset)
                    .forEach { it.isEnabled = false }
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
            focusDistance.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                it.selectAll()
            }
            focus.setOnPreferenceChangeListener { _, value ->
                capabilities.controls.focusModes.firstOrNull { it.name == value }?.let { mode ->
                    save(profile.copy(controls = profile.controls.copy(focusMode = mode)))
                }
                false
            }
            focusDistance.setOnPreferenceChangeListener { _, value ->
                val distance = (value as? String)?.trim()?.replace(',', '.')?.toFloatOrNull()
                if (distance == null || !distance.isFinite() || distance !in 0f..capabilities.controls.maxFocusDistance) {
                    message(focusRangeMessage())
                } else if (profile.controls.focusMode == FocusMode.MANUAL && capabilities.controls.manualFocus) {
                    save(profile.copy(controls = profile.controls.copy(focusDistance = distance)))
                }
                false
            }
            whiteBalance.setOnPreferenceChangeListener { _, value ->
                val mode = (value as? String)?.toIntOrNull()
                if (capabilities.controls.whiteBalanceOptions.any { it.mode == mode }) {
                    save(profile.copy(controls = profile.controls.copy(whiteBalanceMode = requireNotNull(mode))))
                }
                false
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
                previewDisplaySize.value = PreviewDisplaySize.ORIGINAL.key
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
            exposure.summary = getString(R.string.exposure_summary, exposure.text)
            exposure.dialogMessage = exposureRangeMessage()
            iso.text = profile.iso.toString()
            iso.summary = getString(R.string.iso_summary, profile.iso)
            iso.dialogMessage = getString(R.string.iso_range, capabilities.isoRange.lower, capabilities.isoRange.upper)
            resolution.entries = capabilities.streams.map { it.label }.toTypedArray()
            resolution.entryValues = capabilities.streams.map { it.key }.toTypedArray()
            resolution.value = profile.stream.key
            resolution.summary = getString(R.string.resolution_summary, profile.stream.label)
            fps.entries = profile.stream.frameRates.map { getString(R.string.fps_value, it) }.toTypedArray()
            fps.entryValues = profile.stream.frameRates.map { it.toString() }.toTypedArray()
            fps.value = profile.fps.toString()
            fps.summary = getString(R.string.fps_summary, profile.fps)
            refreshFocusAndWhiteBalance()
            ResearchSettings.refreshRowTime(this, capabilities.cameraId to profile.stream.key)
        }

        private fun focusRangeMessage(): String = getString(
            if (capabilities.controls.focusCalibrated) R.string.focus_distance_range else R.string.focus_position_range,
            capabilities.controls.maxFocusDistance.toBigDecimal().stripTrailingZeros().toPlainString()
        )

        private fun refreshFocusAndWhiteBalance() {
            val controls = capabilities.controls
            val selected = profile.controls
            focus.entries = controls.focusModes.map { getString(it.label) }.toTypedArray()
            focus.entryValues = controls.focusModes.map { it.name }.toTypedArray()
            focus.value = selected.focusMode.name
            focus.isEnabled = controls.focusModes.size > 1
            focus.summary = when (selected.focusMode) {
                FocusMode.LOCK -> getString(R.string.focus_lock_summary)
                FocusMode.AUTO -> getString(R.string.focus_auto_summary)
                FocusMode.MANUAL -> getString(R.string.focus_manual_summary)
                FocusMode.FIXED -> getString(R.string.focus_fixed_summary)
                FocusMode.DEVICE -> getString(R.string.focus_unavailable_summary)
            }
            if (!controls.focusLock && !controls.fixedFocus) {
                focus.summary = "${focus.summary}\n${getString(R.string.focus_lock_unavailable)}"
            }
            focusDistance.isVisible = controls.manualFocus && selected.focusMode == FocusMode.MANUAL
            focusDistance.isEnabled = selected.focusMode == FocusMode.MANUAL
            focusDistance.title = getString(if (controls.focusCalibrated) R.string.focus_distance else R.string.focus_position)
            focusDistance.dialogTitle = focusDistance.title
            focusDistance.text = selected.focusDistance.toBigDecimal().stripTrailingZeros().toPlainString()
            focusDistance.dialogMessage = focusRangeMessage()
            focusDistance.summary = if (focusDistance.isEnabled)
                focusDistance.text else getString(R.string.focus_distance_disabled)
            whiteBalance.entries = controls.whiteBalanceOptions.map { getString(it.label) }.toTypedArray()
            whiteBalance.entryValues = controls.whiteBalanceOptions.map { it.mode.toString() }.toTypedArray()
            whiteBalance.value = selected.whiteBalanceMode.toString()
            whiteBalance.isEnabled = controls.whiteBalanceOptions.size > 1
            whiteBalance.summary = when (selected.whiteBalanceMode) {
                FocusWhiteBalanceCapabilities.WB_LOCK -> getString(R.string.wb_lock_summary)
                FocusWhiteBalanceCapabilities.WB_DEVICE -> getString(R.string.wb_unavailable_summary)
                android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO -> getString(R.string.wb_auto_summary)
                else -> getString(R.string.wb_preset_summary, whiteBalance.entry)
            }
            if (!controls.whiteBalanceLock) {
                whiteBalance.summary = "${whiteBalance.summary}\n${getString(R.string.wb_lock_unavailable)}"
            }
        }

        private fun message(text: String) {
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }
    }
}
