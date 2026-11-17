package com.developer27.qvlp.capture

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.StatFs
import android.os.Build
import com.developer27.qvlp.camera.CameraProfile
import com.developer27.qvlp.camera.CaptureSettings
import com.developer27.qvlp.vision.LedDetectionSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal data class BurstRequestTag(val controlsTag: Any?, val burstId: String)
internal data class BurstSummary(val sessionId: String, val positionId: Int, val saved: Int, val length: CaptureLength,
    val fps: Double?, val warnings: List<String>, val interrupted: Boolean, val ledsDetected: List<Int>? = null)

internal data class FrameMetadata(val id: Int, val timestamp: Long?, val exposureNs: Long?, val iso: Int?, val details: JSONObject) {
    companion object {
        fun read(id: Int, r: CaptureResult, cameraId: String, physical: Boolean): FrameMetadata {
            val gains = r[CaptureResult.COLOR_CORRECTION_GAINS]
            val crop = r[CaptureResult.SCALER_CROP_REGION]
            return FrameMetadata(id, r[CaptureResult.SENSOR_TIMESTAMP], r[CaptureResult.SENSOR_EXPOSURE_TIME],
                r[CaptureResult.SENSOR_SENSITIVITY], JSONObject().apply {
                    put("camera_frame_number", r.frameNumber)
                    put("metadata_camera_id", cameraId)
                    put("metadata_source", if (physical) "physical_capture_result" else "capture_result")
                    put("frame_duration_ns", r[CaptureResult.SENSOR_FRAME_DURATION] ?: JSONObject.NULL)
                    // First row to one past the last row: the camera's own report of its row timing.
                    put("rolling_shutter_skew_ns", r[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW] ?: JSONObject.NULL)
                    put("focus_distance", r[CaptureResult.LENS_FOCUS_DISTANCE] ?: JSONObject.NULL)
                    put("video_stabilization_mode", r[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE] ?: JSONObject.NULL)
                    put("optical_stabilization_mode", r[CaptureResult.LENS_OPTICAL_STABILIZATION_MODE] ?: JSONObject.NULL)
                    put("scene_mode", r[CaptureResult.CONTROL_SCENE_MODE] ?: JSONObject.NULL)
                    if (Build.VERSION.SDK_INT >= 28) {
                        put("distortion_correction_mode", r[CaptureResult.DISTORTION_CORRECTION_MODE] ?: JSONObject.NULL)
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        put("active_physical_camera_id", r[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID] ?: JSONObject.NULL)
                    }
                    if (Build.VERSION.SDK_INT >= 30) put("zoom_ratio", r[CaptureResult.CONTROL_ZOOM_RATIO] ?: JSONObject.NULL)
                    if (Build.VERSION.SDK_INT >= 31) put("rotate_and_crop", r[CaptureResult.SCALER_ROTATE_AND_CROP] ?: JSONObject.NULL)
                    put("af_mode", r[CaptureResult.CONTROL_AF_MODE] ?: JSONObject.NULL)
                    put("awb_mode", r[CaptureResult.CONTROL_AWB_MODE] ?: JSONObject.NULL)
                    put("awb_locked", r[CaptureResult.CONTROL_AWB_LOCK] ?: JSONObject.NULL)
                    put("white_balance_gains", gains?.let { JSONArray((0..3).map(it::getComponent)) } ?: JSONObject.NULL)
                    put("sensor_crop", crop?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
                })
        }
    }
}

/** One bounded, asynchronous writer per burst; camera buffers are always released promptly. */
internal class BurstWriter private constructor(
    private val context: Context,
    private val location: BurstLocation,
    private val profile: CameraProfile,
    private val length: CaptureLength,
    private val position: Position,
    private val cameraInfo: JSONObject,
    private val progress: (Int) -> Unit,
    private val analyzing: (Int, Int) -> Unit,
    private val completed: (BurstSummary) -> Unit,
    private val inference: FrameInference?
) {
    val id = UUID.randomUUID().toString()
    private val accepting = AtomicBoolean(true)
    // Frames wait here for their capture result. At the start of a burst several arrive before the first
    // result is handled, so the limit is the memory budget, with enough room to ride out that backlog.
    private val slots = Semaphore(inFlightFrames(profile.stream.size.width, profile.stream.size.height))
    private val excludedTimestamps = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    // The remaining collections are accessed only on SessionFiles.io.
    private val images = mutableMapOf<Long, YuvFrame>()
    private val metadata = mutableMapOf<Long, FrameMetadata>()
    private val allMetadata = mutableMapOf<Int, FrameMetadata>()
    private val starts = mutableMapOf<Long, Int>()
    private val startTimestamps = mutableMapOf<Int, Long>()
    private val saved = sortedMapOf<Int, JSONObject>()
    private val warnings = linkedSetOf<String>()
    private val createdAt = Instant.now().toString()
    private val ledSettings = LedDetectionSettings()
    private var ledDetection: JSONObject? = null
    private var ledRecognition: JSONObject? = null
    private var rowTimeUs: Double? = null

    private fun manifest(state: String) = JSONObject().apply {
        put("burst_id", id)
        put("session_id", location.sessionId)
        put("state", state)
        put("started_at", createdAt)
        put("position", position.json())
        put("camera", cameraInfo)
        put("requested", requested())
        put("frames_started", starts.size)
        put("frames_saved", saved.size)
        put("frames", JSONArray(saved.values.toList()))
        ledDetection?.let { put("led_detection", it) }
        ledRecognition?.let { put("led_recognition", it) }
        put("warnings", JSONArray(warnings.toList()))
    }

    /** What the camera was asked for. What it actually did is recorded per frame. */
    private fun requested() = JSONObject().put("frames", length.frames)
        .put("countdown_seconds", CaptureLength.COUNTDOWN_SECONDS)
        .put("rolling_shutter_rate_hz", CaptureSettings.shutterRateHz(profile.exposureNs))
        .put("exposure_time_ns", profile.exposureNs).put("iso", profile.iso)
        .put("width", profile.stream.size.width).put("height", profile.stream.size.height)
        .put("fps", profile.fps).put("focus_mode", profile.controls.focusMode.name)
        .put("focus_value", profile.controls.focusDistance).put("white_balance_mode", profile.controls.whiteBalanceMode)

    fun onStarted(frameId: Int, timestamp: Long) {
        if (accepting.get()) SessionFiles.io.execute {
            // Replace the logical start clock with this frame's physical sensor timestamp.
            // The frame still counts once, even if the two timestamps differ.
            startTimestamps.put(frameId, timestamp)?.let(starts::remove)
            starts[timestamp] = frameId
        }
    }

    /** Discard extra in-flight frames after a repeating capture reaches its limit. */
    fun exclude(timestamp: Long) {
        excludedTimestamps.add(timestamp)
        if (accepting.get()) SessionFiles.io.execute {
            if (images.remove(timestamp) != null) slots.release()
        }
    }

    fun onMetadata(value: FrameMetadata) {
        if (!accepting.get()) return
        SessionFiles.io.execute {
            allMetadata[value.id] = value
            value.timestamp?.let { timestamp ->
                metadata[timestamp] = value
                images.remove(timestamp)?.let { writeFrame(it, value) }
            }
        }
    }

    fun warning(message: String) {
        if (accepting.get()) SessionFiles.io.execute { warnings.add(message) }
    }

    /** Must run on the same camera handler as the capture callbacks and finish(). */
    fun onImage(image: Image) {
        try {
            if (!accepting.get() || image.timestamp in excludedTimestamps) return
            if (!slots.tryAcquire()) {
                warning("Frame buffer full: one or more frames could not be saved.")
                return
            }
            val frame = try { YuvFrame.copy(image) } catch (e: Exception) {
                slots.release()
                warning("Could not read a camera frame: ${e.message}")
                return
            }
            SessionFiles.io.execute {
                if (frame.timestamp in excludedTimestamps) {
                    slots.release()
                } else if (images.containsKey(frame.timestamp)) {
                    warnings.add("Duplicate image timestamp received.")
                    slots.release()
                } else {
                    val data = metadata[frame.timestamp]
                    if (data != null) writeFrame(frame, data) else images[frame.timestamp] = frame
                }
            }
        } finally {
            image.close()
        }
    }

    private fun writeFrame(frame: YuvFrame, meta: FrameMetadata) {
        try {
            if (meta.id < 0 || meta.id >= length.frames || saved.containsKey(meta.id)) {
                warnings.add("Duplicate or unrecognized frame was excluded.")
                return
            }
            // The saved frame is a PNG, written after the capture from this temporary raw copy.
            val name = SessionLayout.frameName(meta.id)
            val raw = CaptureProcessing.rawFile(location.directory, meta.id)
            try {
                raw.outputStream().buffered().use { it.write(frame.bytes) }
            } catch (e: Exception) {
                raw.delete()
                throw e
            }
            saved[meta.id] = JSONObject().apply {
                put("frame_id", meta.id)
                put("file", name)
                put("timestamp_ns", frame.timestamp)
                put("exposure_time_ns", meta.exposureNs ?: JSONObject.NULL)
                put("iso", meta.iso ?: JSONObject.NULL)
                put("width", frame.width)
                put("height", frame.height)
                put("image_crop", JSONArray(frame.crop))
                put("source_row_strides", JSONArray(frame.rowStrides))
                put("source_pixel_strides", JSONArray(frame.pixelStrides))
                put("capture_result", meta.details)
            }
            // Journal each saved frame: a process interruption still leaves usable metadata.
            SessionFiles.writeJson(File(location.directory, "burst.json"), manifest("capturing"))
            progress(saved.size)
        } catch (e: Exception) {
            warnings.add("Storage error for frame ${meta.id}: ${e.message}")
        } finally {
            slots.release()
        }
    }

    fun finish(reason: String? = null) {
        if (!accepting.compareAndSet(true, false)) return
        SessionFiles.io.execute {
            if (reason != null) warnings.add(reason)
            // Keep images with a known request ID even if the device omitted its result.
            val firstStart = starts.keys.minOrNull()
            images.values.toList().forEach { frame ->
                val frameId = starts[frame.timestamp]
                if (frameId != null) {
                    warnings.add("Some frames have no capture metadata; unknown values are left empty.")
                    writeFrame(frame, allMetadata[frameId]
                        ?: FrameMetadata(frameId, frame.timestamp, null, null, JSONObject()))
                } else {
                    // In Inference Mode the preview reads frames too. One exposed before the capture's first
                    // frame was on its way when the capture started and was never part of it.
                    if (firstStart == null || frame.timestamp >= firstStart)
                        warnings.add("An image had no matching capture timestamp and was excluded.")
                    slots.release()
                }
            }
            images.clear()
            val expectedFrames = length.frames
            val missing = (0 until expectedFrames).filterNot(saved::containsKey)
            if (missing.isNotEmpty()) warnings.add("Missing frames: ${missing.size} of $expectedFrames (${saved.size} saved).")
            val ordered = saved.values.sortedBy { it.getLong("timestamp_ns") }
            val elapsed = if (ordered.size >= 2) ordered.last().getLong("timestamp_ns") - ordered.first().getLong("timestamp_ns") else 0L
            val fps = if (elapsed > 0) (ordered.size - 1) * 1_000_000_000.0 / elapsed else null
            val slowIntervals = ordered.zipWithNext().count { (a, b) ->
                val interval = b.getLong("timestamp_ns") - a.getLong("timestamp_ns")
                interval > 0 && 1_000_000_000.0 / interval < profile.fps * 0.9
            }
            if (fps != null && fps < profile.fps * 0.9) warnings.add(
                "Frame rate dropped more than 10%: ${String.format(java.util.Locale.US, "%.2f", fps)} fps measured; ${profile.fps} fps requested.")
            else if (slowIntervals > 0) warnings.add("Frame rate dropped more than 10% in $slowIntervals frame interval(s).")
            if (fps == null) warnings.add("Not enough frames to measure frame rate.")
            if (allMetadata.values.mapNotNull { it.exposureNs }.distinct().size > 1)
                warnings.add("Exposure changed during the burst.")
            if (allMetadata.values.mapNotNull { it.iso }.distinct().size > 1)
                warnings.add("ISO changed during the burst.")
            if (saved.values.any { it.isNull("exposure_time_ns") || it.isNull("iso") })
                warnings.add("Actual exposure or ISO is missing for some frames.")
            val physicalIds = allMetadata.values.mapNotNull {
                it.details.optString("metadata_camera_id", it.details.optString("active_physical_camera_id")).takeIf { value -> value.isNotBlank() && value != "null" }
            }.distinct()
            if (physicalIds.size > 1) warnings.add("Frame metadata came from more than one camera. Repeat the capture before using it for research.")
            var previous: Long? = null
            ordered.forEach { frame ->
                val now = frame.getLong("timestamp_ns")
                val delta = previous?.let { now - it }
                frame.put("fps", if (delta != null && delta > 0) 1_000_000_000.0 / delta else JSONObject.NULL)
                previous = now
            }
            val ledsDetected = runCatching { analyzeLeds() }.getOrElse {
                warnings.add("Frames could not be turned into pictures or analysed: ${it.message}. LED columns are left empty.")
                null
            }
            try {
                val output = manifest(if (reason == null) "complete" else "interrupted")
                    .put("finished_at", Instant.now().toString()).put("measured_fps", fps ?: JSONObject.NULL)
                    .put("saved_frame_span_s", if (ordered.size >= 2) elapsed / 1_000_000_000.0 else JSONObject.NULL)
                    .put("slow_frame_intervals", slowIntervals)
                    .put("missing_frame_ids", JSONArray(missing))
                SessionFiles.writeJson(File(location.directory, "burst.json"), output)
            } catch (e: Exception) {
                warnings.add("Could not finish session metadata: ${e.message}. Saved frame files remain available.")
            }
            if (SessionFiles.activeCapture == location.directory) SessionFiles.activeCapture = null
            SessionPublisher.captureReady(context, SessionLayout.session(location.directory), position.id)
            completed(BurstSummary(location.sessionId, position.id, saved.size, length, fps, warnings.toList(), reason != null,
                ledsDetected))
        }
    }

    /**
     * Turns the raw frames into the capture's saved files and finds the LEDs in each. Returns the LED numbers
     * detected at this position, or null if it did not run.
     */
    private fun analyzeLeds(): List<Int>? {
        if (saved.isEmpty()) return null
        val frames = saved.values.toList()
        val rowTime = CaptureProcessing.rowTime(frames)
        rowTimeUs = rowTime.microseconds
        val started = System.nanoTime()
        val result = CaptureProcessing.process(location.directory, frames, rowTime, ledSettings, ::cameraMetadata, inference, analyzing)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val recognised = if (inference == null) null else recognise(frames, result)
        // A frame whose files could not be written has no picture to point at.
        frames.filterNot { it.getInt("frame_id") in result.saved }.forEach { it.put("file", JSONObject.NULL) }
        val lost = frames.size - result.saved.size
        if (lost > 0) warnings.add("$lost of ${frames.size} frames could not be saved as pictures.")
        if (result.leds.isEmpty()) return recognised
        val fingerprint = SessionCsv.fingerprint(frames.mapNotNull { frame ->
            result.leds[frame.getInt("frame_id")]?.let { found ->
                found.leds.map { SessionCsv.LedPoint(it.detected, it.blob?.u, it.blob?.v) }
            }
        })
        val detected = fingerprint.indices.filter { fingerprint[it].detected }.map { it + 1 }
        ledDetection = JSONObject().put("settings", CaptureProcessing.json(ledSettings))
            .put("row_time_us", rowTime.microseconds ?: JSONObject.NULL).put("row_time_source", rowTime.source)
            .put("closing_rows_used", result.leds.values.first().closingRows)
            .put("frames_processed", result.leds.size).put("processing_ms", elapsedMs)
            .put("min_valid_share", SessionCsv.MIN_VALID_SHARE)
            .put("leds", JSONArray(fingerprint.mapIndexed { index, led ->
                JSONObject().put("led", index + 1).put("frequency_hz", ledSettings.ledFrequenciesHz[index])
                    .put("frames_valid", led.valid).put("detected", led.detected)
                    .put("u_px", if (led.detected) led.u else JSONObject.NULL)
                    .put("v_px", if (led.detected) led.v else JSONObject.NULL)
            }))
        // An Inference Mode capture reports what the model found: that is what its row of final.csv holds.
        if (recognised != null) return recognised
        if (rowTime.microseconds == null) warnings.add(
            "LEDs could not be identified: this camera did not report its row time.")
        else if (detected.size < 2) warnings.add("Fewer than 2 LEDs were detected at this position (${detected.size} of ${fingerprint.size}).")
        return detected
    }

    /** The LED numbers the two models found and named at this position, by the same 70% rule as final.csv. */
    private fun recognise(frames: List<JSONObject>, result: CaptureProcessing.Result): List<Int> {
        val failed = result.saved.size - result.inferred.size
        if (failed > 0) warnings.add("The LED models could not run on $failed of ${result.saved.size} frames.")
        val fingerprint = SessionCsv.fingerprint(frames.mapNotNull { frame ->
            result.inferred[frame.getInt("frame_id")]?.let(PostInferenceFiles::points)
        })
        val detected = fingerprint.indices.filter { fingerprint[it].detected }.map { it + 1 }
        ledRecognition = JSONObject()
            .put("detection_model", com.developer27.qvlp.inference.LedRecognition.DETECTION_MODEL_FILE)
            .put("recognition_model", com.developer27.qvlp.inference.LedRecognition.RECOGNITION_MODEL_FILE)
            .put("min_confidence", com.developer27.qvlp.inference.LedBoxes.MIN_CONFIDENCE.toDouble())
            .put("min_recognition_probability", com.developer27.qvlp.inference.LedTracks.MIN_PROBABILITY.toDouble())
            .put("frames_processed", result.inferred.size).put("min_valid_share", SessionCsv.MIN_VALID_SHARE)
            .put("leds", JSONArray(fingerprint.mapIndexed { index, led ->
                JSONObject().put("led", index + 1).put("frames_valid", led.valid).put("detected", led.detected)
                    .put("u_px", if (led.detected) led.u else JSONObject.NULL)
                    .put("v_px", if (led.detected) led.v else JSONObject.NULL)
            }))
        if (result.inferred.isNotEmpty() && detected.size < 2)
            warnings.add("Fewer than 2 LEDs were recognised at this position (${detected.size} of ${fingerprint.size}).")
        return detected
    }

    /**
     * The camera settings of one frame, for its file in the metadata folder. "requested" is what the operator
     * selected; "reported" is what the camera says it used for this frame, which may differ slightly.
     */
    private fun cameraMetadata(frame: JSONObject): JSONObject {
        val id = frame.getInt("frame_id")
        val exposure = if (frame.isNull("exposure_time_ns")) null else frame.getLong("exposure_time_ns")
        fun known(name: String): Any = if (frame.isNull(name)) JSONObject.NULL else frame.get(name)
        return JSONObject()
            .put("frame_id", id).put("frame_file", SessionLayout.frameName(id)).put("timestamp_ns", frame.getLong("timestamp_ns"))
            .put("camera", cameraInfo)
            .put("requested", requested())
            .put("reported", JSONObject()
                .put("rolling_shutter_rate_hz", exposure?.let { CaptureSettings.shutterRateHz(it) } ?: JSONObject.NULL)
                .put("exposure_time_ns", exposure ?: JSONObject.NULL)
                .put("iso", known("iso")).put("fps", known("fps"))
                .put("width", frame.getInt("width")).put("height", frame.getInt("height"))
                .put("row_time_us", rowTimeUs ?: JSONObject.NULL))
            .put("capture_result", known("capture_result"))
    }

    companion object {
        const val MAX_IN_FLIGHT_FRAMES = 12
        private const val IN_FLIGHT_BYTES = 32L * 1024 * 1024

        fun inFlightFrames(width: Int, height: Int): Int =
            (IN_FLIGHT_BYTES / YuvFrame.byteCount(width, height)).toInt().coerceIn(1, MAX_IN_FLIGHT_FRAMES)

        fun prepare(context: Context, profile: CameraProfile, length: CaptureLength, position: Position,
                    cameraInfo: JSONObject, progress: (Int) -> Unit, analyzing: (Int, Int) -> Unit,
                    completed: (BurstSummary) -> Unit, inference: FrameInference?, prepared: (BurstWriter?, String?) -> Unit) {
            SessionFiles.io.execute {
                try {
                    val reserveFrames = length.frames.toLong()
                    // Until its PNG is written, each frame also exists as a raw copy; a colour PNG may not compress at all.
                    val size = profile.stream.size
                    val required = Math.addExact(Math.multiplyExact(
                        YuvFrame.byteCount(size.width, size.height) + 3L * size.width * size.height, reserveFrames), 32L * 1024 * 1024)
                    // Both the recoverable private copy and public copy occupy internal storage.
                    check(StatFs(context.filesDir.absolutePath).availableBytes >= Math.multiplyExact(required, 2L)) { "Not enough free storage for the selected capture length. Choose fewer frames or a shorter duration." }
                    check(PublicSessionStorage.configured(context)) { "Tap the auto-save status on the camera screen and choose Pictures/Q-VLP before recording." }
                    val writer = BurstWriter(context.applicationContext, SessionFiles.createBurst(context, position), profile, length,
                        position, cameraInfo, progress, analyzing, completed, inference)
                    SessionFiles.writeJson(File(writer.location.directory, "burst.json"), writer.manifest("preparing"))
                    SessionFiles.activeCapture = writer.location.directory
                    prepared(writer, null)
                } catch (e: Exception) {
                    prepared(null, e.message ?: "Could not prepare session storage.")
                }
            }
        }
    }
}
