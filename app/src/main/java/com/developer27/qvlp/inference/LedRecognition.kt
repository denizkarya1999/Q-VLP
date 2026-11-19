package com.developer27.qvlp.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import com.developer27.qvlp.capture.FrameInference
import com.developer27.qvlp.capture.InferredFrame
import com.developer27.qvlp.vision.FramePng
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.PyTorchAndroid
import org.pytorch.Tensor
import java.nio.FloatBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The two models of Inference Mode, run one after the other on every frame.
 *
 * First the Physical LED Detection Model, a YOLO26 network, finds the LEDs in the frame: a box round each, with
 * no name. Then the OOK-Based LED Recognition Model, a ResNet-50 network, names each of them LED 1 to LED 4
 * from the on-off keying signal it sends, looking at the last five frames of that LED cut out along its box
 * (see [LedTracks] and [LedCrop]). Inference Mode, its post-inference files and Local File Inference all use
 * this one copy of the models. Each of them says which models are to run (see [InferenceModels]): without the
 * recognition model the LEDs are found and stay without a name, and without the detection model nothing is
 * looked for. A model that never runs is never loaded.
 *
 * The model files are the TorchScript exports of the two training notebooks and are loaded as they are. They
 * run on one thread of their own: [execute] and [frames] may be called from anywhere, everything else only
 * from inside [execute].
 */
internal object LedRecognition {
    const val DETECTION_MODEL_FILE = "qvlp_physical_led_detection_model.pt"
    const val DETECTION_MODEL_NAME = "Physical LED Detection Model (YOLO26n)"
    const val RECOGNITION_MODEL_FILE = "qvlp_ook_led_recognition_model.pt"
    const val RECOGNITION_MODEL_NAME = "OOK-Based LED Recognition Model (ResNet-50)"

