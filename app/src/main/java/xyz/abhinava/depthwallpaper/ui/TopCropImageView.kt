package xyz.abhinava.depthwallpaper.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.widget.ImageView

/**
 * CENTER_CROP anchors a tall wallpaper at its middle, so a card-sized window lands on the subject's
 * torso and cuts the head off. This anchors the same crop at the top of the image instead.
 */
class TopCropImageView(context: Context) : ImageView(context) {
    private val cropMatrix = Matrix()

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setFrame(l: Int, t: Int, r: Int, b: Int): Boolean {
        applyTopCrop(r - l, b - t)
        return super.setFrame(l, t, r, b)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        applyTopCrop(width, height)
    }

    private fun applyTopCrop(viewWidth: Int, viewHeight: Int) {
        val source = drawable ?: return
        val sourceWidth = source.intrinsicWidth.toFloat()
        val sourceHeight = source.intrinsicHeight.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || sourceWidth <= 0f || sourceHeight <= 0f) return
        val scale = maxOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
        cropMatrix.setScale(scale, scale)
        cropMatrix.postTranslate((viewWidth - sourceWidth * scale) * 0.5f, 0f)
        imageMatrix = cropMatrix
    }
}
