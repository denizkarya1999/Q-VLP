package com.developer27.qvlp.capture

import com.developer27.qvlp.inference.LedDetection
import com.developer27.qvlp.vision.FrameLeds
import com.developer27.qvlp.vision.FrameOverlay
import com.developer27.qvlp.vision.FramePng
import com.developer27.qvlp.vision.LedDetectionSettings
import com.developer27.qvlp.vision.LedDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Turns the raw frames of one finished capture into its saved files. Called on SessionFiles.io. */
internal object CaptureProcessing {
    /** The time between two image rows, which turns a stripe spacing into a blink frequency. */
    data class RowTime(val microseconds: Double?, val source: String) {
        val seconds: Double? get() = microseconds?.let { it / 1_000_000.0 }
    }

    /**
     * [leds] holds the frames whose detection ran; [saved] the frame IDs whose files were all written;
     * [inferred] the LEDs the two models found and named in each frame they ran on (Inference Mode captures only).
     */
    data class Result(val leds: Map<Int, FrameLeds>, val saved: Set<Int>, val inferred: Map<Int, List<LedDetection>> = emptyMap())

    private class Frame(val id: Int, val leds: FrameLeds, val inferred: List<LedDetection>?)

    /** The camera's own report: its rolling-shutter skew spread over the rows of the frame. */
    fun rowTime(frames: Collection<JSONObject>): RowTime {
        val reported = frames.mapNotNull { frame ->
            val skew = frame.optJSONObject("capture_result")?.optLong("rolling_shutter_skew_ns", 0L) ?: 0L
            val rows = frame.optInt("height")
            if (skew > 0L && rows > 0) skew / 1000.0 / rows else null
        }.sorted()
        return if (reported.isEmpty()) RowTime(null, "unknown")
            else RowTime(reported[reported.size / 2], "device_reported_rolling_shutter_skew")
    }

    /**
     * During capture a frame is written raw, because only that keeps up with the camera. This is not a saved
     * format: the name ends in ".part", so it is never published or exported, and it is deleted once the
     * frame's pictures exist.
     */
    fun rawFile(capture: File, frameId: Int) =
        File(File(capture, SessionLayout.FRAMES), SessionLayout.frameName(frameId).substringBeforeLast('.') + ".yuv.part")