    /** The LEDs found in one picture, with boxes in that picture's own pixels, and how long finding and naming them took. */
    data class Outcome(val detections: List<LedDetection>, val milliseconds: Long)

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "QvlpLedModel") }
    private var detector: Module? = null
    private var recogniser: Module? = null
    private val inputBuffer by lazy { Tensor.allocateFloatBuffer(LedInput.SIZE) }
    private val inputTensor by lazy {
        Tensor.fromBlob(inputBuffer, longArrayOf(1, 3, LedInput.HEIGHT.toLong(), LedInput.WIDTH.toLong()))
    }
    private val input = FloatArray(LedInput.SIZE)
    // One buffer for each number of LEDs named in one run: a tensor must fill its buffer exactly.
    private val stripBuffers = arrayOfNulls<FloatBuffer>(LedBoxes.LEDS)
    private val strips = FloatArray(LedBoxes.LEDS * LedCrop.STRIP_SIZE)
    private var rgb = ByteArray(0)
    private var pixels = IntArray(0)
    private val fitted by lazy { Bitmap.createBitmap(LedInput.WIDTH, LedInput.HEIGHT, Bitmap.Config.ARGB_8888) }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Runs [work] on the models' thread. */
    fun execute(work: () -> Unit) = worker.execute(work)

    /**
     * For a capture's post-inference files: finds and names the LEDs in each frame and draws them, on the
     * models' thread. The frames of the capture must be given in the order they were taken, because an LED is
     * named from its last five frames. The caller waits for the answer, so this must not be used from inside
     * [execute].
     */
    fun frames(context: Context, selected: InferenceModels = InferenceModels.BOTH): FrameInference {
        val app = context.applicationContext
        val tracks = LedTracks()
        return object : FrameInference {
            override val models = selected
            override fun run(rgb: ByteArray, width: Int, height: Int): InferredFrame =
                worker.submit(Callable { annotate(app, rgb, width, height, tracks, selected) }).get()
        }
    }

    /** A camera frame as tightly packed I420 (see YuvFrame). [tracks] follows the frames this one belongs to. */
    fun detectFrame(context: Context, i420: ByteArray, width: Int, height: Int, tracks: LedTracks,
                    models: InferenceModels = InferenceModels.BOTH): Outcome {
        val started = SystemClock.elapsedRealtime()
        if (!models.detection) return Outcome(emptyList(), 0L)
        if (rgb.size < width * height * 3) rgb = ByteArray(width * height * 3)
        // The same colour conversion that wrote the training frames.
        FramePng.toRgb(i420, width, height, rgb)
        return Outcome(detectRgb(context, rgb, width, height, tracks, models), SystemClock.elapsedRealtime() - started)
    }

    /**
     * A picture of any size, for example one opened from a file. On its own it is one frame, and each LED is
     * named from five copies of it; with [tracks] it is the next frame of a series.
     */
    fun detectPicture(context: Context, picture: Bitmap, tracks: LedTracks = LedTracks(),
                      models: InferenceModels = InferenceModels.BOTH): Outcome {
        val started = SystemClock.elapsedRealtime()
        if (!models.detection) return Outcome(emptyList(), 0L)
        load(context, models)
        return Outcome(detect(picture, tracks, models), SystemClock.elapsedRealtime() - started)
    }

    /** Loads the models that are to run and are not loaded yet. The first call takes a few seconds. */
    fun load(context: Context, models: InferenceModels = InferenceModels.BOTH) {
        val detectorMissing = models.detection && detector == null
        val recogniserMissing = models.recognition && recogniser == null
        if (!detectorMissing && !recogniserMissing) return
        PyTorchAndroid.setNumThreads(THREADS)
        val assets = context.applicationContext.assets
        if (detectorMissing) detector = PyTorchAndroid.loadModuleFromAsset(assets, DETECTION_MODEL_FILE)
        if (recogniserMissing) recogniser = PyTorchAndroid.loadModuleFromAsset(assets, RECOGNITION_MODEL_FILE)
    }

    /** [frame] holds [width] × [height] pixels as red, green, blue bytes. */
    private fun detectRgb(context: Context, frame: ByteArray, width: Int, height: Int, tracks: LedTracks,
                          models: InferenceModels): List<LedDetection> {
        if (!models.detection) return emptyList()
        load(context, models)
        if (width == LedInput.WIDTH && height == LedInput.HEIGHT) {
            LedInput.fromRgb(frame, input)
            return named(findLeds(), tracks, models) { box -> LedCrop.fromRgb(frame, width, LedCrop.region(box, width, height)) }
        }
        val picture = bitmap(frame, width, height)
        try {
            return detect(picture, tracks, models)
        } finally {
            picture.recycle()
        }
    }

    /**
     * The LEDs the detection model [found], with their names when the recognition model is to run. Without it
     * they stay as they were found: no name, the most confidently detected first.
     */
    private fun named(found: List<LedDetection>, tracks: LedTracks, models: InferenceModels,
                      crop: (LedDetection) -> ByteArray): List<LedDetection> =
        if (models.recognition) tracks.update(found, crop, ::recognise) else found

    /** The frame as a PNG with the LEDs the models found drawn on it, exactly as Inference Mode shows them. */
    private fun annotate(context: Context, frame: ByteArray, width: Int, height: Int, tracks: LedTracks,
                         models: InferenceModels): InferredFrame {
        val started = SystemClock.elapsedRealtime()
        val found = detectRgb(context, frame, width, height, tracks, models)
        val milliseconds = SystemClock.elapsedRealtime() - started
        val picture = bitmap(frame, width, height)
        try {
            val painter = LedBoxPainter.forPicture(width)
            val canvas = Canvas(picture)
            found.forEach { painter.draw(canvas, it.left, it.top, it.right, it.bottom, it) }
            picture.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            picture.recycle()
        }
        val drawn = ByteArray(width * height * 3)
        var to = 0
        for (pixel in 0 until width * height) {
            val colour = pixels[pixel]
            drawn[to] = (colour shr 16).toByte(); drawn[to + 1] = (colour shr 8).toByte(); drawn[to + 2] = colour.toByte()
            to += 3
        }
        return InferredFrame(FramePng.encode(drawn, width, height), found, milliseconds)
    }

    private fun bitmap(frame: ByteArray, width: Int, height: Int): Bitmap {
        if (pixels.size < width * height) pixels = IntArray(width * height)
        var from = 0
        for (pixel in 0 until width * height) {
            pixels[pixel] = Color.rgb(frame[from].toInt() and 0xFF, frame[from + 1].toInt() and 0xFF, frame[from + 2].toInt() and 0xFF)
            from += 3
        }
        val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        picture.setPixels(pixels, 0, width, 0, 0, width, height)
        return picture
    }

    private fun detect(picture: Bitmap, tracks: LedTracks, models: InferenceModels): List<LedDetection> {
        val fit = Letterbox.fit(picture.width, picture.height)
        if (pixels.size < LedInput.PLANE) pixels = IntArray(LedInput.PLANE)
        if (fit.unchanged) {
            picture.getPixels(pixels, 0, LedInput.WIDTH, 0, 0, LedInput.WIDTH, LedInput.HEIGHT)
        } else {
            val canvas = Canvas(fitted)
            canvas.drawColor(Color.rgb(LedInput.PADDING_GREY, LedInput.PADDING_GREY, LedInput.PADDING_GREY))
            canvas.drawBitmap(picture, Matrix().apply {
                setScale(fit.scale, fit.scale)
                postTranslate(fit.offsetX, fit.offsetY)
            }, smooth)
            fitted.getPixels(pixels, 0, LedInput.WIDTH, 0, 0, LedInput.WIDTH, LedInput.HEIGHT)
        }
        LedInput.fromPixels(pixels, input)
        // The LEDs are cut out of the picture itself, not out of the copy fitted to the detection model.
        return named(findLeds().map(fit::toSource), tracks, models) { box ->
            val region = LedCrop.region(box, picture.width, picture.height)
            val regionWidth = region[2] - region[0]
            val regionHeight = region[3] - region[1]
            val cut = IntArray(regionWidth * regionHeight)
            picture.getPixels(cut, 0, regionWidth, region[0], region[1], regionWidth, regionHeight)
            LedCrop.fromPixels(cut, regionWidth, regionHeight)
        }
    }

    /** Runs the detection model on [input] and returns the boxes of the LEDs in input pixels, without names. */
    private fun findLeds(): List<LedDetection> {
        val model = requireNotNull(detector) { "The Physical LED Detection Model is not loaded." }
        inputBuffer.rewind()
        inputBuffer.put(input, 0, LedInput.SIZE)
        val answer = model.forward(IValue.from(inputTensor)).toTensor()
        val shape = answer.shape()
        check(shape.size == 3 && shape[0] == 1L && shape[1] == LedBoxes.ROWS.toLong()) {
            "The Physical LED Detection Model answered with shape ${shape.contentToString()}, not 1 × ${LedBoxes.ROWS} × N."
        }
        return LedBoxes.pick(answer.dataAsFloatArray, LedInput.WIDTH, LedInput.HEIGHT)
    }

    /**
     * Runs the recognition model on one strip per LED, all in one run, and returns the probabilities of
     * LED 1 to LED 4 for each. [sequences] holds each LED's crops, at most five, the earliest first.
     */
    private fun recognise(sequences: List<List<ByteArray>>): List<FloatArray> {
        if (sequences.isEmpty()) return emptyList()
        check(sequences.size <= LedBoxes.LEDS) { "The recognition model is given at most ${LedBoxes.LEDS} LEDs, not ${sequences.size}." }
        val model = requireNotNull(recogniser) { "The OOK-Based LED Recognition Model is not loaded." }
        val count = sequences.size
        sequences.forEachIndexed { index, crops -> LedCrop.strip(crops, strips, index) }
        val buffer = stripBuffers[count - 1] ?: Tensor.allocateFloatBuffer(count * LedCrop.STRIP_SIZE).also { stripBuffers[count - 1] = it }
        buffer.rewind()
        buffer.put(strips, 0, count * LedCrop.STRIP_SIZE)
        val tensor = Tensor.fromBlob(buffer, longArrayOf(count.toLong(), 3, LedCrop.SIDE.toLong(), LedCrop.STRIP_WIDTH.toLong()))
        val answer = model.forward(IValue.from(tensor)).toTensor()
        val shape = answer.shape()
        check(shape.size == 2 && shape[0] == count.toLong() && shape[1] == LedBoxes.LEDS.toLong()) {
            "The OOK-Based LED Recognition Model answered with shape ${shape.contentToString()}, not $count × ${LedBoxes.LEDS}."
        }
        val probabilities = answer.dataAsFloatArray
        return List(count) { index -> probabilities.copyOfRange(index * LedBoxes.LEDS, (index + 1) * LedBoxes.LEDS) }
    }

    // The phone's fast cores. More threads than that share the slow cores and make each run longer.
    private const val THREADS = 4
}
