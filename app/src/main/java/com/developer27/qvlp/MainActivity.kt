package com.developer27.qvlp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.CameraHelper
import com.developer27.qvlp.camera.RecorderHelper
import com.developer27.qvlp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var cameraHelper: CameraHelper

    // RecorderHelper instance and flag to track recording state.
    private lateinit var recorderHelper: RecorderHelper
    private var isRecordingVideo = false
    private var isStartingVideo = false

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
            val cameraId = runCatching { cameraHelper.getCameraId() }.getOrNull()
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_CAMERA_ID, cameraId))
        }

        cameraHelper.setupZoomControls()
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
        cameraHelper.startBackgroundThread()
        openCameraWhenReady()
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
        // If video recording is active, stop it.
        if (isRecordingVideo || isStartingVideo) {
            stopVideoRecording(restorePreview = false)
        }
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        super.onPause()
    }

    private fun startVideoRecording() {
        if (isRecordingVideo || isStartingVideo) return
        isStartingVideo = true
        viewBinding.startRecordingButton.isEnabled = false
        setConfigurationControlsEnabled(false)
        recorderHelper.startRecordingVideo { error ->
            isStartingVideo = false
            isRecordingVideo = error == null
            updateRecordingButton()
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopVideoRecording(restorePreview: Boolean = true) {
        recorderHelper.stopRecordingVideo(restorePreview)
        isStartingVideo = false
        isRecordingVideo = false
        updateRecordingButton()
    }

    private fun updateRecordingButton() {
        setConfigurationControlsEnabled(!isRecordingVideo && !isStartingVideo)
        viewBinding.startRecordingButton.isEnabled = true
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
