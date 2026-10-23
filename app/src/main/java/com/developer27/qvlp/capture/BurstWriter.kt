package com.developer27.qvlp.capture

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.os.StatFs
import android.os.Build
import com.developer27.qvlp.camera.CameraProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal data class BurstRequestTag(val controlsTag: Any?, val burstId: String)
internal data class BurstSummary(val sessionId: String, val positionId: Int, val saved: Int, val length: CaptureLength,
    val fps: Double?, val warnings: List<String>, val interrupted: Boolean)

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
    private val progress: (Int, Long) -> Unit,
    private val completed: (BurstSummary) -> Unit
) {
    val id = UUID.randomUUID().toString()
    private val accepting = AtomicBoolean(true)
    private val slots = Semaphore(maxOf(1, minOf(4,
        (32L * 1024 * 1024 / YuvFrame.byteCount(profile.stream.size.width, profile.stream.size.height)).toInt())))
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

    private fun manifest(state: String) = JSONObject().apply {
        put("burst_id", id)
        put("session_id", location.sessionId)
        put("state", state)
        put("started_at", createdAt)
        put("position", position.json())
        put("camera", cameraInfo)
        put("requested", JSONObject().put("frames", length.frameCount ?: JSONObject.NULL)
            .put("length_mode", length.mode.key).put("duration_seconds", length.durationSeconds ?: JSONObject.NULL)
            .put("estimated_frames", length.estimatedFrames(profile.frameDurationNs))
            .put("countdown_seconds", CaptureLength.COUNTDOWN_SECONDS)
            .put("exposure_time_ns", profile.exposureNs).put("iso", profile.iso)
            .put("width", profile.stream.size.width).put("height", profile.stream.size.height)
            .put("fps", profile.fps).put("focus_mode", profile.controls.focusMode.name)
            .put("focus_value", profile.controls.focusDistance).put("white_balance_mode", profile.controls.whiteBalanceMode))
        put("frames_started", starts.size)
        put("frames_saved", saved.size)
        put("frames", JSONArray(saved.values.toList()))
        put("warnings", JSONArray(warnings.toList()))
    }

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
                warning("Frame buffer full: one or more frames could not be saved. Lower resolution or frame rate.")
                return
            }
            val frame = try { YuvFrame.copy(image) } catch (e: Exception) {
                slots.release()
                warning("Could not read a YUV frame: ${e.message}")
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
            if (meta.id < 0 || (length.frameCount != null && meta.id >= requireNotNull(length.frameCount)) || saved.containsKey(meta.id)) {
                warnings.add("Duplicate or unrecognized frame was excluded.")
                return
            }
            val name = "frame_${meta.id.toString().padStart(5, '0')}.yuv"
            val partial = File(location.directory, "$name.part")
            try {
                partial.outputStream().buffered().use { it.write(frame.bytes) }
                check(partial.renameTo(File(location.directory, name))) { "Could not finish the frame file." }
            } catch (e: Exception) {
                partial.delete()
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
            val elapsedMs = starts.keys.minOrNull()?.let { (frame.timestamp - it).coerceAtLeast(0L) / 1_000_000L } ?: 0L
            progress(saved.size, elapsedMs)
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
            images.values.toList().forEach { frame ->
                val frameId = starts[frame.timestamp]
                if (frameId != null) {
                    warnings.add("Some frames have no capture metadata; unknown values are left empty.")
                    writeFrame(frame, allMetadata[frameId]
                        ?: FrameMetadata(frameId, frame.timestamp, null, null, JSONObject()))
                } else {
                    warnings.add("An image had no matching capture timestamp and was excluded.")
                    slots.release()
                }
            }
            images.clear()
            val expectedFrames = length.frameCount ?: starts.size
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
            try {
                writeCsv()
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
            SessionPublisher.captureReady(context, requireNotNull(location.directory.parentFile))
            completed(BurstSummary(location.sessionId, position.id, saved.size, length, fps, warnings.toList(), reason != null))
        }
    }

    private fun writeCsv() {
        val header = mutableListOf("session_id", "position_id", "rx_x_m", "rx_y_m", "frame_id", "timestamp_ns",
            "exposure_time_s", "iso", "fps", "sensor_width_px", "sensor_height_px", "row_time_us")
        for (led in 1..4) listOf("detected", "img_x_px", "img_y_px", "freq_est_hz", "stripe_period_rows",
            "blob_area_px", "peak_level", "touches_border").forEach { header.add("led${led}_$it") }
        val partial = File(location.directory, "frames.csv.part")
        partial.bufferedWriter(Charsets.UTF_8).use { csv ->
            csv.appendLine(header.joinToString(","))
            saved.values.forEach { f ->
                val exposure = if (f.isNull("exposure_time_ns")) null else java.math.BigDecimal.valueOf(f.getLong("exposure_time_ns"), 9).toPlainString()
                val fields = mutableListOf<Any?>(location.sessionId, position.id, position.x, position.y,
                    f.getInt("frame_id"), f.getLong("timestamp_ns"), exposure,
                    f.opt("iso"), f.opt("fps"), f.getInt("width"), f.getInt("height"), location.mounting.opt("row_time_us"))
                repeat(32) { fields.add(null) } // LED values are unknown until CV is implemented.
                csv.appendLine(fields.joinToString(",") { if (it == null || it == JSONObject.NULL) "" else it.toString() })
            }
        }
        check(partial.renameTo(File(location.directory, "frames.csv"))) { "Cannot finish frames.csv." }
    }

    companion object {
        fun prepare(context: Context, profile: CameraProfile, length: CaptureLength, position: Position, mounting: JSONObject,
                    cameraInfo: JSONObject, progress: (Int, Long) -> Unit, completed: (BurstSummary) -> Unit,
                    prepared: (BurstWriter?, String?) -> Unit) {
            SessionFiles.io.execute {
                try {
                    // Reserve for the fastest supported cadence in timed mode, including rounding.
                    val reserveFrames = length.estimatedFrames(profile.stream.minFrameDurationNs)
                    val required = Math.addExact(Math.multiplyExact(
                        YuvFrame.byteCount(profile.stream.size.width, profile.stream.size.height), reserveFrames), 32L * 1024 * 1024)
                    // Both the recoverable private copy and public copy occupy internal storage.
                    check(StatFs(context.filesDir.absolutePath).availableBytes >= Math.multiplyExact(required, 2L)) { "Not enough free storage for the selected capture length. Choose fewer frames or a shorter duration." }
                    check(PublicSessionStorage.configured(context)) { "Choose Pictures/Q-VLP in Sessions → Storage before recording." }
                    val writer = BurstWriter(context.applicationContext, SessionFiles.createBurst(context, position, mounting), profile, length,
                        position, cameraInfo, progress, completed)
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
