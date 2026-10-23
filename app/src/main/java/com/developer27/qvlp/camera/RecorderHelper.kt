package com.developer27.qvlp.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.widget.Toast
import com.developer27.qvlp.MainActivity
import com.developer27.qvlp.databinding.ActivityMainBinding
import java.io.File

/** Records the camera stream and publishes completed videos in the Movies collection. */
class RecorderHelper(
    private val mainActivity: MainActivity,
    private val cameraHelper: CameraHelper,
    private val viewBinding: ActivityMainBinding
) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputDisplayName: String? = null
    @Volatile
    private var recordingRequested = false
    @Volatile
    private var isRecording = false
    private var startCallback: ((String?) -> Unit)? = null

    /**
     * Starts recording. The callback receives null once MediaRecorder has really started,
     * or a user-facing error when setup fails.
     */
    @SuppressLint("MissingPermission")
    fun startRecordingVideo(callback: (String?) -> Unit) {
        if (recordingRequested || isRecording) return
        val cameraDevice = cameraHelper.cameraDevice
        if (cameraDevice == null) {
            callback("Camera is not ready yet.")
            return
        }
        val profile = cameraHelper.activeProfile
        if (profile == null) {
            callback("No supported manual capture profile is available. Open Settings or switch cameras.")
            return
        }

        recordingRequested = true
        startCallback = callback
        releaseRecorder(deleteOutput = true)

        try {
            mediaRecorder = createMediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            }

            setRecorderOrientation()
            configureOutput()

            val recordSize = profile.stream.size
            mediaRecorder?.apply {
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncodingBitRate(profile.videoBitRate)
                setVideoFrameRate(profile.fps)
                setVideoSize(recordSize.width, recordSize.height)
                prepare()
            }

            val texture = viewBinding.viewFinder.surfaceTexture
                ?: throw IllegalStateException("Camera preview is not ready.")
            cameraHelper.previewSize?.let { texture.setDefaultBufferSize(it.width, it.height) }

            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder?.surface
                ?: throw IllegalStateException("Recorder surface is unavailable.")

            cameraHelper.cameraCaptureSession?.close()
            cameraHelper.cameraCaptureSession = null
            cameraHelper.captureRequestBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(previewSurface)
                    addTarget(recorderSurface)
                }
            cameraHelper.applyCaptureSettings(submitRequest = false)

            cameraDevice.createCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!recordingRequested || cameraHelper.cameraDevice !== cameraDevice) {
                            session.close()
                            releaseRecorder(deleteOutput = true)
                            notifyStartResult("Recording was cancelled.")
                            return
                        }

                        cameraHelper.cameraCaptureSession = session
                        try {
                            val request = cameraHelper.captureRequestBuilder?.build()
                                ?: throw IllegalStateException("Camera request is unavailable.")
                            session.setRepeatingRequest(request, cameraHelper.newCaptureCallback(), cameraHelper.backgroundHandler)
                            mediaRecorder?.start()
                                ?: throw IllegalStateException("Recorder is unavailable.")
                            isRecording = true
                            notifyStartResult(null)
                        } catch (e: Exception) {
                            failStart("Failed to start recording: ${e.message ?: "unknown error"}", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        failStart("The camera rejected this recording configuration. Choose a lower resolution or frame rate in Settings.")
                    }
                },
                cameraHelper.backgroundHandler
            )
        } catch (e: Exception) {
            failStart("Cannot record video: ${e.message ?: "unknown error"}", e)
        }
    }

    /** Stops recording, finalizes the media item, and optionally restores camera preview. */
    fun stopRecordingVideo(restorePreview: Boolean = true) {
        val hadPendingRecording = recordingRequested || isRecording || mediaRecorder != null
        val savedDisplayName = outputDisplayName
        recordingRequested = false
        var savedSuccessfully = isRecording

        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (e: RuntimeException) {
                savedSuccessfully = false
                Log.e(TAG, "MediaRecorder failed while stopping", e)
            }
        }
        isRecording = false

        cameraHelper.cameraCaptureSession?.close()
        cameraHelper.cameraCaptureSession = null
        releaseRecorder(deleteOutput = !savedSuccessfully)

        if (savedSuccessfully) {
            publishOutput()
            Toast.makeText(
                mainActivity,
                "Video saved to Movies/${savedDisplayName.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
        }

        if (hadPendingRecording && startCallback != null) {
            notifyStartResult("Recording was cancelled.")
        }
        if (restorePreview && cameraHelper.cameraDevice != null) {
            cameraHelper.createCameraPreview()
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(mainActivity)
        } else {
            MediaRecorder()
        }
    }

    private fun configureOutput() {
        val fileName = "QVLP_${System.currentTimeMillis()}.mp4"
        outputDisplayName = fileName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = mainActivity.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("Cannot create a video in the Movies folder.")
            outputDescriptor = mainActivity.contentResolver.openFileDescriptor(outputUri!!, "rw")
                ?: throw IllegalStateException("Cannot open the new video file.")
            mediaRecorder?.setOutputFile(outputDescriptor!!.fileDescriptor)
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!moviesDir.exists() && !moviesDir.mkdirs()) {
                throw IllegalStateException("Cannot access the Movies folder.")
            }
            outputFile = File(moviesDir, fileName)
            mediaRecorder?.setOutputFile(outputFile!!.absolutePath)
        }
    }

    private fun publishOutput() {
        outputDescriptor?.close()
        outputDescriptor = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                mainActivity.contentResolver.update(uri, values, null, null)
            }
        }
        outputUri = null
        outputFile = null
        outputDisplayName = null
    }

    private fun failStart(message: String, cause: Exception? = null) {
        if (cause != null) Log.e(TAG, message, cause) else Log.e(TAG, message)
        recordingRequested = false
        isRecording = false
        cameraHelper.cameraCaptureSession?.close()
        cameraHelper.cameraCaptureSession = null
        releaseRecorder(deleteOutput = true)
        notifyStartResult(message)
        if (!mainActivity.isFinishing && cameraHelper.cameraDevice != null) {
            mainActivity.runOnUiThread { cameraHelper.createCameraPreview() }
        }
    }

    private fun releaseRecorder(deleteOutput: Boolean) {
        try {
            mediaRecorder?.reset()
        } catch (_: RuntimeException) {
        }
        mediaRecorder?.release()
        mediaRecorder = null
        outputDescriptor?.close()
        outputDescriptor = null

        if (deleteOutput) {
            outputUri?.let { mainActivity.contentResolver.delete(it, null, null) }
            outputFile?.let { if (it.exists()) it.delete() }
            outputUri = null
            outputFile = null
            outputDisplayName = null
        }
    }

    private fun notifyStartResult(error: String?) {
        val callback = startCallback ?: return
        startCallback = null
        mainActivity.runOnUiThread { callback(error) }
    }

    private fun setRecorderOrientation() {
        @Suppress("DEPRECATION")
        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mainActivity.display?.rotation ?: Surface.ROTATION_0
        } else {
            mainActivity.windowManager.defaultDisplay.rotation
        }
        val rotationDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sensorOrientation = cameraHelper.cameraManager
            .getCameraCharacteristics(cameraHelper.getCameraId())
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        mediaRecorder?.setOrientationHint((sensorOrientation - rotationDegrees + 360) % 360)
    }

    private companion object {
        const val TAG = "RecorderHelper"
    }
}
