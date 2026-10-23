package com.developer27.qvlp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.ImageFormat
import android.hardware.camera2.CaptureFailure
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
import android.os.Looper
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.developer27.qvlp.MainActivity
import com.developer27.qvlp.R
import com.developer27.qvlp.databinding.ActivityMainBinding
import com.developer27.qvlp.capture.BurstWriter
import com.developer27.qvlp.capture.BurstRequestTag
import com.developer27.qvlp.capture.BurstSummary
import com.developer27.qvlp.capture.FrameMetadata
import com.developer27.qvlp.capture.Position
import org.json.JSONObject
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
    @Volatile private var cameraControls: FocusWhiteBalanceController? = null
    private var frameReader: ImageReader? = null
    private var ownedPreviewSurface: Surface? = null
    private var burstWriter: BurstWriter? = null
    private var burstGeneration = 0
    @Volatile private var burstRequested = false

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
        if (dispatch { openCamera() }) return
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
            cameraControls = FocusWhiteBalanceController(activity.applicationContext, capabilities.controls, profile.controls)
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
        if (dispatch { closeCamera() }) return
        cancelBurst("Capture interrupted because the camera closed.", restorePreview = false)
        cameraOpenGeneration += 1
        isOpeningCamera = false
        cameraCaptureSession?.close()
        cameraCaptureSession = null
        frameReader?.close()
        frameReader = null
        ownedPreviewSurface?.release()
        ownedPreviewSurface = null
        cameraDevice?.close()
        cameraDevice = null
        captureRequestBuilder = null
        activeProfile = null
        activeCaptureCallback = null
        cameraControls = null
    }

    // ------------------------------------------------------------------------
    // Create Preview
    // ------------------------------------------------------------------------
    fun createCameraPreview() {
        if (dispatch { createCameraPreview() }) return
        try {
            val activeCamera = cameraDevice ?: return
            val texture = viewBinding.viewFinder.surfaceTexture ?: return
            // Match the texture view size to the chosen preview size
            previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }

            cameraCaptureSession?.close()
            cameraCaptureSession = null
            frameReader?.close()
            ownedPreviewSurface?.release()
            val previewSurface = Surface(texture).also { ownedPreviewSurface = it }
            val size = requireNotNull(previewSize)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
            frameReader = reader
            reader.setOnImageAvailableListener({ source ->
                if (source === frameReader) {
                    try {
                        while (true) {
                            val image = source.acquireNextImage() ?: break
                            val writer = burstWriter
                            if (writer != null) writer.onImage(image) else image.close()
                        }
                    } catch (e: Exception) {
                        burstWriter?.warning("Camera image read failed: ${e.message}")
                    }
                }
            }, backgroundHandler)
            // Build a preview request
            captureRequestBuilder = activeCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            // Add the preview surface as a target
            captureRequestBuilder?.addTarget(previewSurface)

            applyCaptureSettings(submitRequest = false)

            // Now create the capture session
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            activeCamera.createCaptureSession(
                listOf(previewSurface, reader.surface),
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
        if (dispatch { updatePreview() }) return
        if (burstWriter != null) return
        if (cameraDevice == null) return
        try {
            val requestBuilder = captureRequestBuilder ?: return
            cameraCaptureSession?.setRepeatingRequest(
                synchronized(requestBuilder) { requestBuilder.build() },
                activeCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            cameraControls?.fail(e.message ?: "Preview request failed.")
            showCameraError(e.message ?: "Preview request failed.")
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to update camera preview", e)
            cameraControls?.fail(activity.getString(R.string.camera_locks_unconfirmed))
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

    /** Apply the capture profile; locks may briefly meter before restoring manual AE. */
    fun applyCaptureSettings(submitRequest: Boolean = true) {
        val profile = activeProfile ?: return
        captureRequestBuilder?.apply {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, profile.exposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, profile.iso)
            // AE target FPS does not control cadence when exposure is manual.
            set(CaptureRequest.SENSOR_FRAME_DURATION, profile.frameDurationNs)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            cameraControls?.apply(this)
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

    internal fun recordingBlockReason(): String? {
        val controls = cameraControls ?: return activity.getString(R.string.camera_controls_wait)
        return if (controls.ready) null else controls.error ?: activity.getString(R.string.camera_controls_wait)
    }

    private fun dispatch(action: () -> Unit): Boolean {
        val handler = backgroundHandler ?: return false
        if (Looper.myLooper() == handler.looper) return false
        handler.post(action)
        return true
    }

    internal fun startBurst(position: Position, mounting: JSONObject, progress: (Int) -> Unit,
                            completed: (BurstSummary) -> Unit, failed: (String) -> Unit) {
        if (dispatch { startBurst(position, mounting, progress, completed, failed) }) return
        if (burstRequested) { failed("A capture is already running."); return }
        val device = cameraDevice
        val session = cameraCaptureSession
        val profile = activeProfile
        val reader = frameReader
        val handler = backgroundHandler
        val block = recordingBlockReason()
        if (device == null || session == null || profile == null || reader == null || handler == null || block != null) {
            failed(block ?: "Camera is not ready for frame capture.")
            return
        }
        burstRequested = true
        val generation = ++burstGeneration
        val cameraInfo = try {
            val characteristics = cameraManager.getCameraCharacteristics(getCameraId())
            JSONObject().put("camera_id", getCameraId())
                .put("sensor_orientation_degrees", characteristics[CameraCharacteristics.SENSOR_ORIENTATION])
                .put("lens_facing", characteristics[CameraCharacteristics.LENS_FACING])
                .put("digital_zoom", zoomLevel)
        } catch (e: Exception) {
            burstRequested = false
            failed(e.message ?: "Camera information is unavailable.")
            return
        }
        BurstWriter.prepare(activity.applicationContext, profile, position, mounting, cameraInfo,
            progress, completed) { writer, error ->
            val posted = handler.post {
                if (generation != burstGeneration || cameraDevice !== device || cameraCaptureSession !== session) {
                    writer?.finish("Capture interrupted before frames started.")
                    if (writer == null) failed(error ?: "Capture cancelled before frames started.")
                    return@post
                }
                if (writer == null) {
                    burstRequested = false
                    failed(error ?: "Could not prepare frame storage.")
                    return@post
                }
                val lockError = recordingBlockReason()
                if (lockError != null) {
                    burstRequested = false
                    writer.finish(lockError)
                    return@post
                }
                burstWriter = writer
                try {
                    val builder = requireNotNull(captureRequestBuilder)
                    val originalTag = builder.build().tag
                    val requests = try {
                        builder.addTarget(reader.surface)
                        List(BurstWriter.FRAME_COUNT) { index ->
                            builder.setTag(BurstRequestTag(originalTag, writer.id, index))
                            builder.build()
                        }
                    } finally {
                        builder.removeTarget(reader.surface)
                        builder.setTag(originalTag)
                    }
                    val previewCallback = activeCaptureCallback
                    session.stopRepeating()
                    session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                        private fun frame(request: CaptureRequest): Int? =
                            (request.tag as? BurstRequestTag)?.takeIf { it.burstId == writer.id }?.frameId

                        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            if (burstWriter === writer) frame(request)?.let { writer.onStarted(it, timestamp) }
                        }
                        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            if (burstWriter !== writer) return
                            frame(request)?.let { index ->
                                runCatching { FrameMetadata.read(index, result) }
                                    .onSuccess(writer::onMetadata)
                                    .onFailure { writer.warning("Metadata was unavailable for frame $index: ${it.message}") }
                            }
                            previewCallback?.onCaptureCompleted(s, request, result)
                            cameraControls?.error?.let { writer.warning(it) }
                        }
                        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            if (burstWriter === writer) writer.warning("Camera capture failed for frame ${frame(request)} (reason ${failure.reason}).")
                        }
                        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                            if (burstWriter === writer && target == reader.surface)
                                writer.warning("The camera dropped a YUV buffer for frame ${frame(request)}.")
                        }
                        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                            // Image delivery can lag behind CaptureResult delivery. Drain before finalizing.
                            handler.postDelayed({ finishBurst(writer, null) }, 2000L)
                        }
                        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                            finishBurst(writer, "Camera capture sequence was interrupted.")
                        }
                    }, handler)
                    val timeout = maxOf(12_000L, BurstWriter.FRAME_COUNT * 3000L / profile.fps + 5000L)
                    handler.postDelayed({
                        if (burstWriter === writer) cancelBurst("Capture timed out before all frames arrived.")
                    }, timeout)
                } catch (e: Exception) {
                    finishBurst(writer, "Capture could not finish: ${e.message}")
                }
            }
            if (!posted) {
                writer?.finish("Capture interrupted because the camera closed.")
                if (writer == null) failed(error ?: "Camera closed before capture started.")
            }
        }
    }

    private fun finishBurst(writer: BurstWriter, reason: String?, restorePreview: Boolean = true) {
        if (burstWriter !== writer) return
        burstWriter = null
        burstRequested = false
        writer.finish(reason)
        if (restorePreview) updatePreview()
    }

    fun cancelBurst(reason: String, restorePreview: Boolean = true) {
        if (dispatch { cancelBurst(reason, restorePreview) }) return
        burstGeneration++
        burstRequested = false
        val writer = burstWriter ?: return
        runCatching { cameraCaptureSession?.abortCaptures() }
        finishBurst(writer, reason, restorePreview)
    }

    /** Confirms controls and reports measurements; burst callbacks additionally save metadata. */
    internal fun newCaptureCallback(onReady: ((String?) -> Unit)? = null): CameraCaptureSession.CaptureCallback {
        val controls = requireNotNull(cameraControls) { "Camera controls are unavailable." }
        controls.beginSession()
        val expectedSession = cameraCaptureSession
        var readinessDelivered = false
        fun deliverReadiness(error: String?) {
            if (!readinessDelivered) {
                readinessDelivered = true
                onReady?.invoke(error)
            }
        }
        backgroundHandler?.postDelayed({
            if (expectedSession != null && expectedSession === cameraCaptureSession &&
                controls === cameraControls && !readinessDelivered) {
                controls.fail(activity.getString(R.string.camera_locks_unconfirmed))
                applyCaptureSettings()
                deliverReadiness(controls.error)
                activity.runOnUiThread {
                    if (expectedSession === cameraCaptureSession) viewBinding.captureStatus.text = controls.status()
                }
            }
        }, FocusWhiteBalanceController.SETTLE_TIMEOUT_MS + 1000L)
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
                if (controls !== cameraControls) return
                val update = controls.onResult(result)
                if (update.repeat) applyCaptureSettings()
                if (update.triggerFocus) {
                    val builder = captureRequestBuilder ?: return
                    synchronized(builder) {
                        try {
                            // Keep concurrent preview updates from repeating the one-shot trigger.
                            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                            session.capture(builder.build(), this, backgroundHandler)
                        } catch (e: Exception) {
                            controls.fail(activity.getString(R.string.focus_lock_failed))
                            Log.e(TAG, "Focus trigger failed", e)
                        } finally {
                            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        }
                    }
                    if (controls.error != null) applyCaptureSettings()
                }
                if (controls.error != null || controls.ready) deliverReadiness(controls.error)
                if (session !== cameraCaptureSession) return
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
                ) + "\n" + controls.status()
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
        if (!viewBinding.zoomInButton.isEnabled || burstRequested) return
        if (zoomLevel < maxZoom) {
            zoomLevel += 0.1f
            applyZoom()
        }
    }

    private fun zoomOut() {
        if (!viewBinding.zoomOutButton.isEnabled || burstRequested) return
        if (zoomLevel > 1.0f) {
            zoomLevel -= 0.1f
            applyZoom()
        }
    }

    /**
     * Applies digital zoom by setting the SCALER_CROP_REGION
     */
    fun applyZoom(submitRequest: Boolean = true) {
        if (dispatch { applyZoom(submitRequest) }) return
        if (burstRequested) return
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
                synchronized(requestBuilder) { requestBuilder.build() },
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
