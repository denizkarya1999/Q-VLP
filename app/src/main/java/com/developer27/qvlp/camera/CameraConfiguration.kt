package com.developer27.qvlp.camera

import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import kotlin.math.abs

internal data class StreamOption(
    val size: Size,
    val minFrameDurationNs: Long,
    val frameRates: List<Int>,
    val videoFrameRates: List<Int>
) {
    val key: String get() = "${size.width}x${size.height}"
    val label: String get() = "${size.width} × ${size.height}"
    fun frameDurationNs(fps: Int): Long =
        maxOf(minFrameDurationNs, CaptureSettings.frameDurationNanos(fps))
}

internal data class CameraProfile(
    val exposureNs: Long,
    val iso: Int,
    val stream: StreamOption,
    val fps: Int,
    val controls: FocusWhiteBalanceProfile
) {
    val frameDurationNs: Long get() = stream.frameDurationNs(fps)
    val videoBitRate: Int get() = videoBitRate(stream.size, fps)
}

private fun videoBitRate(size: Size, fps: Int): Int =
    (size.width.toLong() * size.height * fps * 15 / 100)
        .coerceIn(2_000_000L, 60_000_000L).toInt()

/** Preview/YUV capabilities; MP4 support is tracked separately from research capture. */
internal data class CameraCapabilities(
    val cameraId: String,
    val exposureRangeNs: Range<Long>,
    val isoRange: Range<Int>,
    val streams: List<StreamOption>,
    val sensorAspectRatio: Double,
    val controls: FocusWhiteBalanceCapabilities
) {
    fun maximumExposureNs(stream: StreamOption, fps: Int): Long =
        minOf(exposureRangeNs.upper, stream.frameDurationNs(fps))

    fun defaultFrameRate(stream: StreamOption): Int =
        stream.frameRates.minBy { abs(it - CaptureSettings.DEFAULT_FPS) }

    fun defaults(): CameraProfile {
        val fastStreams = streams.filter { CaptureSettings.DEFAULT_FPS in it.frameRates }
            .ifEmpty { streams }
        val stream = fastStreams.firstOrNull { it.size == Size(1280, 960) }
            ?: fastStreams.minWith(compareBy<StreamOption> {
                // Prefer the native sensor aspect ratio, then a modest capture size.
                if (abs(it.size.width.toDouble() / it.size.height - sensorAspectRatio) < 0.02) 0 else 1
            }.thenBy { abs(it.size.width.toLong() * it.size.height - 1280L * 960) })
        val fps = defaultFrameRate(stream)
        return CameraProfile(
            CaptureSettings.DEFAULT_EXPOSURE_NS.coerceIn(
                exposureRangeNs.lower, maximumExposureNs(stream, fps)
            ), isoRange.lower, stream, fps, controls.defaults()
        )
    }

    companion object {
        fun read(cameraId: String, characteristics: CameraCharacteristics): CameraCapabilities {
            val manual = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            val requiredKeys = listOf(CaptureRequest.SENSOR_EXPOSURE_TIME,
                CaptureRequest.SENSOR_SENSITIVITY, CaptureRequest.SENSOR_FRAME_DURATION)
            require(manual && characteristics.availableCaptureRequestKeys.containsAll(requiredKeys)) {
                "This camera does not support manual exposure, ISO and frame duration. Switch cameras to use Q-VLP capture."
            }
            val exposure = requireNotNull(characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]) {
                "This camera does not report its exposure range."
            }
            val iso = requireNotNull(characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]) {
                "This camera does not report its ISO range."
            }
            val maxDuration = requireNotNull(characteristics[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION]) {
                "This camera does not report its frame-duration limit."
            }
            val map = requireNotNull(characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]) {
                "This camera does not report its output sizes."
            }
            val previews = map.getOutputSizes(SurfaceTexture::class.java)?.toSet().orEmpty()
            val videos = map.getOutputSizes(MediaRecorder::class.java).orEmpty()
            val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            // Use normal-session AE ranges only as a conservative ceiling for the menu.
            // The manual request itself controls timing through SENSOR_FRAME_DURATION.
            val normalMaxFps = characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                ?.maxOfOrNull { it.upper } ?: 30
            val candidateRates = listOf(1, 5, 10, 15, 24, 25, 30, 50, 60)
            val streams = yuvSizes.filter { it in previews }.mapNotNull { size ->
                val previewMin = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                val yuvMin = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size)
                // A zero duration is unknown, not evidence that an arbitrary rate is supported.
                if (previewMin <= 0L || yuvMin <= 0L) return@mapNotNull null
                val minDuration = maxOf(previewMin, yuvMin)
                val rates = candidateRates.filter { fps ->
                    val requestedDuration = CaptureSettings.frameDurationNanos(fps)
                    val duration = maxOf(requestedDuration, minDuration)
                    fps <= normalMaxFps && minDuration <= requestedDuration * 1.001 &&
                        duration <= maxDuration && exposure.lower <= duration
                }
                val videoMin = if (size in videos) map.getOutputMinFrameDuration(MediaRecorder::class.java, size) else 0L
                val videoRates = rates.filter { videoMin > 0L &&
                    videoMin <= CaptureSettings.frameDurationNanos(it) * 1.001 && supportsEncoding(codecs, size, it) }
                if (rates.isEmpty()) null else StreamOption(size, minDuration, rates, videoRates)
            }.distinctBy { it.key }.sortedByDescending { it.size.width.toLong() * it.size.height }
            require(streams.isNotEmpty()) {
                "No compatible preview/YUV configuration was reported by this camera. Try another camera."
            }
            val sensor = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            val aspect = sensor?.let { it.width().toDouble() / it.height() } ?: 4.0 / 3.0
            return CameraCapabilities(cameraId, exposure, iso, streams, aspect,
                FocusWhiteBalanceCapabilities.read(characteristics))
        }

        private fun supportsEncoding(codecs: MediaCodecList, size: Size, fps: Int): Boolean =
            runCatching {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate(size, fps))
                }
                codecs.findEncoderForFormat(format) != null
            }.getOrDefault(false)
    }
}

