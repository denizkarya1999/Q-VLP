package com.developer27.qvlp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.developer27.qvlp.databinding.ActivityLocalFileInferenceBinding
import com.developer27.qvlp.inference.LedBoxPainter
import com.developer27.qvlp.inference.LedDetection
import com.developer27.qvlp.inference.LedRecognition

/**
 * Settings → Options → Local File Inference: opens an image stored on the phone and runs the Physical LED
 * Detection Model and the OOK-Based LED Recognition Model on it. The LEDs are shown exactly as in Inference
 * Mode: one box per LED with its name and confidence. The image is one frame, so each LED is named from five
 * copies of it.
 */
class LocalFileInferenceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLocalFileInferenceBinding
    private var imageUri: Uri? = null
    private var request = 0
    private val chooseImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) open(uri)
    }

    private class Shown(val picture: Bitmap, val detections: List<LedDetection>, val name: String,
                        val width: Int, val height: Int, val milliseconds: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalFileInferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.doneButton.setOnClickListener { finish() }
        binding.chooseImageButton.setOnClickListener { chooseImage.launch(arrayOf("image/*")) }
        // Turning the phone rebuilds the screen: show the same image again.
        savedInstanceState?.getString(IMAGE_URI)?.let { open(Uri.parse(it)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(IMAGE_URI, imageUri?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun open(uri: Uri) {
        imageUri = uri
        val current = ++request
        binding.chooseImageButton.isEnabled = false
        binding.results.setText(R.string.local_inference_running)
        binding.details.text = ""
        val app = applicationContext
        LedRecognition.execute {
            val shown = runCatching {
                val picture = decode(uri)
                val found = LedRecognition.detectPicture(app, picture)
                Shown(draw(picture, found.detections), found.detections, name(uri), picture.width, picture.height, found.milliseconds)
            }
            runOnUiThread { if (!isDestroyed && current == request) show(shown) }
        }
    }

    private fun show(shown: Result<Shown>) {
        binding.chooseImageButton.isEnabled = true
        val result = shown.getOrElse { error ->
            binding.picture.setImageDrawable(null)
            binding.results.text = getString(R.string.local_inference_failed, error.message ?: error.javaClass.simpleName)
            return
        }
        binding.picture.setImageBitmap(result.picture)
        binding.results.text = if (result.detections.isEmpty()) getString(R.string.local_inference_none) else list(result.detections)
        binding.details.text = getString(R.string.local_inference_details, result.name, result.width, result.height, result.milliseconds)
    }

    /** One line per LED, "LED 1 | 98%", with a square in the colour of its box. */
    private fun list(detections: List<LedDetection>): CharSequence = SpannableStringBuilder().apply {
        detections.forEachIndexed { index, found ->
            if (index > 0) append('\n')
            val start = length
            append("■ ")
            setSpan(ForegroundColorSpan(LedBoxPainter.colour(found.led)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(found.label)
        }
    }

    /** Very large photos are read at a reduced size; the model works on 1280 × 960 pixels anyway. */
    private fun decode(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "it is not an image this phone can read." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return requireNotNull(contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }) {
            "it is not an image this phone can read."
        }
    }

    /** A copy of the picture with the boxes drawn on it. Line and letter sizes follow the picture's size. */
    private fun draw(picture: Bitmap, detections: List<LedDetection>): Bitmap {
        val copy = picture.copy(Bitmap.Config.ARGB_8888, true)
        val painter = LedBoxPainter.forPicture(copy.width)
        val canvas = Canvas(copy)
        detections.forEach { painter.draw(canvas, it.left, it.top, it.right, it.bottom, it) }
        return copy
    }

    private fun name(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private companion object {
        const val IMAGE_URI = "image_uri"
        const val MAX_SIDE = 4096
    }
}