    /**
     * Writes every file of the capture. Each frame is handled once, on its own core: LED detection, the colour
     * picture, the annotated picture and both metadata files. The mapping files are written last, so their
     * presence means the capture is complete.
     *
     * With [inference], the two LED models also run on every frame and the capture gets its post-inference
     * files: each frame with the models' boxes, and what they found in it. The models are given the frames one
     * at a time in the order they were taken, because an LED is named from its last five frames.
     */
    fun process(capture: File, frames: List<JSONObject>, rowTime: RowTime, settings: LedDetectionSettings,
                cameraMetadata: (JSONObject) -> JSONObject, inference: FrameInference? = null,
                progress: (Int, Int) -> Unit): Result {
        if (frames.isEmpty()) return Result(emptyMap(), emptySet())
        val processed = SessionLayout.processedFolder(capture)
        val folders = listOf(File(capture, SessionLayout.FRAMES), File(capture, SessionLayout.METADATA),
            File(processed, SessionLayout.PROCESSED_FRAMES), File(processed, SessionLayout.PROCESSED_METADATA))
        val inferenceFolder = SessionLayout.inferenceFolder(capture)
        val inferenceFolders = if (inference == null) emptyList() else listOf(
            File(inferenceFolder, SessionLayout.INFERENCE_FRAMES), File(inferenceFolder, SessionLayout.INFERENCE_METADATA))
        (folders + inferenceFolders).forEach { check(it.isDirectory || it.mkdirs()) { "Cannot create ${it.name}." } }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS))
        val workspace = ThreadLocal.withInitial { Workspace(settings) }
        val saved = ConcurrentHashMap.newKeySet<Int>()
        val done = AtomicInteger()
        try {
            // Started in the order the frames were taken, so the frame whose turn it is at the models is always under way.
            val ordered = frames.sortedBy { it.getInt("frame_id") }
            val order = if (inference == null) null else InOrder(ordered.size)
            val leds = ordered.mapIndexed { index, frame ->
                pool.submit(Callable {
                  try {
                    val id = frame.getInt("frame_id")
                    val width = frame.getInt("width")
                    val height = frame.getInt("height")
                    val space = workspace.get()
                    val raw = rawFile(capture, id)
                    val pixels = space.pixels(FramePng.i420Size(width, height))
                    DataInputStream(raw.inputStream()).use { it.readFully(pixels, 0, FramePng.i420Size(width, height)) }
                    // Detection reads the luminance plane, which comes first in the raw frame.
                    val found = space.detector.detect(pixels, width, height, rowTime.seconds)
                    val rgb = space.rgb(width * height * 3)
                    FramePng.toRgb(pixels, width, height, rgb)
                    val picture = FramePng.encode(rgb, width, height)
                    // The model sees the frame before anything is drawn on it. A frame it cannot run on is still saved.
                    val inferred = inference?.let { model -> order!!.turn(index) { runCatching { model.run(rgb, width, height) }.getOrNull() } }
                    // A frame with nothing found looks the same processed, so its picture is reused as it is.
                    val annotated = if (FrameOverlay.draw(rgb, width, height, found)) FramePng.encode(rgb, width, height) else picture
                    write(File(folders[0], SessionLayout.frameName(id)), picture)
                    write(File(folders[1], SessionLayout.metadataName(id)), cameraMetadata(frame).toString(2).toByteArray())
                    write(File(folders[2], SessionLayout.frameName(id)), annotated)
                    write(File(folders[3], SessionLayout.metadataName(id)), processedMetadata(id, found, rowTime).toString(2).toByteArray())
                    if (inferred != null) {
                        write(File(inferenceFolders[0], SessionLayout.frameName(id)), inferred.picture)
                        write(File(inferenceFolders[1], SessionLayout.metadataName(id)),
                            PostInferenceFiles.metadata(id, inferred, inference!!.models).toString(2).toByteArray())
                    }
                    saved.add(id)
                    raw.delete()
                    progress(done.incrementAndGet(), frames.size)
                    Frame(id, found, inferred?.detections)
                  } finally {
                    // A frame that failed before its turn must not keep the later ones waiting.
                    order?.pass(index)
                  }
                })
            }.mapNotNull { task -> runCatching { task.get() }.getOrNull() }
            val ids = frames.map { it.getInt("frame_id") }.filter { it in saved }.sorted()
            write(File(capture, SessionLayout.MAPPING), SessionLayout.mapping(ids).toByteArray())
            write(File(processed, SessionLayout.PROCESSED_MAPPING), SessionLayout.mapping(ids).toByteArray())
            val inferred = leds.filter { it.inferred != null && it.id in saved }.associate { it.id to it.inferred!! }
            if (inference != null) write(File(inferenceFolder, SessionLayout.INFERENCE_MAPPING),
                SessionLayout.mapping(inferred.keys.sorted()).toByteArray())
            return Result(leds.associate { it.id to it.leds }, saved, inferred)
        } finally {
            pool.shutdown()
        }
    }

    /**
     * Runs the whole per-frame path a few times on a small made-up frame. Android compiles code only once it
     * has run for a while, so without this the first frames of the first capture are processed several times
     * slower than the rest. Called when a countdown starts; it has finished long before the frames arrive.
     */
    fun warmUp() {
        if (!warmed.compareAndSet(false, true)) return
        Thread({
            runCatching {
                val width = 320
                val height = 240
                val frame = ByteArray(FramePng.i420Size(width, height)) { 110 }
                // A striped bright disc, so detection, the stripe fit, colour conversion and drawing all run.
                for (y in 0 until height) for (x in 0 until width) {
                    val inside = (x - 160) * (x - 160) + (y - 120) * (y - 120) < 60 * 60
                    frame[y * width + x] = (if (inside) (if (y % 12 < 6) 210 else 90) else 4).toByte()
                }
                val detector = LedDetector(LedDetectionSettings())
                val rgb = ByteArray(width * height * 3)
                repeat(WARM_UP_RUNS) {
                    val found = detector.detect(frame, width, height, 30e-6)
                    detector.detectWhole(frame, width, height, null)
                    FramePng.toRgb(frame, width, height, rgb)
                    FramePng.encode(rgb, width, height)
                    FrameOverlay.draw(rgb, width, height, found)
                    processedMetadata(0, found, RowTime(30.0, "warm_up")).toString(2)
                }
            }
        }, "QvlpWarmUp").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private val warmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val WARM_UP_RUNS = 60

    /**
     * Lets the frames of a capture through to the LED models one at a time and in the order they were taken,
     * while everything else about them is still done side by side.
     */
    private class InOrder(count: Int) {
        private val passed = BooleanArray(count)
        private var next = 0
        private val lock = ReentrantLock()
        private val moved = lock.newCondition()

        /** Waits until every earlier frame has passed, then runs [work]. */
        fun <T> turn(index: Int, work: () -> T): T {
            lock.withLock { while (next != index) moved.await() }
            try {
                return work()
            } finally {
                pass(index)
            }
        }

        /** Frame [index] has been to the models, or never will: the frames after it need not wait for it. */
        fun pass(index: Int) = lock.withLock {
            passed[index] = true
            while (next < passed.size && passed[next]) next++
            moved.signalAll()
        }
    }

    /** Buffers one worker reuses from frame to frame. */
    private class Workspace(settings: LedDetectionSettings) {
        val detector = LedDetector(settings)
        private var pixels = ByteArray(0)
        private var rgb = ByteArray(0)
        fun pixels(size: Int): ByteArray { if (pixels.size < size) pixels = ByteArray(size); return pixels }
        fun rgb(size: Int): ByteArray { if (rgb.size < size) rgb = ByteArray(size); return rgb }
    }

    /** A reader never sees a half-written file: it appears under its name only once complete. */
    private fun write(file: File, bytes: ByteArray) {
        val partial = File(file.parentFile, "${file.name}.part")
        try {
            partial.outputStream().use { it.write(bytes) }
            check(partial.renameTo(file)) { "Cannot finish ${file.name}." }
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    /**
     * What LED detection found in one frame. u is the column and v the row of the blob centre, in pixels from
     * the centre of the top-left pixel. A rejected or missing LED has no coordinates, never zeros.
     */
    fun processedMetadata(frameId: Int, found: FrameLeds, rowTime: RowTime): JSONObject {
        val data = JSONObject().put("frame_id", frameId).put("frame_file", SessionLayout.frameName(frameId))
        found.leds.forEachIndexed { index, reading ->
            val blob = reading.blob.takeIf { reading.detected }
            data.put("LED_${index + 1}_u_px", blob?.let { rounded(it.u) } ?: JSONObject.NULL)
            data.put("LED_${index + 1}_v_px", blob?.let { rounded(it.v) } ?: JSONObject.NULL)
            data.put("LED_${index + 1}_detected", if (reading.detected) 1 else 0)
        }
        return data.put("details", JSONObject().put("blobs_found", found.blobs.size)
            .put("background_level", found.background).put("closing_rows", found.closingRows)
            .put("row_time_us", rowTime.microseconds ?: JSONObject.NULL).put("row_time_source", rowTime.source)
            .put("leds", JSONArray(found.leds.mapIndexed { index, reading ->
                val blob = reading.blob
                JSONObject().put("led", index + 1).put("detected", reading.detected).apply {
                    if (blob != null) put("freq_est_hz", blob.frequencyHz?.let(::rounded) ?: JSONObject.NULL)
                        .put("stripe_period_rows", blob.stripe?.let { rounded(it.periodRows) } ?: JSONObject.NULL)
                        .put("stripe_fit_snr_db", blob.stripe?.let { rounded(it.snrDb) } ?: JSONObject.NULL)
                        .put("blob_area_px", blob.areaPx).put("peak_level", blob.peakLevel)
                        .put("touches_border", blob.touchesBorder).put("saturated", blob.saturated)
                        .put("roughly_round", blob.roughlyRound)
                }
            })))
    }

    fun json(settings: LedDetectionSettings): JSONObject = JSONObject()
        .put("led_frequencies_hz", JSONArray(settings.ledFrequenciesHz))
        .put("background_sample_step", settings.backgroundSampleStep)
        .put("smoothing_sigma_px", settings.smoothingSigmaPx).put("closing_rows", settings.closingRows)
        .put("threshold_above_background", settings.thresholdAboveBackground)
        .put("min_blob_area_px", settings.minBlobAreaPx).put("border_margin_px", settings.borderMarginPx)
        .put("saturation_level", settings.saturationLevel).put("min_profile_rows", settings.minProfileRows)
        .put("min_stripe_periods", settings.minStripePeriods).put("min_fit_snr_db", settings.minFitSnrDb)
        .put("frequency_tolerance", settings.frequencyTolerance)
        .put("max_box_aspect", settings.maxBoxAspect).put("min_box_fill", settings.minBoxFill)

    fun number(value: Double?, decimals: Int = 3): String =
        if (value == null || !value.isFinite()) "" else String.format(Locale.US, "%.${decimals}f", value)

    private fun rounded(value: Double): Double = Math.round(value * 1000.0) / 1000.0

    // Every frame is independent, so more cores finish sooner; the cap bounds the memory the workers hold.
    private const val MAX_THREADS = 8
}
