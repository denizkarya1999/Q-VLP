package com.developer27.qvlp.camera

/** The preview always fills the screen at the image's true proportions; whatever does not fit is cropped. */
internal object PreviewFill {
    /**
     * TextureView scale factors (x, y) for an on-screen [imageWidth] × [imageHeight] image.
     * A TextureView stretches the image over the whole view by default, so each factor undoes that stretch.
     */
    fun scale(viewWidth: Int, viewHeight: Int, imageWidth: Int, imageHeight: Int): Pair<Float, Float> {
        val zoom = maxOf(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        return imageWidth * zoom / viewWidth to imageHeight * zoom / viewHeight
    }
}
