package com.developer27.qvlp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.CountDownTimer
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.preference.PreferenceManager
import com.developer27.qvlp.camera.CameraChoice
import com.developer27.qvlp.camera.CameraHelper
import com.developer27.qvlp.camera.CameraRecovery
import com.developer27.qvlp.capture.CaptureTrigger
import com.developer27.qvlp.capture.CaptureLength
import com.developer27.qvlp.capture.CaptureProcessing
import com.developer27.qvlp.capture.CaptureLengthPreferences
import com.developer27.qvlp.capture.BurstSummary
import com.developer27.qvlp.capture.Position
import com.developer27.qvlp.capture.PositionPicker
import com.developer27.qvlp.capture.PublicSessionStorage
import com.developer27.qvlp.capture.ResearchSettings
import com.developer27.qvlp.capture.SessionBrowser
import com.developer27.qvlp.capture.SessionCheck
import com.developer27.qvlp.capture.SessionCsv
import com.developer27.qvlp.capture.SessionFiles
import com.developer27.qvlp.capture.SessionPublisher
import com.developer27.qvlp.databinding.ActivityMainBinding
import com.developer27.qvlp.inference.InferenceModels
import com.developer27.qvlp.inference.LedRecognition
import com.developer27.qvlp.inference.LiveInference
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences
    private lateinit var cameraHelper: CameraHelper
    private lateinit var positions: PositionPicker
    private lateinit var sessions: SessionBrowser
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private val captureTrigger = CaptureTrigger()
    private var resumed = false
    private var triggerBusy = false
    private var captureBusy = false
    private var fileBusy = false
    private var cameraReady = false
    private var cameraDetails = ""
    private var currentZoom = 1f
    private var maximumZoom = 1f
    private var switchIconToRear: Boolean? = null
    private var capturedPositions: Set<Int> = emptySet()
    private var cameraNeedsAttention = false
    private val cameraRecovery = CameraRecovery()
    private var countdown: CountDownTimer? = null
    private var orientationBeforeCapture = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastPosition: Position? = null
    private var resultDetails: String? = null
    // A capture the operator stopped is theirs to repeat or not; any other incomplete one is reported.
    private var cancelledByOperator = false
    private var endSessionAsked = false
    private var storageState = SessionPublisher.State()
    private var stopObservingStorage: (() -> Unit)? = null
    // Inference Mode: the model runs on the preview, and positions are captured only when their files are wanted.
    private var inferenceMode = false
    private var postInferenceFiles = false
    private var inferenceModels = InferenceModels.BOTH
    private var liveInference: LiveInference? = null
    private var inferenceResult: LiveInference.Result? = null

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
        cameraHelper = CameraHelper(this, viewBinding, preferences)
        lastPosition = runCatching {
            val saved = JSONObject(requireNotNull(preferences.getString("last_capture_position", null)))
            Position(saved.getInt("position_id"))
        }.getOrNull()
        sessions = SessionBrowser(this, ::setFileBusy) { resetPosition -> sessionChanged(resetPosition) }
        sessions.restore(savedInstanceState)
        viewBinding.storageStatus.setOnClickListener { sessions.storage(retry = storageState.needsAttention) }
        // The session folder exists from the first start, before any capture or auto-save setup.
        applicationContext.let { app -> SessionFiles.io.execute { PublicSessionStorage.ensureFolder(app) } }
        stopObservingStorage = SessionPublisher.observe { state ->
            storageState = state
            showStorageStatus()
        }
        positions = PositionPicker(this, preferences) {
            clearResult()
            updateControls()
        }
        cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCameraWhenReady() else {
                cameraReady = false
                showCameraNotice(R.string.camera_permission_help)
                updateControls()
            }
        }
        viewBinding.titleContainer.setOnClickListener { openProjectWebsite() }
        viewBinding.settingsButton.setOnClickListener { openSettings() }
        viewBinding.zoomInButton.setOnClickListener { if (!captureBusy && !fileBusy) cameraHelper.changeZoom(0.1f) }
        viewBinding.zoomOutButton.setOnClickListener { if (!captureBusy && !fileBusy) cameraHelper.changeZoom(-0.1f) }
        viewBinding.switchCameraButton.setOnClickListener { switchCamera() }
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
        viewBinding.startCaptureButton.setOnClickListener { triggerCapture() }
        viewBinding.repeatCaptureButton.setOnClickListener {
            lastPosition?.let { ResearchSettings.select(preferences, it); beginCountdown(it) }
        }
        viewBinding.infoButton.setOnClickListener { showInformation() }
        TooltipCompat.setTooltipText(viewBinding.infoButton, getString(R.string.information))
        viewBinding.previousPositionButton.setOnClickListener {
            ResearchSettings.previous(preferences)?.let(::selectForCapture)
        }
        viewBinding.replacePositionButton.setOnClickListener {
            positions.replace(capturedPositions, lastPosition?.id, ::selectForCapture)
        }
        viewBinding.endSessionButton.setOnClickListener { askToEndSession() }
        viewBinding.nextPositionButton.setOnClickListener {
            ResearchSettings.next(preferences)?.let {
                ResearchSettings.select(preferences, it)
                clearResult()
                viewBinding.burstStatus.setText(R.string.next_position_ready)
                updateControls()
            }
        }
        resultDetails = savedInstanceState?.getString("result_details")
        savedInstanceState?.getString("result_text")?.let { viewBinding.burstStatus.text = it }
        updateControls()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!captureBusy && resultDetails == null) showCapturePlan()
        cameraHelper.fitPreview()
        cameraHelper.startBackgroundThread()
        cameraRecovery.restart() // Settings may have changed while the screen was away.
        if (FactoryReset.consume()) sessionChanged(resetPosition = true)
        applyMode()
        openCameraWhenReady()
        updateControls()
        if (!captureBusy && !fileBusy) sessions.resumeSaving()
        refreshCapturedPositions()
    }

    /**
     * Reads the mode chosen in Settings. Inference Mode runs the two LED models on the preview and draws
     * what it finds. Called before the camera opens, because the camera then also has to deliver its frames.
     */
    private fun applyMode() {
        inferenceMode = SettingsActivity.inferenceMode(preferences)
        postInferenceFiles = SettingsActivity.postInferenceFiles(preferences)
        inferenceModels = SettingsActivity.inferenceModels(preferences)
        // Captures of the two modes hold different files, so they never share a session. A session whose mode
        // was never stored is from before Inference Mode became the default: it is a Data Collection one.
        val mode = if (inferenceMode) Modes.INFERENCE else Modes.DATA_COLLECTION
        if (preferences.getString(SESSION_MODE, Modes.DATA_COLLECTION) != mode) {
            preferences.edit().putString(SESSION_MODE, mode).apply()
            SessionFiles.newSession(applicationContext)
            sessionChanged(resetPosition = false)
        }
        liveInference?.stop()
        inferenceResult = null
        viewBinding.ledOverlay.clear()
        var live: LiveInference? = null
        // With the detection model unticked there is nothing to look for, so no frames are taken for it.
        // With Enable CV Algorithms it also finds the blob centroid of each LED: CV Processing.
        if (inferenceMode && inferenceModels.detection) live = LiveInference(this, inferenceModels, SettingsActivity.cvAlgorithms(preferences)) { result ->
            runOnUiThread { if (liveInference === live) showInference(result) }
        }
        liveInference = live
        cameraHelper.liveFrames = live?.let { running -> { image -> running.offer(image) } }
    }

    private fun stopInference() {
        liveInference?.stop()
        liveInference = null
        cameraHelper.liveFrames = null
        viewBinding.ledOverlay.clear()
    }

    /** The boxes go on the preview; everything else about a result is behind the information button. */
    private fun showInference(result: LiveInference.Result) {
        if (isDestroyed) return
        val first = inferenceResult == null
        inferenceResult = result
        val geometry = cameraHelper.previewGeometry()
        // While frames are being captured the preview boxes would belong to an older frame.
        if (result.error != null || geometry == null || captureBusy) viewBinding.ledOverlay.clear()
        else viewBinding.ledOverlay.show(result.detections, result.frameWidth, result.frameHeight, geometry, result.centres)
        if (result.error != null && first)
            Toast.makeText(this, getString(R.string.inference_failed, result.error), Toast.LENGTH_LONG).show()
    }

    @SuppressLint("MissingPermission")
    private fun openCameraWhenReady() {
        if (viewBinding.viewFinder.isAvailable) {
            if (cameraPermissionGranted()) cameraHelper.openCamera()
            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else viewBinding.viewFinder.surfaceTextureListener = textureListener
    }

    override fun onPause() {
        resumed = false
        captureTrigger.reset()
        cameraReady = false
        if (captureBusy) cancelFrameCapture("Capture interrupted while the app was not active.", restorePreview = false)
        stopInference()
        cameraHelper.closeCamera()
        cameraHelper.stopBackgroundThread()
        super.onPause()
    }

    override fun onDestroy() {
        stopObservingStorage?.invoke()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sessions.save(outState)
        outState.putString("result_details", resultDetails)
        outState.putString("result_text", viewBinding.burstStatus.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) captureTrigger.reset()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val supported = event.keyCode in setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CAMERA) || (event.keyCode == KeyEvent.KEYCODE_ENTER && event.device?.isVirtual == false &&
                event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0)
        if (!supported || !resumed || !hasWindowFocus() || !captureAvailable()) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (captureTrigger.press(event.deviceId, event.keyCode, event.repeatCount)) triggerCapture()
            KeyEvent.ACTION_UP -> captureTrigger.release(event.deviceId, event.keyCode)
        }
        return true
    }

    /** Data Collection Mode always captures. Inference Mode captures only when its files are wanted. */
    private fun captureAvailable(): Boolean = !inferenceMode || postInferenceFiles

    private fun triggerCapture() {
        if (fileBusy || triggerBusy || !captureAvailable()) return
        if (captureBusy) { cancelledByOperator = true; cancelFrameCapture(); return }
        if (!cameraReady) {
            Toast.makeText(this, R.string.camera_wait_button, Toast.LENGTH_SHORT).show()
            return
        }
        if (!ResearchSettings.hasPosition(preferences)) positions.show()
        else beginCountdown(ResearchSettings.position(preferences))
    }

    private fun switchCamera() {
        if (captureBusy || fileBusy) return
        if (!cameraPermissionGranted()) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA); return }
        cameraReady = false // Capture waits for the other camera to confirm its settings.
        cameraRecovery.restart()
        viewBinding.ledOverlay.clear()
        cameraHelper.switchCamera()
        updateControls()
    }

    /** Everything the camera screen no longer shows all the time: camera, saving and the last capture. */
    private fun showInformation() {
        val camera = getString(when {
            !cameraPermissionGranted() -> R.string.camera_permission_help
            cameraNeedsAttention -> R.string.camera_attention
            cameraReady -> R.string.camera_ready
            else -> R.string.camera_preparing
        })
        val sections = listOfNotNull(
            if (inferenceMode) getString(R.string.info_inference) to inferenceDetails() else null,
            if (inferenceMode && SettingsActivity.cvAlgorithms(preferences)) getString(R.string.info_cv_processing) to cvProcessingDetails() else null,
            getString(R.string.info_camera) to listOf(camera, cameraDetails).filter { it.isNotBlank() }.joinToString("\n"),
            getString(R.string.info_saving) to storageState.message,
            getString(R.string.info_last_capture) to (resultDetails ?: getString(R.string.info_no_capture))
        )
        AlertDialog.Builder(this).setTitle(R.string.information)
            .setMessage(sections.joinToString("\n\n") { (title, text) -> "${title.uppercase()}\n$text" })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun inferenceDetails(): String {
        val result = inferenceResult
        val lines = mutableListOf<String>()
        if (inferenceModels.detection) lines.add(LedRecognition.DETECTION_MODEL_NAME)
        if (inferenceModels.recognition) lines.add(LedRecognition.RECOGNITION_MODEL_NAME)
        when {
            !inferenceModels.detection -> lines.add(getString(R.string.inference_no_model))
            result == null -> lines.add(getString(R.string.inference_starting))
            result.error != null -> lines.add(getString(R.string.inference_failed, result.error))
            else -> {
                lines.add(getString(R.string.inference_latest, result.milliseconds))
                lines.add(if (result.detections.isEmpty()) getString(R.string.inference_no_leds)
                    else result.detections.joinToString("\n") { it.label })
            }
        }
        return lines.joinToString("\n")
    }

    /** CV Processing: the blob centroid of each LED in the latest frame, as final.csv would give it. */
    private fun cvProcessingDetails(): String {
        val result = inferenceResult
        val lines = mutableListOf(getString(R.string.cv_blob_centroids))
        when {
            !inferenceModels.detection -> lines.add(getString(R.string.cv_no_model))
            result == null -> lines.add(getString(R.string.inference_starting))
            result.error != null -> lines.add(getString(R.string.inference_failed, result.error))
            result.detections.isEmpty() -> lines.add(getString(R.string.inference_no_leds))
            else -> result.detections.forEachIndexed { index, led ->
                val centre = result.centres.getOrNull(index)
                lines.add(if (centre == null) getString(R.string.cv_no_blob, led.name)
                    else getString(R.string.cv_centroid, led.name, CaptureProcessing.number(centre.u), CaptureProcessing.number(centre.v)))
            }
        }
        if (postInferenceFiles) lines.add(getString(R.string.cv_capture_note))
        return lines.joinToString("\n")
    }

    /** A session ended or another became current: the last result and position no longer apply. */
    private fun sessionChanged(resetPosition: Boolean) {
        clearResult()
        lastPosition = null
        preferences.edit().remove("last_capture_position").apply()
        if (resetPosition) ResearchSettings.clear(preferences)
        refreshCapturedPositions()
        updateControls()
    }

    /** Going back to a position: the next capture there replaces the earlier one in final.csv. */
    private fun selectForCapture(position: Position) {
        ResearchSettings.select(preferences, position)
        clearResult()
        viewBinding.burstStatus.text = getString(
            if (position.id in capturedPositions) R.string.replacing_position else R.string.position_not_captured, position.id)
        updateControls()
    }

    private fun refreshCapturedPositions() {
        val app = applicationContext
        SessionFiles.io.execute {
            val captured = runCatching { SessionFiles.capturedPositions(app) }.getOrDefault(emptySet())
            runOnUiThread { if (!isDestroyed) capturedPositions = captured }
        }
    }

    private fun openSettings() {
        val cameraId = runCatching { cameraHelper.getCameraId() }.getOrNull()
        startActivity(Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_CAMERA_ID, cameraId))
    }

    /** The technical measurements stay available without filling the capture screen. */
    internal fun showCameraState(ready: Boolean, details: String, error: Boolean = false, certain: Boolean = true) {
        if (isDestroyed) return
        cameraReady = ready
        cameraDetails = details
        if (ready) cameraRecovery.restart()
        // A camera that needs attention is put back to its defaults instead of waiting for the operator.
        // Without the camera permission there is nothing to reset, and a capture in progress is left to finish.
        val step = when {
            !cameraPermissionGranted() -> if (error) CameraRecovery.Step.SHOW else CameraRecovery.Step.NONE
            captureBusy -> CameraRecovery.Step.NONE
            else -> cameraRecovery.report(error, certain, cameraHelper.onDefaultCamera())
        }
        if (step == CameraRecovery.Step.RESET_SETTINGS || step == CameraRecovery.Step.USE_DEFAULT_CAMERA) {
            cameraReady = false
            cameraHelper.resetToDefaults(useDefaultCamera = step == CameraRecovery.Step.USE_DEFAULT_CAMERA)
            refreshCapturedPositions()
            Toast.makeText(this, R.string.camera_reset, Toast.LENGTH_SHORT).show()
        }
        cameraNeedsAttention = step == CameraRecovery.Step.SHOW
        showCameraNotice(if (cameraNeedsAttention) R.string.camera_attention else null)
        updateControls()
    }

    /** The camera box appears only when the operator has to do something; a working camera shows nothing. */
    private fun showCameraNotice(text: Int?) {
        viewBinding.captureStatus.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) viewBinding.captureStatus.setText(text)
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
        if (!cameraPermissionGranted()) { cameraPermissionLauncher.launch(Manifest.permission.CAMERA); return }
        cameraHelper.recordingBlockReason()?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        val length = CaptureLengthPreferences.read(preferences)
        clearResult()
        cancelledByOperator = false
        captureBusy = true
        orientationBeforeCapture = requestedOrientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        lastPosition = position
        preferences.edit().putString("last_capture_position", position.json().toString()).apply()
        // The countdown is idle time: use it so the frame processing code is already compiled when frames arrive.
        CaptureProcessing.warmUp()
        viewBinding.captureProgress.max = length.frames
        viewBinding.captureProgress.progress = 0
        updateControls()
        countdown = object : CountDownTimer(CaptureLength.COUNTDOWN_SECONDS * 1000L, 1000L) {
            override fun onTick(remaining: Long) {
                viewBinding.burstStatus.text = getString(R.string.capture_countdown, (remaining + 999L) / 1000L)
            }
            override fun onFinish() {
                countdown = null
                viewBinding.burstStatus.setText(R.string.capture_starting)
                viewBinding.ledOverlay.clear()
                cameraHelper.startBurst(position, length,
                    // In Inference Mode the model also runs on every captured frame, for the post-inference files.
                    // With Enable CV Algorithms the blob centroid of each LED is found in those frames too.
                    inference = if (inferenceMode && postInferenceFiles)
                        LedRecognition.frames(applicationContext, inferenceModels, SettingsActivity.cvAlgorithms(preferences)) else null,
                    progress = { count -> runOnUiThread {
                        if (!isDestroyed && captureBusy) {
                            viewBinding.burstStatus.text = getString(R.string.capture_progress, count, length.frames)
                            viewBinding.captureProgress.progress = count
                        }
                    } },
                    analyzing = { done, total -> runOnUiThread {
                        if (!isDestroyed && captureBusy) viewBinding.burstStatus.text = getString(R.string.finding_leds, done, total)
                    } },
                    completed = { summary -> runOnUiThread { if (!isDestroyed) finishFrameCapture(summary) } },
                    failed = { error -> runOnUiThread {
                        if (!isDestroyed) {
                            triggerBusy = false
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
            triggerBusy = true
            viewBinding.burstStatus.setText(R.string.saving_partial_capture)
            cameraHelper.cancelBurst(reason, restorePreview)
        }
    }

    private fun finishFrameCapture(summary: BurstSummary) {
        triggerBusy = false
        captureBusy = false
        refreshCapturedPositions()
        requestedOrientation = orientationBeforeCapture
        val fps = summary.fps?.let { String.format(java.util.Locale.getDefault(), "%.2f", it) } ?: "—"
        val savedDescription = getString(R.string.capture_saved, summary.saved, summary.length.frames)
        val leds = summary.ledsDetected?.let { found ->
            if (found.isEmpty()) getString(R.string.leds_detected_none, SessionCsv.LEDS)
            else getString(R.string.leds_detected, found.size, SessionCsv.LEDS, found.joinToString(", "))
        }
        val description = getString(R.string.capture_summary, summary.positionId, savedDescription, fps) +
            (leds?.let { "\n$it" } ?: "")
        resultDetails = description + "\n\n" + (if (summary.warnings.isEmpty()) getString(R.string.capture_no_warnings)
            else summary.warnings.joinToString("\n\n"))
        resultDetails += "\n\nSession folder: Pictures/Q-VLP/${summary.sessionId}. This position saves there automatically. A recovery copy is kept in the app."
        // Nothing is written on the result screen. Warnings and details are behind the information button.
        viewBinding.burstStatus.text = ""
        updateControls()
        // The one thing the operator is told at once: a position that does not have all of its frames.
        if (!summary.complete && !cancelledByOperator && !isFinishing) showIncompleteCapture(summary)
        cancelledByOperator = false
    }

    /** A position is missing frames, or its capture was cut short: say so, and offer to capture it again. */
    private fun showIncompleteCapture(summary: BurstSummary) {
        val position = Position(summary.positionId)
        AlertDialog.Builder(this).setTitle(getString(R.string.capture_incomplete_title, summary.positionId))
            .setMessage(listOf(getString(R.string.capture_incomplete_message, summary.pictures, summary.length.frames),
                summary.warnings.take(3).joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n\n"))
            .setPositiveButton(R.string.capture_again) { _, _ ->
                ResearchSettings.select(preferences, position)
                beginCountdown(position)
            }
            .setNegativeButton(R.string.capture_later, null).show()
    }

    /**
     * Before a session ends the operator is told what it holds: how many positions are complete, which are not,
     * and whether everything has reached Pictures/Q-VLP. Ending is never refused.
     */
    private fun askToEndSession() {
        if (endSessionAsked) return
        endSessionAsked = true
        val app = applicationContext
        SessionFiles.io.execute {
            val report = runCatching { SessionFiles.check(app) }.getOrNull()
            runOnUiThread {
                endSessionAsked = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                AlertDialog.Builder(this).setTitle(R.string.end_session_title).setMessage(endSessionMessage(report))
                    .setPositiveButton(R.string.end_session) { _, _ ->
                        SessionFiles.newSession(applicationContext)
                        sessionChanged(resetPosition = true)
                        viewBinding.burstStatus.setText(R.string.session_ended)
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
    }

    private fun endSessionMessage(report: SessionCheck.Report?): String {
        val lines = mutableListOf<String>()
        if (report != null) {
            lines.add(resources.getQuantityString(R.plurals.session_positions_complete, report.complete.size, report.complete.size))
            if (report.incomplete.isNotEmpty())
                lines.add(getString(R.string.session_positions_incomplete, report.incomplete.joinToString(", ")))
            lines.add(getString(if (report.saved) R.string.session_saved else R.string.session_not_saved))
        }
        lines.add(getString(R.string.end_session_message))
        return lines.joinToString("\n\n")
    }

    private fun clearResult() {
        resultDetails = null
        showCapturePlan()
    }

    private fun showCapturePlan() {
        val length = CaptureLengthPreferences.read(preferences)
        val label = resources.getQuantityString(R.plurals.capture_length_frames, length.frames, length.frames)
        viewBinding.burstStatus.text = getString(R.string.capture_workflow, CaptureLength.COUNTDOWN_SECONDS, label)
    }

    private fun showStorageStatus() {
        // Routine saving is silent; the box appears only when auto-save needs setting up or has failed.
        viewBinding.storageStatus.visibility = if (storageState.needsAttention) View.VISIBLE else View.GONE
        viewBinding.storageStatus.text = storageState.message
        val actionable = storageState.needsAttention && !captureBusy && !fileBusy
        viewBinding.storageStatus.isClickable = actionable
        viewBinding.storageStatus.isFocusable = actionable
    }

    private fun updateControls() {
        showStorageStatus()
        // With nothing to capture, Inference Mode shows the preview and its boxes only.
        viewBinding.buttonContainer.visibility = if (captureAvailable()) View.VISIBLE else View.GONE
        val busy = captureBusy || fileBusy
        viewBinding.titleContainer.isEnabled = !busy
        viewBinding.settingsButton.isEnabled = !busy
        viewBinding.zoomInButton.isEnabled = !busy && cameraReady && currentZoom < maximumZoom - 0.001f
        viewBinding.zoomOutButton.isEnabled = !busy && cameraReady && currentZoom > 1.001f
        // Stays available while a camera is unsupported, so the other one can be selected.
        val switchTo = cameraHelper.switchTarget()
        viewBinding.switchCameraButton.visibility = if (switchTo == null) View.GONE else View.VISIBLE
        viewBinding.switchCameraButton.isEnabled = !busy
        val toRear = switchTo == CameraChoice.Facing.REAR
        if (toRear != switchIconToRear) {
            switchIconToRear = toRear
            // The icon shows the camera a tap selects: a camera for rear, a person for front.
            viewBinding.switchCameraButton.setIconResource(
                if (toRear) R.drawable.ic_switch_to_rear else R.drawable.ic_switch_to_front)
            val label = getString(if (toRear) R.string.switch_to_rear else R.string.switch_to_front)
            viewBinding.switchCameraButton.contentDescription = label
            TooltipCompat.setTooltipText(viewBinding.switchCameraButton, label)
        }
        viewBinding.aboutButton.isEnabled = !busy
        viewBinding.sessionsButton.isEnabled = !busy
        viewBinding.storageStatus.isEnabled = !busy
        viewBinding.positionButton.isEnabled = !busy
        viewBinding.captureStatus.isEnabled = !busy
        val position = ResearchSettings.position(preferences)
        val hasPosition = ResearchSettings.hasPosition(preferences)
        viewBinding.positionButton.text = if (hasPosition) getString(R.string.position_button, position.id)
            else getString(R.string.choose_position)
        viewBinding.positionCoordinates.text = if (hasPosition) "" else getString(R.string.position_required)
        viewBinding.positionCoordinates.visibility =
            if (viewBinding.positionCoordinates.text.isEmpty()) View.GONE else View.VISIBLE
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
        viewBinding.burstStatus.visibility = if (viewBinding.burstStatus.text.isEmpty()) View.GONE else View.VISIBLE
        viewBinding.repeatCaptureButton.isEnabled = !busy && cameraReady && lastPosition != null
        val hasNext = if (hasResult && !busy) ResearchSettings.next(preferences) != null else false
        viewBinding.nextPositionButton.isEnabled = !busy && hasNext
        viewBinding.previousPositionButton.isEnabled = !busy && hasResult && ResearchSettings.previous(preferences) != null
        viewBinding.infoButton.isEnabled = !busy
        viewBinding.replacePositionButton.isEnabled = !busy
        viewBinding.endSessionButton.isEnabled = !busy
        viewBinding.captureProgress.visibility = if (captureBusy) View.VISIBLE else View.GONE
    }

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    internal companion object {
        /** The mode the session being recorded belongs to. */
        const val SESSION_MODE = "session_mode"
    }
}
