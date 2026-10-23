package com.developer27.qvlp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.CameraHelper
import com.developer27.qvlp.camera.RecorderHelper
import com.developer27.qvlp.databinding.ActivityMainBinding
import com.developer27.qvlp.capture.BurstSummary
import com.developer27.qvlp.capture.Position
import com.developer27.qvlp.capture.ResearchSettings
import com.developer27.qvlp.capture.SessionBrowser
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var cameraHelper: CameraHelper

    // RecorderHelper instance and flag to track recording state.
    private lateinit var recorderHelper: RecorderHelper
    private var isRecordingVideo = false
    private var isStartingVideo = false
    private var videoGeneration = 0
    private var captureBusy = false
    private var exportBusy = false
    private var visible = false
    private var countdown: CountDownTimer? = null
    private var orientationBeforeCapture = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastPosition: Position? = null
    private lateinit var sessions: SessionBrowser

    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var recordingPermissionLauncher: ActivityResultLauncher<Array<String>>
    private val textureListener = object : TextureView.SurfaceTextureListener {
        @SuppressLint("MissingPermission")
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            if (cameraPermissionGranted()) {
                cameraHelper.openCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Prevent screen from turning off
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Install the splash screen (Android 12+)
        installSplashScreen()

        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        lastPosition = runCatching {
            val saved = JSONObject(requireNotNull(sharedPreferences.getString("last_capture_position", null)))
            Position(saved.getInt("position_id"), if (saved.isNull("rx_x_m")) null else saved.getDouble("rx_x_m"),
                if (saved.isNull("rx_y_m")) null else saved.getDouble("rx_y_m"))
        }.getOrNull()
        sessions = SessionBrowser(this, { busy -> exportBusy = busy; updateRecordingButton() }) {
            sharedPreferences.edit().putString("position_id", "0").remove("rx_x_m").remove("rx_y_m").apply()
            updateCaptureControls()
        }
        sessions.restore(savedInstanceState)

        cameraHelper = CameraHelper(this, viewBinding, sharedPreferences)

        // Initialize MP4 video recording.
        recorderHelper = RecorderHelper(this, cameraHelper, viewBinding)

        cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    openCameraWhenReady()
                } else {
                    Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show()
                }
            }

        recordingPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                if (permissions.values.all { it }) {
                    startVideoRecording()
                } else {
                    Toast.makeText(
                        this,
                        "Recording permission is required to save video.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        // Set up the Capture Video button to trigger recording functions.
        viewBinding.startRecordingButton.setOnClickListener {
            if (captureBusy || exportBusy) return@setOnClickListener
            if (isRecordingVideo || isStartingVideo) {
                stopVideoRecording()
            } else {
                val missingPermissions = recordingPermissions().filterNot(::permissionGranted)
                if (missingPermissions.isEmpty()) {
                    startVideoRecording()
                } else {
                    recordingPermissionLauncher.launch(missingPermissions.toTypedArray())
                }
            }
        }

        // Set up the Switch Camera, About, and Settings buttons.
        viewBinding.titleContainer.setOnClickListener { openProjectWebsite() }
        viewBinding.switchCameraButton.setOnClickListener { switchCamera() }
        viewBinding.aboutButton.setOnClickListener {
            startActivity(Intent(this, AboutQvlpActivity::class.java))
        }
        viewBinding.settingsButton.setOnClickListener {
            openSettings()
        }
        viewBinding.positionButton.setOnClickListener { openSettings("position_id") }
        viewBinding.sessionsButton.setOnClickListener { sessions.show() }
        viewBinding.startCaptureButton.setOnClickListener {
            if (captureBusy) cancelFrameCapture() else beginCountdown(ResearchSettings.position(sharedPreferences))
        }
        viewBinding.repeatCaptureButton.setOnClickListener {
            lastPosition?.let { position ->
                sharedPreferences.edit().putString("position_id", position.id.toString())
                    .putString("rx_x_m", position.x?.toString().orEmpty())
                    .putString("rx_y_m", position.y?.toString().orEmpty()).apply()
                beginCountdown(position)
            }
        }
        viewBinding.nextPositionButton.setOnClickListener {
            ResearchSettings.nextPosition(sharedPreferences)
            viewBinding.burstStatus.text = getString(R.string.next_position_ready)
            updateCaptureControls()
        }

        cameraHelper.setupZoomControls()
        updateRecordingButton()
    }

    private var isFrontCamera = false
    @SuppressLint("MissingPermission")
    private fun switchCamera() {
        if (isRecordingVideo || isStartingVideo) {
            stopVideoRecording(restorePreview = false)
        }
        isFrontCamera = !isFrontCamera
        cameraHelper.isFrontCamera = isFrontCamera
        cameraHelper.closeCamera()
        if (cameraPermissionGranted()) {
            cameraHelper.openCamera()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        visible = true
        cameraHelper.startBackgroundThread()
        openCameraWhenReady()
        updateCaptureControls()
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWhenReady() {
        if (viewBinding.viewFinder.isAvailable) {
            if (cameraPermissionGranted()) {
                cameraHelper.openCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        } else {
            viewBinding.viewFinder.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        visible = false
        if (captureBusy) cancelFrameCapture("Capture interrupted while the app was not active.", restorePreview = false)
        // If video recording is active, stop it.
        if (isRecordingVideo || isStartingVideo) {
            stopVideoRecording(restorePreview = false)
        }
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessions.save(outState)
        super.onSaveInstanceState(outState)
    }

    private fun openSettings(preferenceKey: String? = null) {
        val cameraId = runCatching { cameraHelper.getCameraId() }.getOrNull()
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_CAMERA_ID, cameraId)
            .putExtra(SettingsActivity.EXTRA_PREFERENCE_KEY, preferenceKey))
    }

    private fun beginCountdown(position: Position) {
        if (captureBusy || exportBusy || isRecordingVideo || isStartingVideo) return
        if (!cameraPermissionGranted()) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA); return }
        cameraHelper.recordingBlockReason()?.let { message ->
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        captureBusy = true
        orientationBeforeCapture = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        lastPosition = position
        sharedPreferences.edit().putString("last_capture_position", position.json().toString()).apply()
        updateRecordingButton()
        countdown = object : CountDownTimer(3000L, 1000L) {
            override fun onTick(remaining: Long) {
                viewBinding.burstStatus.text = getString(R.string.capture_countdown, (remaining + 999L) / 1000L)
            }
            override fun onFinish() {
                countdown = null
                viewBinding.burstStatus.text = getString(R.string.capture_starting)
                val profileKey = cameraHelper.activeProfile?.let { cameraHelper.getCameraId() to it.stream.key }
                cameraHelper.startBurst(position, ResearchSettings.mounting(sharedPreferences, profileKey),
                    progress = { count -> runOnUiThread {
                        if (!isDestroyed && captureBusy) viewBinding.burstStatus.text = getString(R.string.capture_progress, count)
                    } },
                    completed = { summary -> runOnUiThread { if (!isDestroyed) finishFrameCapture(summary) } },
                    failed = { error -> runOnUiThread {
                        if (!isDestroyed) {
                            captureBusy = false
                            requestedOrientation = orientationBeforeCapture
                            viewBinding.burstStatus.text = error
                            updateRecordingButton()
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
            viewBinding.burstStatus.text = getString(R.string.countdown_cancelled)
            updateRecordingButton()
        } else {
            viewBinding.burstStatus.text = getString(R.string.saving_partial_capture)
            cameraHelper.cancelBurst(reason, restorePreview)
        }
    }

    private fun finishFrameCapture(summary: BurstSummary) {
        captureBusy = false
        requestedOrientation = orientationBeforeCapture
        val fps = summary.fps?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "—"
        val description = getString(R.string.capture_summary, summary.positionId, summary.saved, fps)
        viewBinding.burstStatus.text = description + if (summary.warnings.isEmpty()) "" else "\n${summary.warnings.size} warning(s)"
        updateRecordingButton()
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
        if (visible) AlertDialog.Builder(this)
            .setTitle(if (summary.interrupted) R.string.capture_interrupted else R.string.capture_finished)
            .setMessage(description + "\n\n" + if (summary.warnings.isEmpty()) getString(R.string.capture_no_warnings)
                else summary.warnings.joinToString("\n\n"))
            .setPositiveButton("OK", null).show()
    }

    private fun updateCaptureControls() {
        val busy = captureBusy || exportBusy || isRecordingVideo || isStartingVideo
        setConfigurationControlsEnabled(!busy)
        viewBinding.positionButton.text = getString(R.string.position_button, ResearchSettings.position(sharedPreferences).id)
        viewBinding.startCaptureButton.text = getString(if (captureBusy) R.string.cancel_capture else R.string.start_capture)
        viewBinding.startCaptureButton.isEnabled = !exportBusy && !isRecordingVideo && !isStartingVideo
        viewBinding.repeatCaptureButton.isEnabled = !busy && lastPosition != null
        viewBinding.nextPositionButton.isEnabled = !busy && ResearchSettings.position(sharedPreferences).id < 999999
    }

    private fun startVideoRecording() {
        if (isRecordingVideo || isStartingVideo || captureBusy || exportBusy) return
        isStartingVideo = true
        val generation = ++videoGeneration
        updateRecordingButton()
        recorderHelper.startRecordingVideo { error ->
            if (generation != videoGeneration || isDestroyed) return@startRecordingVideo
            isStartingVideo = false
            isRecordingVideo = error == null
            updateRecordingButton()
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopVideoRecording(restorePreview: Boolean = true) {
        videoGeneration++
        recorderHelper.stopRecordingVideo(restorePreview)
        isStartingVideo = false
        isRecordingVideo = false
        updateRecordingButton()
    }

    private fun updateRecordingButton() {
        updateCaptureControls()
        viewBinding.startRecordingButton.isEnabled = !captureBusy && !exportBusy && !isStartingVideo
        viewBinding.startRecordingButton.text =
            getString(if (isRecordingVideo) R.string.stop_video else R.string.capture_video)
        viewBinding.startRecordingButton.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isRecordingVideo) R.color.red else R.color.green
        )
    }

    private fun setConfigurationControlsEnabled(enabled: Boolean) {
        viewBinding.titleContainer.isEnabled = enabled
        viewBinding.settingsButton.isEnabled = enabled
        viewBinding.switchCameraButton.isEnabled = enabled
        viewBinding.aboutButton.isEnabled = enabled
        viewBinding.sessionsButton.isEnabled = enabled
        viewBinding.positionButton.isEnabled = enabled
        viewBinding.zoomInButton.isEnabled = enabled
        viewBinding.zoomOutButton.isEnabled = enabled
    }

    private fun recordingPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun cameraPermissionGranted(): Boolean = permissionGranted(Manifest.permission.CAMERA)

    private fun permissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
