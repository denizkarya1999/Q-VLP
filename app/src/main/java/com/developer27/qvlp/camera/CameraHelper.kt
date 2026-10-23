package com.developer27.qvlp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.developer27.qvlp.MainActivity
import com.developer27.qvlp.R
import com.developer27.qvlp.databinding.ActivityMainBinding
import java.util.Locale

/** Owns Camera2 preview and one manual profile shared with the recorder. */
class CameraHelper(
    private val activity: MainActivity,
    private val viewBinding: ActivityMainBinding,
    private val sharedPreferences: SharedPreferences
) {
    // The Android Camera2 API
    val cameraManager: CameraManager by lazy {
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    // Active camera device + capture session
    @Volatile var cameraDevice: CameraDevice? = null
    @Volatile var cameraCaptureSession: CameraCaptureSession? = null

    // Capture builder for preview (and record)
    var captureRequestBuilder: CaptureRequest.Builder? = null

    // Preview + video sizes
    var previewSize: Size? = null
    @Volatile internal var activeProfile: CameraProfile? = null
        private set
    private var activeCaptureCallback: CameraCaptureSession.CaptureCallback? = null

    // Sensor area for zoom
    var sensorArraySize: Rect? = null

    // Whether we are using the front camera
    var isFrontCamera = false

    // Thread for camera operations
    private var backgroundThread: HandlerThread? = null
    var backgroundHandler: Handler? = null
        private set

    // Zoom control
    private var zoomLevel = 1.0f
    private var maxZoom = 1.0f
    @Volatile
    private var isOpeningCamera = false
    @Volatile
    private var cameraOpenGeneration = 0

    /**
     * Callback for camera device events
     */
    private fun stateCallback(generation: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (!isOpeningCamera || generation != cameraOpenGeneration) {
                camera.close()
                return
            }
            isOpeningCamera = false
            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (generation == cameraOpenGeneration) {
                isOpeningCamera = false
                if (cameraDevice === camera) cameraDevice = null
            }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (generation != cameraOpenGeneration) return
            isOpeningCamera = false
            if (cameraDevice === camera) cameraDevice = null
            showCameraError("Camera error ($error). Try reopening the camera or choosing another configuration.")
        }
    }

    // ------------------------------------------------------------------------
    // Background Thread Setup
    // ------------------------------------------------------------------------
    fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------------------
    // Open/Close Camera
    // ------------------------------------------------------------------------
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera() {
        if (cameraDevice != null || isOpeningCamera) return
        try {
            // Decide which camera (front/back)
            val cameraId = getCameraId()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            // Grab the full sensor area for zoom
            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1.0f).coerceAtLeast(1.0f)
            zoomLevel = zoomLevel.coerceIn(1.0f, maxZoom)

            val capabilities = CameraCapabilities.read(cameraId, characteristics)
            val profile = CameraSettingsStore(sharedPreferences, cameraId).load(capabilities)
            activeProfile = profile
            previewSize = profile.stream.size
            showRequestedProfile(profile)

            // Now open the selected camera
            val generation = cameraOpenGeneration + 1
            cameraOpenGeneration = generation
            isOpeningCamera = true
            cameraManager.openCamera(cameraId, stateCallback(generation), backgroundHandler)
        } catch (e: CameraAccessException) {
            isOpeningCamera = false
            activeProfile = null
            showCameraError(e.message ?: "Camera access failed.")
        } catch (e: SecurityException) {
            isOpeningCamera = false
            activeProfile = null
            showCameraError("Camera permission needed.")
        } catch (e: RuntimeException) {
            isOpeningCamera = false
            Log.e(TAG, "Unable to open camera", e)
            activeProfile = null
            showCameraError(e.message ?: "Unable to open the camera.")
        }
    }

    fun closeCamera() {
        cameraOpenGeneration += 1
        isOpeningCamera = false
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        cameraDevice?.close()
        cameraDevice = null
        captureRequestBuilder = null
        activeProfile = null
        activeCaptureCallback = null
    }

    // ------------------------------------------------------------------------
    // Create Preview
    // ------------------------------------------------------------------------
    fun createCameraPreview() {
        try {
            val activeCamera = cameraDevice ?: return
            val texture = viewBinding.viewFinder.surfaceTexture ?: return
            // Match the texture view size to the chosen preview size
            previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }

            val previewSurface = Surface(texture)
            // Build a preview request
            captureRequestBuilder = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            // Add the preview surface as a target
            captureRequestBuilder?.addTarget(previewSurface)

            applyCaptureSettings(submitRequest = false)

            // Now create the capture session
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            activeCamera.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice !== activeCamera) {
                            session.close()
                            return
                        }
                        // Save the session
                        cameraCaptureSession = session
                        newCaptureCallback()
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (cameraDevice === activeCamera) showCameraError(
                            "Camera rejected the preview configuration. Choose a lower resolution or frame rate in Settings."
                        )
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            showCameraError(e.message ?: "Preview configuration failed.")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to create camera preview", e)
            showCameraError(e.message ?: "Preview configuration failed.")
        }
    }

    /**
     * Update the camera preview with latest builder settings
     */
    fun updatePreview() {
        if (cameraDevice == null) return
        try {
            val requestBuilder = captureRequestBuilder ?: return
            cameraCaptureSession?.setRepeatingRequest(
                requestBuilder.build(),
                activeCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            showCameraError(e.message ?: "Preview request failed.")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to update camera preview", e)
            showCameraError("Camera rejected the requested settings. Try a lower resolution or frame rate in Settings.")
        }
    }

    // ------------------------------------------------------------------------
    // Camera Selection (Front/Back)
    // ------------------------------------------------------------------------
    fun getCameraId(): String {
        for (id in cameraManager.cameraIdList) {
            val facing = cameraManager
                .getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (!isFrontCamera && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            } else if (isFrontCamera && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        // fallback if none matched
        return cameraManager.cameraIdList.firstOrNull()
            ?: throw IllegalStateException("No camera is available on this device.")
    }

    /** Keep 3A available for focus/white balance, but disable automatic exposure. */
    fun applyCaptureSettings(submitRequest: Boolean = true) {
        val profile = requireNotNull(activeProfile) { "A manual capture profile is required." }
        captureRequestBuilder?.apply {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, profile.exposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, profile.iso)
            // AE target FPS does not control cadence when exposure is manual.
            set(CaptureRequest.SENSOR_FRAME_DURATION, profile.frameDurationNs)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        }
        applyZoom(submitRequest = false)
        if (submitRequest) updatePreview()
    }

    private fun requestedText(profile: CameraProfile): String = activity.getString(
        R.string.capture_requested, CaptureSettings.exposureMicros(profile.exposureNs),
        profile.iso, profile.stream.label, profile.fps
    )

    private fun showRequestedProfile(profile: CameraProfile) {
        activity.runOnUiThread {
            if (activeProfile == profile) {
                viewBinding.captureStatus.text = requestedText(profile) + "\n" +
                    activity.getString(R.string.capture_waiting)
            }
        }
    }

    private fun showCameraError(message: String) {
        activity.runOnUiThread {
            viewBinding.captureStatus.text = activity.getString(R.string.capture_unavailable, message)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Reports actual sensor metadata and cadence; it does not yet save per-frame metadata. */
    internal fun newCaptureCallback(): CameraCaptureSession.CaptureCallback {
        activeProfile?.let(::showRequestedProfile)
        return object : CameraCaptureSession.CaptureCallback() {
            private var firstTimestamp = 0L
            private var previousTimestamp = 0L
            private var intervals = 0
            private var lastUiUpdateMs = 0L
            private var measuredFps: Double? = null

            override fun onCaptureCompleted(
                session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult
            ) {
                if (session !== cameraCaptureSession) return
                val profile = activeProfile ?: return
                val timestamp = result[CaptureResult.SENSOR_TIMESTAMP]
                if (timestamp != null) {
                    if (firstTimestamp == 0L || timestamp <= previousTimestamp) {
                        firstTimestamp = timestamp
                        intervals = 0
                        measuredFps = null
                    } else {
                        intervals++
                        val elapsed = timestamp - firstTimestamp
                        if (elapsed >= 1_000_000_000L) {
                            measuredFps = intervals * 1_000_000_000.0 / elapsed
                            firstTimestamp = timestamp
                            intervals = 0
                        }
                    }
                    previousTimestamp = timestamp
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUiUpdateMs < 1000L) return
                lastUiUpdateMs = now
                val reportedExposure = result[CaptureResult.SENSOR_EXPOSURE_TIME]
                    ?.let(CaptureSettings::exposureMicros) ?: "—"
                val reportedIso = result[CaptureResult.SENSOR_SENSITIVITY]?.toString() ?: "—"
                val cadence = measuredFps?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"
                val text = requestedText(profile) + "\n" + activity.getString(
                    R.string.capture_reported, reportedExposure, reportedIso, cadence
                )
                activity.runOnUiThread {
                    if (session === cameraCaptureSession) viewBinding.captureStatus.text = text
                }
            }
        }.also { activeCaptureCallback = it }
    }

    // ------------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------------
    fun setupZoomControls() {
        val zoomHandler = Handler(activity.mainLooper)
        var zoomInRunnable: Runnable? = null
        var zoomOutRunnable: Runnable? = null

        // Repetitive zoom in on long-press
        viewBinding.zoomInButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomInRunnable = object : Runnable {
                        override fun run() {
                            zoomIn()
                            zoomHandler.postDelayed(this, 50)
                        }
                    }
                    zoomHandler.post(zoomInRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomInRunnable?.let(zoomHandler::removeCallbacks)
                    true
                }
                else -> false
            }
        }

        // Repetitive zoom out on long-press
        viewBinding.zoomOutButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    zoomOutRunnable = object : Runnable {
                        override fun run() {
                            zoomOut()
                            zoomHandler.postDelayed(this, 50)
                        }
                    }
                    zoomHandler.post(zoomOutRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    zoomOutRunnable?.let(zoomHandler::removeCallbacks)
                    true
                }
                else -> false
            }
        }
    }

    private fun zoomIn() {
        if (zoomLevel < maxZoom) {
            zoomLevel += 0.1f
            applyZoom()
        }
    }

    private fun zoomOut() {
        if (zoomLevel > 1.0f) {
            zoomLevel -= 0.1f
            applyZoom()
        }
    }

    /**
     * Applies digital zoom by setting the SCALER_CROP_REGION
     */
    fun applyZoom(submitRequest: Boolean = true) {
        val sensorRect = sensorArraySize ?: return
        val requestBuilder = captureRequestBuilder ?: return
        zoomLevel = zoomLevel.coerceIn(1.0f, maxZoom)
        val ratio = 1 / zoomLevel
        val croppedWidth = sensorRect.width() * ratio
        val croppedHeight = sensorRect.height() * ratio

        val left = sensorRect.left + ((sensorRect.width() - croppedWidth) / 2).toInt()
        val top = sensorRect.top + ((sensorRect.height() - croppedHeight) / 2).toInt()
        val right = (left + croppedWidth).toInt()
        val bottom = (top + croppedHeight).toInt()

        val zoomRect = Rect(left, top, right, bottom)
        requestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)

        if (!submitRequest) return
        try {
            cameraCaptureSession?.setRepeatingRequest(
                requestBuilder.build(),
                activeCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Unable to update zoom", e)
        }
    }

    private companion object {
        const val TAG = "CameraHelper"
    }
}
