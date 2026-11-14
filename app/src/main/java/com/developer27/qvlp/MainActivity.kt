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
import com.developer27.qvlp.capture.CaptureTrigger
import com.developer27.qvlp.capture.CaptureLength
import com.developer27.qvlp.capture.CaptureProcessing
import com.developer27.qvlp.capture.CaptureLengthPreferences
import com.developer27.qvlp.capture.BurstSummary
import com.developer27.qvlp.capture.Position
import com.developer27.qvlp.capture.PositionCatalog
import com.developer27.qvlp.capture.PositionPicker
import com.developer27.qvlp.capture.PublicSessionStorage
import com.developer27.qvlp.capture.ResearchSettings
import com.developer27.qvlp.capture.SessionBrowser
import com.developer27.qvlp.capture.SessionCsv
import com.developer27.qvlp.capture.SessionFiles
import com.developer27.qvlp.capture.SessionPublisher
import com.developer27.qvlp.databinding.ActivityMainBinding
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
    private var countdown: CountDownTimer? = null
    private var orientationBeforeCapture = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastPosition: Position? = null
    private var resultDetails: String? = null
    private var storageState = SessionPublisher.State()
    private var stopObservingStorage: (() -> Unit)? = null

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
            Position(saved.getInt("position_id"), if (saved.isNull("rx_x_m")) null else saved.getDouble("rx_x_m"),
                if (saved.isNull("rx_y_m")) null else saved.getDouble("rx_y_m"), saved.optString("source", "manual"))
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
        positions = PositionPicker(this, preferences, ::setFileBusy) { imported ->
            if (imported) lastPosition = null
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
            lastPosition?.let { PositionCatalog.select(preferences, it); beginCountdown(it) }
        }
        viewBinding.infoButton.setOnClickListener { showInformation() }
        TooltipCompat.setTooltipText(viewBinding.infoButton, getString(R.string.information))
        viewBinding.previousPositionButton.setOnClickListener {
            PositionCatalog.previous(preferences)?.let(::selectForCapture)
        }
        viewBinding.replacePositionButton.setOnClickListener {
            positions.replace(capturedPositions, lastPosition?.id, ::selectForCapture)
        }
        viewBinding.endSessionButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.end_session_title).setMessage(R.string.end_session_message)
                .setPositiveButton(R.string.end_session) { _, _ ->
                    SessionFiles.newSession(applicationContext)
                    sessionChanged(resetPosition = true)
                    viewBinding.burstStatus.setText(R.string.session_ended)
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        viewBinding.nextPositionButton.setOnClickListener {
            PositionCatalog.next(preferences)?.let {
                PositionCatalog.select(preferences, it)
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
        openCameraWhenReady()
        updateControls()
        if (!captureBusy && !fileBusy) sessions.resumeSaving()
        refreshCapturedPositions()
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
        if (!supported || !resumed || !hasWindowFocus()) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (captureTrigger.press(event.deviceId, event.keyCode, event.repeatCount)) triggerCapture()
            KeyEvent.ACTION_UP -> captureTrigger.release(event.deviceId, event.keyCode)
        }
        return true
    }

    private fun triggerCapture() {
        if (fileBusy || triggerBusy) return
        if (captureBusy) { cancelFrameCapture(); return }
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
        val sections = listOf(
            getString(R.string.info_camera) to listOf(camera, cameraDetails).filter { it.isNotBlank() }.joinToString("\n"),
            getString(R.string.info_saving) to storageState.message,
            getString(R.string.info_last_capture) to (resultDetails ?: getString(R.string.info_no_capture))
        )
        AlertDialog.Builder(this).setTitle(R.string.information)
            .setMessage(sections.joinToString("\n\n") { (title, text) -> "${title.uppercase()}\n$text" })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    /** A session ended or another became current: the last result and position no longer apply. */
    private fun sessionChanged(resetPosition: Boolean) {
        clearResult()
        lastPosition = null
        preferences.edit().remove("last_capture_position").apply()
        if (resetPosition) {
            val first = PositionCatalog.read(preferences).firstOrNull()
            if (first != null) PositionCatalog.select(preferences, first) else PositionCatalog.clear(preferences)
        }
        refreshCapturedPositions()
        updateControls()
    }

    /** Going back to a position: the next capture there replaces the earlier one in final.csv. */
    private fun selectForCapture(position: Position) {
        PositionCatalog.select(preferences, position)
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
    internal fun showCameraState(ready: Boolean, details: String, error: Boolean = false) {
        if (isDestroyed) return
        cameraReady = ready
        cameraDetails = details
        cameraNeedsAttention = error
        showCameraNotice(if (error) R.string.camera_attention else null)
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
                cameraHelper.startBurst(position, length,
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
        // Coordinates are shown only when an imported survey supplies them.
        viewBinding.positionCoordinates.text = when {
            !hasPosition -> getString(R.string.position_required)
            position.x != null && position.y != null ->
                getString(R.string.position_coordinates, position.x.toString(), position.y.toString())
            else -> ""
        }
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
        val hasNext = if (hasResult && !busy) PositionCatalog.next(preferences) != null else false
        viewBinding.nextPositionButton.isEnabled = !busy && hasNext
        viewBinding.nextPositionButton.setText(if (hasNext) R.string.next_position else R.string.last_position)
        viewBinding.previousPositionButton.isEnabled = !busy && hasResult && PositionCatalog.previous(preferences) != null
        viewBinding.infoButton.isEnabled = !busy
        viewBinding.replacePositionButton.isEnabled = !busy
        viewBinding.endSessionButton.isEnabled = !busy
        viewBinding.captureProgress.visibility = if (captureBusy) View.VISIBLE else View.GONE
    }

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