/** Each camera keeps its own profile; resolution, cadence and exposure are saved together. */
internal class CameraSettingsStore(private val preferences: SharedPreferences, private val cameraId: String) {
    private fun key(name: String) = "camera_${cameraId}_$name"

    fun load(capabilities: CameraCapabilities): CameraProfile {
        val defaults = capabilities.defaults()
        val stream = capabilities.streams.firstOrNull { it.key == preferences.getString(key("resolution"), null) }
            ?: defaults.stream
        val fps = preferences.getInt(key("fps"), capabilities.defaultFrameRate(stream))
            .takeIf { it in stream.frameRates } ?: capabilities.defaultFrameRate(stream)
        val firstProfile = !preferences.contains(key("exposure_ns"))
        // Import existing manual choices once. New installations start with the Q-VLP defaults.
        val importLegacy = firstProfile && !preferences.getBoolean("camera_settings_migrated", false) &&
            preferences.getBoolean("manual_iso_enabled", true)
        val oldExposure = if (importLegacy && preferences.contains("shutter_speed"))
            CaptureSettings.exposureTimeNanos(preferences.getString("shutter_speed", null)) else null
        val oldIso = if (importLegacy) CaptureSettings.normalizeIso(preferences.getString("iso_value", null)) else null
        val exposure = preferences.getLong(key("exposure_ns"), oldExposure ?: defaults.exposureNs)
            .coerceIn(capabilities.exposureRangeNs.lower, capabilities.maximumExposureNs(stream, fps))
        val iso = preferences.getInt(key("iso"), oldIso ?: defaults.iso).coerceIn(capabilities.isoRange.lower, capabilities.isoRange.upper)
        val focusMode = capabilities.controls.focusModes.firstOrNull {
            it.name == preferences.getString(key("focus_mode"), null)
        } ?: defaults.controls.focusMode
        val distance = preferences.getFloat(key("focus_distance"), defaults.controls.focusDistance)
            .takeIf { it.isFinite() }?.coerceIn(0f, capabilities.controls.maxFocusDistance)
            ?: defaults.controls.focusDistance
        val wbMode = preferences.getInt(key("white_balance_mode"), defaults.controls.whiteBalanceMode)
            .takeIf { mode -> capabilities.controls.whiteBalanceOptions.any { it.mode == mode } }
            ?: defaults.controls.whiteBalanceMode
        return CameraProfile(exposure, iso, stream, fps,
            FocusWhiteBalanceProfile(focusMode, distance, wbMode)).also { save(it) }
    }

    fun save(profile: CameraProfile) {
        preferences.edit()
            .putLong(key("exposure_ns"), profile.exposureNs)
            .putInt(key("iso"), profile.iso)
            .putString(key("resolution"), profile.stream.key)
            .putInt(key("fps"), profile.fps)
            .putString(key("focus_mode"), profile.controls.focusMode.name)
            .putFloat(key("focus_distance"), profile.controls.focusDistance)
            .putInt(key("white_balance_mode"), profile.controls.whiteBalanceMode)
            .putBoolean("camera_settings_migrated", true)
            .remove("shutter_speed").remove("iso_value").remove("manual_iso_enabled")
            .apply()
    }
}
