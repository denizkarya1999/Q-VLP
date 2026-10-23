package com.developer27.qvlp.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.Matrix
import android.os.Build
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
import android.view.Surface
import android.widget.Toast
import androidx.annotation.RequiresPermission
import com.developer27.qvlp.MainActivity
import com.developer27.qvlp.R
import com.developer27.qvlp.databinding.ActivityMainBinding
import com.developer27.qvlp.capture.CaptureLength
import com.developer27.qvlp.capture.CaptureWindow
import com.developer27.qvlp.capture.BurstWriter
import com.developer27.qvlp.capture.BurstRequestTag
import com.developer27.qvlp.capture.BurstSummary
import com.developer27.qvlp.capture.FrameMetadata
import com.developer27.qvlp.capture.Position
import com.developer27.qvlp.capture.SessionFiles
import org.json.JSONObject
import java.util.Locale

/** Owns the selected camera preview and uncompressed research capture. */
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

    // Fixed preview and frame size
    var previewSize: Size? = null
    @Volatile internal var activeProfile: CameraProfile? = null
        private set
    private var activeCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    @Volatile private var cameraControls: FocusWhiteBalanceController? = null
    private var frameReader: ImageReader? = null
    private var ownedPreviewSurface: Surface? = null
    private var burstWriter: BurstWriter? = null
    private var burstTimeout: Runnable? = null
    private var burstGeneration = 0
    @Volatile private var burstRequested = false

    // Full sensor area at 1x
    var sensorArraySize: Rect? = null


    // Thread for camera operations
    private var backgroundThread: HandlerThread? = null
    var backgroundHandler: Handler? = null
        private set

    private var cameraCharacteristics: CameraCharacteristics? = null
    @Volatile private var activeCapabilities: CameraCapabilities? = null
    @Volatile private var requestedResearchCrop: Rect? = null
    private val researchConfirmation = ResearchCapturePolicy.Confirmation()
    @Volatile private var reportedResearchIssue: ResearchCapturePolicy.Issue? = ResearchCapturePolicy.Issue.CONFIRMING
    @Volatile private var selectedCameraId: String? = null
    private var zoomLevel = 1f
    private var maxZoom = 1f
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
            val cameraId = getCameraId()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            cameraCharacteristics = characteristics
            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxZoom = if (CaptureRequest.SCALER_CROP_REGION in characteristics.availableCaptureRequestKeys && sensorArraySize != null)
                (characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f)
                    .takeIf { it.isFinite() && it >= 1f } ?: 1f else 1f
            zoomLevel = sharedPreferences.getFloat("camera_${cameraId}_zoom", 1f)
                .takeIf { it.isFinite() }?.coerceIn(1f, maxZoom) ?: 1f
            reportZoom()

            val capabilities = CameraCapabilities.read(cameraId, characteristics)
            activeCapabilities = capabilities
            val profile = CameraSettingsStore(sharedPreferences, cameraId).load(capabilities)
            activeProfile = profile
            cameraControls = FocusWhiteBalanceController(activity.applicationContext, capabilities.controls, profile.controls)
            previewSize = profile.stream.size
            activity.runOnUiThread { fitPreview() }
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
        activeCapabilities = null
        selectedCameraId = null // Re-read the selection after Settings applies Factory Reset.
        requestedResearchCrop = null
        resetResearchConfirmation()
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

    // Start on the rear camera; retain a camera explicitly chosen by the operator.
    fun getCameraId(): String {
        selectedCameraId?.let { return it }
        val ids = cameraManager.cameraIdList.toList()
        val saved = sharedPreferences.getString("selected_capture_camera",
            sharedPreferences.getString("research_rear_camera", null))
        val selected = saved?.takeIf { it in ids } ?: ids.firstOrNull {
            cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull() ?: error("No camera is available on this device.")
        selectedCameraId = selected
        return selected
    }

    @SuppressLint("MissingPermission")
    fun switchCamera() {
        if (dispatch { switchCamera() }) return
        if (burstRequested || isOpeningCamera) return
        try {
            val current = getCameraId()
            val facing = cameraManager.getCameraCharacteristics(current)[CameraCharacteristics.LENS_FACING]
            val targetFacing = if (facing == CameraCharacteristics.LENS_FACING_BACK)
                CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val targets = cameraManager.cameraIdList.filter {
                cameraManager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == targetFacing
            }
            if (targets.isEmpty()) {
                message(activity.getString(R.string.other_camera_missing))
                return
            }
            // Check the destination before closing the working camera. A camera lacking
            // manual sensor/YUV support cannot provide the same research capture controls.
            val target = targets.firstOrNull { id ->
                runCatching { CameraCapabilities.read(id, cameraManager.getCameraCharacteristics(id)) }.isSuccess
            }
            if (target == null) {
                message(activity.getString(R.string.other_camera_unsupported))
                return
            }
            closeCamera()
            selectedCameraId = target
            zoomLevel = 1f
            sharedPreferences.edit().putString("selected_capture_camera", target)
                .putFloat("camera_${target}_zoom", 1f).apply()
            SessionFiles.newSession(activity.applicationContext)
            openCamera()
        } catch (error: Exception) {
            message(error.message ?: activity.getString(R.string.camera_switch_failed))
        }
    }

    fun changeZoom(step: Float) {
        if (dispatch { changeZoom(step) }) return
        if (burstRequested || isOpeningCamera || cameraDevice == null || activeProfile == null) return
        val next = (kotlin.math.round((zoomLevel + step) * 10f) / 10f).coerceIn(1f, maxZoom)
        if (next == zoomLevel) return
        val previous = zoomLevel
        try {
            zoomLevel = next
            resetResearchConfirmation()
            applyResearchGeometry()
            updatePreview()
            sharedPreferences.edit().putFloat("camera_${getCameraId()}_zoom", zoomLevel).apply()
            SessionFiles.newSession(activity.applicationContext)
        } catch (error: Exception) {
            zoomLevel = previous
            runCatching { applyResearchGeometry(); updatePreview() }
            message(error.message ?: activity.getString(R.string.zoom_unavailable))
        }
        reportZoom()
    }

    private fun reportZoom() {
        val level = zoomLevel
        val maximum = maxZoom
        activity.runOnUiThread { activity.showZoomState(level, maximum) }
    }

    private fun message(text: String) {
        activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }
    }

    /** Apply the capture profile; locks may briefly meter before restoring manual AE. */
    fun applyCaptureSettings(submitRequest: Boolean = true) {
        val profile = activeProfile ?: return
        resetResearchConfirmation()
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
        applyResearchGeometry()
        if (submitRequest) updatePreview()
    }

    private fun requestedText(profile: CameraProfile): String = activity.getString(
        R.string.capture_requested, CaptureSettings.exposureMicros(profile.exposureNs),
        profile.iso, profile.stream.label, profile.fps
    )

    private fun showRequestedProfile(profile: CameraProfile) {
        activity.runOnUiThread {
            if (activeProfile == profile) {
                activity.showCameraState(false, requestedText(profile) + "\n" +
                    activity.getString(R.string.capture_waiting))
            }
        }
    }

    private fun showCameraError(message: String) {
        activity.runOnUiThread {
            activity.showCameraState(false, message, error = true)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    internal fun recordingBlockReason(): String? {
        if (cameraDevice == null || cameraCaptureSession == null || activeProfile == null)
            return activity.getString(R.string.camera_controls_wait)
        researchConfigurationIssue()?.let { return it.message }
        val controls = cameraControls ?: return activity.getString(R.string.camera_controls_wait)
        if (!controls.ready) return controls.error ?: activity.getString(R.string.camera_controls_wait)
        return reportedResearchIssue?.message
    }

    private fun researchConfigurationIssue(): ResearchCapturePolicy.Issue? {
        val profile = activeProfile ?: return ResearchCapturePolicy.Issue.CONFIRMING
        val capabilities = activeCapabilities ?: return ResearchCapturePolicy.Issue.CONFIRMING
        val size = profile.stream.size
        val fixedResolution = previewSize == size && frameReader?.width == size.width && frameReader?.height == size.height &&
            profile.stream in capabilities.streams &&
            kotlin.math.abs(size.width.toDouble() / size.height - capabilities.sensorAspectRatio) < 0.02
        return ResearchCapturePolicy.settingsIssue(
            rear = cameraCharacteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK,
            zoom = zoomLevel, fixedResolution = fixedResolution, exposureNs = profile.exposureNs,
            fixedIso = capabilities.isoRange.contains(profile.iso),
            fps = profile.fps.takeIf { it in profile.stream.frameRates } ?: 0,
            fixedFocus = profile.controls.focusMode in listOf(FocusMode.LOCK, FocusMode.MANUAL, FocusMode.FIXED),
            fixedWhiteBalance = ResearchCameraReset.isFixedWhiteBalance(profile.controls.whiteBalanceMode)
        )
    }

    private fun resetResearchConfirmation() {
        researchConfirmation.reset()
        reportedResearchIssue = ResearchCapturePolicy.Issue.CONFIRMING
    }

    private fun dispatch(action: () -> Unit): Boolean {
        val handler = backgroundHandler ?: return false
        if (Looper.myLooper() == handler.looper) return false
        handler.post(action)
        return true
    }

    internal fun startBurst(position: Position, mounting: JSONObject, length: CaptureLength, progress: (Int, Long) -> Unit,
                            completed: (BurstSummary) -> Unit, failed: (String) -> Unit) {
        if (dispatch { startBurst(position, mounting, length, progress, completed, failed) }) return
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
                .put("digital_zoom", zoomLevel.toDouble())
                .put("lens_selection", "operator-selected camera ID; physical lens pinning not yet implemented")
        } catch (e: Exception) {
            burstRequested = false
            failed(e.message ?: "Camera information is unavailable.")
            return
        }
        BurstWriter.prepare(activity.applicationContext, profile, length, position, mounting, cameraInfo,
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
                    val request = try {
                        builder.addTarget(reader.surface)
                        builder.setTag(BurstRequestTag(originalTag, writer.id))
                        builder.build()
                    } finally {
                        builder.removeTarget(reader.surface)
                        builder.setTag(originalTag)
                    }
                    val previewCallback = activeCaptureCallback
                    val window = CaptureWindow(length)
                    var stopped = false
                    session.stopRepeating()
                    // One request keeps long captures bounded; frame IDs come from actual starts.
                    session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureStarted(s: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                            if (burstWriter !== writer) return
                            val index = window.start(frameNumber, timestamp)
                            if (index != null) writer.onStarted(index, timestamp) else writer.exclude(timestamp)
                            if (window.complete && !stopped) {
                                stopped = true
                                try { s.stopRepeating() } catch (e: Exception) {
                                    cancelBurst("Could not stop capture: ${e.message}")
                                }
                            }
                        }
                        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            if (burstWriter !== writer) return
                            window.frameId(result.frameNumber)?.let { index ->
                                runCatching { FrameMetadata.read(index, result) }
                                    .onSuccess(writer::onMetadata)
                                    .onFailure { writer.warning("Metadata was unavailable for frame $index: ${it.message}") }
                            }
                            previewCallback?.onCaptureCompleted(s, request, result)
                            cameraControls?.error?.let { writer.warning(it) }
                            if (window.frameId(result.frameNumber) != null) reportedResearchIssue?.let {
                                writer.warning("Research settings need attention: ${it.message}")
                            }
                        }
                        override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            if (burstWriter !== writer) return
                            val index = window.frameId(failure.frameNumber)
                            if (index != null || !stopped)
                                writer.warning("Camera capture failed for frame ${index ?: "unknown"} (reason ${failure.reason}).")
                        }
                        override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                            val index = window.frameId(frameNumber)
                            if (burstWriter === writer && target == reader.surface && index != null)
                                writer.warning("The camera dropped a YUV buffer for frame $index.")
                        }
                        override fun onCaptureSequenceCompleted(s: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                            if (burstWriter !== writer) return
                            // Image delivery can lag behind results. Drain before finalizing.
                            val reason = if (window.complete) null else "Camera capture ended before the selected length."
                            handler.postDelayed({ finishBurst(writer, reason) }, 2000L)
                        }
                        override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                            finishBurst(writer, "Camera capture sequence was interrupted.")
                        }
                    }, handler)
                    val timeout = length.timeoutMillis(profile.frameDurationNs)
                    burstTimeout = Runnable {
                        if (burstWriter === writer) cancelBurst("Capture timed out before all frames arrived.")
                    }.also { handler.postDelayed(it, timeout) }
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
        burstTimeout?.let { backgroundHandler?.removeCallbacks(it) }
        burstTimeout = null
        runCatching { cameraCaptureSession?.stopRepeating() }
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
                    if (expectedSession === cameraCaptureSession) activity.showCameraState(false, controls.status(), error = true)
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
                if (controls.ready && researchConfigurationIssue() == null) {
                    val reportedZoom = if (Build.VERSION.SDK_INT >= 30) result[CaptureResult.CONTROL_ZOOM_RATIO] else null
                    val cropMatches = requestedResearchCrop != null && result[CaptureResult.SCALER_CROP_REGION] == requestedResearchCrop &&
                        (reportedZoom == null || kotlin.math.abs(reportedZoom - 1f) <= 0.0001f)
                    reportedResearchIssue = researchConfirmation.observe(ResearchCapturePolicy.Reading(
                        result[CaptureResult.SENSOR_EXPOSURE_TIME], result[CaptureResult.SENSOR_SENSITIVITY],
                        result[CaptureResult.SENSOR_FRAME_DURATION], cropMatches
                    ))
                } else resetResearchConfirmation()
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
                val block = recordingBlockReason()
                val issue = researchConfigurationIssue() ?: reportedResearchIssue
                val text = requestedText(profile) + "\n" + activity.getString(
                    R.string.capture_reported, reportedExposure, reportedIso, cadence
                ) + "\n" + controls.status() + (block?.let { "\n$it" } ?: "")
                val error = controls.error != null || (issue != null && issue != ResearchCapturePolicy.Issue.CONFIRMING)
                activity.runOnUiThread {
                    if (session === cameraCaptureSession) activity.showCameraState(block == null, text, error)
                }
            }
        }.also { activeCaptureCallback = it }
    }

    /** Apply digital crop zoom while keeping the remaining geometry controls fixed. */
    private fun applyResearchGeometry() {
        val builder = captureRequestBuilder ?: return
        val c = cameraCharacteristics ?: return
        val keys = c.availableCaptureRequestKeys
        var cropBase = sensorArraySize
        if (Build.VERSION.SDK_INT >= 30 && CaptureRequest.CONTROL_ZOOM_RATIO in keys)
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f)
        if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in keys)
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in keys)
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        if (CaptureRequest.CONTROL_SCENE_MODE in keys)
            builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
        if (Build.VERSION.SDK_INT >= 28 && CaptureRequest.DISTORTION_CORRECTION_MODE in keys &&
            c[CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES]?.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF) == true) {
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            c[CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE]?.let {
                cropBase = it
            }
        }
        if (CaptureRequest.SCALER_CROP_REGION in keys) cropBase?.let { sensor ->
            // Round up so the crop never exceeds the device's maximum digital zoom.
            val width = kotlin.math.ceil(sensor.width() / zoomLevel.toDouble()).toInt().coerceIn(1, sensor.width())
            val height = kotlin.math.ceil(sensor.height() / zoomLevel.toDouble()).toInt().coerceIn(1, sensor.height())
            val left = sensor.left + (sensor.width() - width) / 2
            val top = sensor.top + (sensor.height() - height) / 2
            requestedResearchCrop = Rect(left, top, left + width, top + height)
            builder.set(CaptureRequest.SCALER_CROP_REGION, requestedResearchCrop)
        }
        if (Build.VERSION.SDK_INT >= 31 && CaptureRequest.SCALER_ROTATE_AND_CROP in keys &&
            c[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES]?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true)
            builder.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
    }

    /** Size only the displayed preview. Capture resolution, crop and saved pixels are unchanged. */
    fun fitPreview() {
        val view = viewBinding.viewFinder
        if (view.width == 0 || view.height == 0) return
        view.isOpaque = false // Reveal the black background around smaller preview sizes.
        val scale = PreviewDisplaySize.read(sharedPreferences).fittedScale
        val size = previewSize
        if (scale == null || size == null) {
            // RoTemplate filled the TextureView without an aspect-ratio transform.
            view.setTransform(Matrix())
            return
        }
        val sourceRatio = size.width.toFloat() / size.height
        val viewRatio = view.width.toFloat() / view.height
        val sx = scale * if (viewRatio > sourceRatio) sourceRatio / viewRatio else 1f
        val sy = scale * if (viewRatio < sourceRatio) viewRatio / sourceRatio else 1f
        view.setTransform(Matrix().apply { setScale(sx, sy, view.width / 2f, view.height / 2f) })
    }

    private companion object {
        const val TAG = "CameraHelper"
    }
}
