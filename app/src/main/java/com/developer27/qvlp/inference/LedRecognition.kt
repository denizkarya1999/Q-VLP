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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The LED Recognition Model: the YOLO26 network trained on Q-VLP frames to find the four LEDs and tell them
 * apart. Inference Mode, its post-inference files and Local File Inference all use this one copy of it.
 *
 * The model file is the TorchScript export of the training notebook and is loaded as it is. It runs on one
 * thread of its own: [execute] and [frames] may be called from anywhere, everything else only from inside
 * [execute].
 */
internal object LedRecognition {
    const val MODEL_FILE = "qvlp_led_detection_model.pt"
    const val MODEL_NAME = "LED Recognition Model (YOLO26n)"

    /** The LEDs found in one picture, with boxes in that picture's own pixels, and how long finding them took. */
    data class Outcome(val detections: List<LedDetection>, val milliseconds: Long)

    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "QvlpLedModel") }
    private var module: Module? = null
    private val inputBuffer by lazy { Tensor.allocateFloatBuffer(LedInput.SIZE) }
    private val inputTensor by lazy {
        Tensor.fromBlob(inputBuffer, longArrayOf(1, 3, LedInput.HEIGHT.toLong(), LedInput.WIDTH.toLong()))
    }
    private val input = FloatArray(LedInput.SIZE)
    private var rgb = ByteArray(0)
    private var pixels = IntArray(0)
    private val fitted by lazy { Bitmap.createBitmap(LedInput.WIDTH, LedInput.HEIGHT, Bitmap.Config.ARGB_8888) }
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Runs [work] on the model's thread. */
    fun execute(work: () -> Unit) = worker.execute(work)

    /**
     * For a capture's post-inference files: finds the LEDs in each frame and draws them, on the model's thread.
     * The caller waits for the answer, so this must not be used from inside [execute].
     */
    fun frames(context: Context): FrameInference {
        val app = context.applicationContext
        return FrameInference { frame, width, height ->
            worker.submit(Callable { annotate(app, frame, width, height) }).get()
        }
    }

    /** A camera frame as tightly packed I420 (see YuvFrame). */
    fun detectFrame(context: Context, i420: ByteArray, width: Int, height: Int): Outcome {
        val started = SystemClock.elapsedRealtime()
        if (rgb.size < width * height * 3) rgb = ByteArray(width * height * 3)
        // The same colour conversion that wrote the training frames.
        FramePng.toRgb(i420, width, height, rgb)
        return Outcome(detectRgb(context, rgb, width, height), SystemClock.elapsedRealtime() - started)
    }

    /** A picture of any size, for example one opened from a file. */
    fun detectPicture(context: Context, picture: Bitmap): Outcome {
        val started = SystemClock.elapsedRealtime()
        load(context)
        return Outcome(detect(picture), SystemClock.elapsedRealtime() - started)
    }

    /** Loads the model if it is not loaded yet. The first call takes about a second. */
    fun load(context: Context) {
        if (module != null) return
        PyTorchAndroid.setNumThreads(THREADS)
        module = PyTorchAndroid.loadModuleFromAsset(context.applicationContext.assets, MODEL_FILE)
    }

    /** [frame] holds [width] × [height] pixels as red, green, blue bytes. */
    private fun detectRgb(context: Context, frame: ByteArray, width: Int, height: Int): List<LedDetection> {
        load(context)
        if (width == LedInput.WIDTH && height == LedInput.HEIGHT) {
            LedInput.fromRgb(frame, input)
            return run()
        }
        val picture = bitmap(frame, width, height)
        try {
            return detect(picture)
        } finally {
            picture.recycle()
        }
    }

    /** The frame as a PNG with the LEDs the model found drawn on it, exactly as Inference Mode shows them. */
    private fun annotate(context: Context, frame: ByteArray, width: Int, height: Int): InferredFrame {
        val started = SystemClock.elapsedRealtime()
        val found = detectRgb(context, frame, width, height)
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

    private fun detect(picture: Bitmap): List<LedDetection> {
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
        return run().map(fit::toSource)
    }

    /** Runs the model on [input] and returns its boxes in input pixels. */
    private fun run(): List<LedDetection> {
        val model = requireNotNull(module) { "The LED Recognition Model is not loaded." }
        inputBuffer.rewind()
        inputBuffer.put(input, 0, LedInput.SIZE)
        val answer = model.forward(IValue.from(inputTensor)).toTensor()
        val shape = answer.shape()
        check(shape.size == 3 && shape[0] == 1L && shape[1] == (4 + LedBoxes.LEDS).toLong()) {
            "The LED Recognition Model answered with shape ${shape.contentToString()}, not 1 × 8 × N."
        }
        return LedBoxes.pick(answer.dataAsFloatArray, LedInput.WIDTH, LedInput.HEIGHT)
    }

    // The phone's fast cores. More threads than that share the slow cores and make each run longer.
    private const val THREADS = 4
}
