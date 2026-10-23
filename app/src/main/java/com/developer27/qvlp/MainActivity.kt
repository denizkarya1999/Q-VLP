package com.developer27.qvlp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.CameraHelper
import com.developer27.qvlp.capture.CaptureLength
import com.developer27.qvlp.capture.CaptureLengthMode
import com.developer27.qvlp.capture.CaptureLengthPreferences
import com.developer27.qvlp.capture.BurstSummary
import com.developer27.qvlp.capture.Position
import com.developer27.qvlp.capture.PositionCatalog
import com.developer27.qvlp.capture.PositionPicker
import com.developer27.qvlp.capture.ResearchSettings
import com.developer27.qvlp.capture.SessionBrowser
import com.developer27.qvlp.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences
    private lateinit var cameraHelper: CameraHelper
    private lateinit var positions: PositionPicker
    private lateinit var sessions: SessionBrowser
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private var captureBusy = false
    private var fileBusy = false
    private var cameraReady = false
    private var cameraDetails = ""
    private var currentZoom = 1f
    private var maximumZoom = 1f
    private var countdown: CountDownTimer? = null
    private var orientationBeforeCapture = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastPosition: Position? = null
    private var resultDetails: String? = null

    private val textureListener = object : TextureView.SurfaceTextureListener {
        @SuppressLint("MissingPermission")
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (cameraPermissionGranted()) cameraHelper.openCamera()
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            cameraHelper.fitPreview()
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        lastPosition = runCatching {
            val saved = JSONObject(requireNotNull(preferences.getString("last_capture_position", null)))
            Position(saved.getInt("position_id"), if (saved.isNull("rx_x_m")) null else saved.getDouble("rx_x_m"),
                if (saved.isNull("rx_y_m")) null else saved.getDouble("rx_y_m"), saved.optString("source", "manual"))
        }.getOrNull()
        sessions = SessionBrowser(this, ::setFileBusy) {
            clearResult()
            lastPosition = null
            preferences.edit().remove("last_capture_position").apply()
            val first = PositionCatalog.read(preferences).firstOrNull() ?: Position(0, null, null)
            PositionCatalog.select(preferences, first)
            updateControls()
        }
        sessions.restore(savedInstanceState)
        positions = PositionPicker(this, preferences, ::setFileBusy) { imported ->
            if (imported) lastPosition = null
            clearResult()
            updateControls()
        }
        cameraHelper = CameraHelper(this, viewBinding, preferences)
        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCameraWhenReady() else {
                cameraReady = false
                viewBinding.captureStatus.setText(R.string.camera_permission_help)
                updateControls()
            }
        }
        viewBinding.titleContainer.setOnClickListener { openProjectWebsite() }
        viewBinding.settingsButton.setOnClickListener { openSettings() }
        viewBinding.switchCameraButton.setOnClickListener {
            if (!captureBusy && !fileBusy) {
                if (cameraPermissionGranted()) cameraHelper.switchCamera()
                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        viewBinding.zoomInButton.setOnClickListener { if (!captureBusy && !fileBusy) cameraHelper.changeZoom(0.1f) }
        viewBinding.zoomOutButton.setOnClickListener { if (!captureBusy && !fileBusy) cameraHelper.changeZoom(-0.1f) }
        viewBinding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutQvlpActivity::class.java))
        }
        viewBinding.sessionsButton.setOnClickListener { sessions.show() }
        viewBinding.positionButton.setOnClickListener { positions.show() }
        viewBinding.captureStatus.setOnClickListener {
            if (!cameraPermissionGranted()) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            else AlertDialog.Builder(this).setTitle(R.string.camera_details)
                .setMessage(cameraDetails.ifBlank { getString(R.string.camera_starting) })
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.settings) { _, _ -> openSettings() }.show()
        }
        viewBinding.startCaptureButton.setOnClickListener {
            if (captureBusy) cancelFrameCapture() else {
                val position = ResearchSettings.position(preferences)
                if (position.x == null || position.y == null) positions.show() else beginCountdown(position)
            }
        }
        viewBinding.repeatCaptureButton.setOnClickListener {
            lastPosition?.let { PositionCatalog.select(preferences, it); beginCountdown(it) }
        }
        viewBinding.nextPositionButton.setOnClickListener {
            PositionCatalog.next(preferences)?.let {
                PositionCatalog.select(preferences, it)
                clearResult()
                viewBinding.burstStatus.setText(R.string.next_position_ready)
                updateControls()
                if (it.x == null || it.y == null) positions.show()
            }
        }
        viewBinding.burstStatus.setOnClickListener {
            resultDetails?.let {
                AlertDialog.Builder(this).setTitle(R.string.capture_details).setMessage(it)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
        resultDetails = savedInstanceState?.getString("result_details")
        savedInstanceState?.getString("result_text")?.let { viewBinding.burstStatus.text = it }
        updateControls()
    }

    override fun onResume() {
        super.onResume()
        if (!captureBusy && resultDetails == null) showCapturePlan()
        cameraHelper.fitPreview()
        cameraHelper.startBackgroundThread()
        openCameraWhenReady()
        updateControls()
        if (!captureBusy && !fileBusy) sessions.resumeSaving()
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWhenReady() {
        if (viewBinding.viewFinder.isAvailable) {
            if (cameraPermissionGranted()) cameraHelper.openCamera()
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else viewBinding.viewFinder.surfaceTextureListener = textureListener
    }

    override fun onPause() {
        cameraReady = false
        if (captureBusy) cancelFrameCapture("Capture interrupted while the app was not active.", restorePreview = false)
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessions.save(outState)
        outState.putString("result_details", resultDetails)
        outState.putString("result_text", viewBinding.burstStatus.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun openSettings() {
        val cameraId = runCatching { cameraHelper.getCameraId() }.getOrNull()
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_CAMERA_ID, cameraId))
    }

    /** The technical measurements stay available without filling the capture screen. */
    internal fun showCameraState(ready: Boolean, details: String, error: Boolean = false) {
        if (isDestroyed) return
        cameraReady = ready
        cameraDetails = details
        viewBinding.captureStatus.setText(when {
            error -> R.string.camera_attention
            ready -> R.string.camera_ready
            else -> R.string.camera_preparing
        })
        updateControls()
    }

    internal fun showZoomState(level: Float, maximum: Float) {
        if (isDestroyed) return
        currentZoom = level
        maximumZoom = maximum
        viewBinding.zoomLevel.text = getString(R.string.zoom_value, level.toDouble())
        updateControls()
    }

    private fun setFileBusy(busy: Boolean) {
        fileBusy = busy
        updateControls()
    }

    private fun beginCountdown(position: Position) {
        if (captureBusy || fileBusy) return
        if (!sessions.readyForCapture()) return
        if (position.x == null || position.y == null) { positions.show(); return }
        if (!cameraPermissionGranted()) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA); return }
        cameraHelper.recordingBlockReason()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        val length = CaptureLengthPreferences.read(preferences)
        clearResult()
        captureBusy = true
        orientationBeforeCapture = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        lastPosition = position
        preferences.edit().putString("last_capture_position", position.json().toString()).apply()
        viewBinding.captureProgress.max = length.frameCount ?: (requireNotNull(length.durationSeconds) * 1000)
        viewBinding.captureProgress.progress = 0
        updateControls()
        countdown = object : CountDownTimer(CaptureLength.COUNTDOWN_SECONDS * 1000L, 1000L) {
            override fun onTick(remaining: Long) {
                viewBinding.burstStatus.text = getString(R.string.capture_countdown, (remaining + 999L) / 1000L)
            }
            override fun onFinish() {
                countdown = null
                viewBinding.burstStatus.setText(R.string.capture_starting)
                val key = cameraHelper.activeProfile?.let { cameraHelper.getCameraId() to it.stream.key }
                cameraHelper.startBurst(position, ResearchSettings.mounting(preferences, key), length,
                    progress = { count, elapsedMs -> runOnUiThread {
                        if (!isDestroyed && captureBusy) {
                            viewBinding.burstStatus.text = if (length.mode == CaptureLengthMode.FRAMES)
                                getString(R.string.capture_progress, count, length.value)
                            else getString(R.string.capture_duration_progress, count, elapsedMs / 1000.0, length.value)
                            viewBinding.captureProgress.progress = if (length.mode == CaptureLengthMode.FRAMES) count
                                else elapsedMs.toInt().coerceAtMost(viewBinding.captureProgress.max)
                        }
                    } },
                    publishing = { runOnUiThread { if (!isDestroyed) {
                        fileBusy = true
                        viewBinding.burstStatus.text = "Saving to Pictures/Q-VLP…"
                        updateControls()
                    } } },
                    completed = { summary -> runOnUiThread { if (!isDestroyed) finishFrameCapture(summary) } },
                    failed = { error -> runOnUiThread {
                        if (!isDestroyed) {
                            captureBusy = false
                            requestedOrientation = orientationBeforeCapture
                            viewBinding.burstStatus.text = error
                            updateControls()
                        }
                    } })
            }
        }.start()
    }

    private fun cancelFrameCapture(reason: String = "Capture cancelled by the operator.", restorePreview: Boolean = true) {
        val timer = countdown
        if (timer != null) {
            timer.cancel()
            countdown = null
            captureBusy = false
            requestedOrientation = orientationBeforeCapture
            viewBinding.burstStatus.setText(R.string.countdown_cancelled)
            updateControls()
        } else {
            viewBinding.burstStatus.setText(R.string.saving_partial_capture)
            cameraHelper.cancelBurst(reason, restorePreview)
        }
    }

    private fun finishFrameCapture(summary: BurstSummary) {
        captureBusy = false
        fileBusy = false
        requestedOrientation = orientationBeforeCapture
        val fps = summary.fps?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "—"
        val savedDescription = if (summary.length.mode == CaptureLengthMode.FRAMES)
            getString(R.string.capture_saved, summary.saved, summary.length.value)
        else getString(R.string.capture_duration_saved, summary.saved, summary.length.value)
        val description = getString(R.string.capture_summary, summary.positionId, savedDescription, fps)
        resultDetails = description + "\n\n" + (if (summary.warnings.isEmpty()) getString(R.string.capture_no_warnings)
            else summary.warnings.joinToString("\n\n"))
        val storageMessage = summary.publicSaveError?.let {
            "Could not save to Pictures/Q-VLP: $it\nYour capture is safe in the app. Open Sessions → Storage → Save now to retry."
        } ?: "Saved to Pictures/Q-VLP/${summary.sessionId}"
        resultDetails += "\n\n$storageMessage"
        viewBinding.burstStatus.text = savedDescription + "\n" + when {
            summary.interrupted -> getString(R.string.capture_interrupted_short)
            summary.warnings.isNotEmpty() -> getString(R.string.capture_warning_short, summary.warnings.size)
            else -> getString(R.string.capture_success_short)
        }
        viewBinding.burstStatus.append("\n" + getString(
            if (summary.warnings.isEmpty()) R.string.tap_capture_details else R.string.tap_capture_warnings))
        viewBinding.burstStatus.append(if (summary.publicSaveError == null) "\nSaved to Pictures/Q-VLP"
            else "\nFolder save failed · Open Sessions → Storage")
        updateControls()
        if (!summary.interrupted) {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
                Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
            }
            runCatching {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun clearResult() {
        resultDetails = null
        showCapturePlan()
    }

    private fun showCapturePlan() {
        val length = CaptureLengthPreferences.read(preferences)
        val label = resources.getQuantityString(if (length.mode == CaptureLengthMode.FRAMES) R.plurals.capture_length_frames
            else R.plurals.capture_length_seconds, length.value, length.value)
        viewBinding.burstStatus.text = getString(R.string.capture_workflow, CaptureLength.COUNTDOWN_SECONDS, label)
    }

    private fun updateControls() {
        val busy = captureBusy || fileBusy
        viewBinding.titleContainer.isEnabled = !busy
        viewBinding.settingsButton.isEnabled = !busy
        viewBinding.switchCameraButton.isEnabled = !busy
        viewBinding.zoomInButton.isEnabled = !busy && cameraReady && currentZoom < maximumZoom - 0.001f
        viewBinding.zoomOutButton.isEnabled = !busy && cameraReady && currentZoom > 1.001f
        viewBinding.aboutButton.isEnabled = !busy
        viewBinding.sessionsButton.isEnabled = !busy
        viewBinding.positionButton.isEnabled = !busy
        viewBinding.captureStatus.isEnabled = !busy
        val position = ResearchSettings.position(preferences)
        val hasPosition = position.x != null && position.y != null
        viewBinding.positionButton.text = if (hasPosition) getString(R.string.position_button, position.id)
            else getString(R.string.choose_position)
        viewBinding.positionCoordinates.text = if (hasPosition)
            getString(R.string.position_coordinates, position.x.toString(), position.y.toString())
            else getString(R.string.position_required)
        val hasResult = resultDetails != null
        viewBinding.startCaptureButton.visibility = if (hasResult && !busy) View.GONE else View.VISIBLE
        viewBinding.startCaptureButton.setText(when {
            fileBusy -> R.string.files_working
            captureBusy -> R.string.cancel_capture
            !cameraReady -> R.string.camera_wait_button
            else -> R.string.start_capture
        })
        viewBinding.startCaptureButton.isEnabled = !fileBusy && (captureBusy || (hasPosition && cameraReady))
        viewBinding.resultActions.visibility = if (hasResult && !busy) View.VISIBLE else View.GONE
        viewBinding.burstStatus.isClickable = hasResult && !busy
        viewBinding.burstStatus.isFocusable = hasResult && !busy
        viewBinding.repeatCaptureButton.isEnabled = !busy && cameraReady && lastPosition != null
        val hasNext = if (hasResult && !busy) PositionCatalog.next(preferences) != null else false
        viewBinding.nextPositionButton.isEnabled = !busy && hasNext
        viewBinding.nextPositionButton.setText(if (hasNext) R.string.next_position else R.string.last_position)
        viewBinding.captureProgress.visibility = if (captureBusy) View.VISIBLE else View.GONE
    }

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
