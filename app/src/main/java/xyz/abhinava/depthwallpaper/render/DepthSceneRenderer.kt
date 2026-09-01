package xyz.abhinava.depthwallpaper.render

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import xyz.abhinava.depthwallpaper.data.DepthScene
import xyz.abhinava.depthwallpaper.data.LayerSpec
import xyz.abhinava.depthwallpaper.data.SceneRepository
import kotlin.math.max
import kotlin.math.min

class DepthSceneRenderer(private val context: Context, private val scene: DepthScene) {
    private data class LoadedLayer(val bitmap: Bitmap?, val drawable: Drawable?, val width: Int, val height: Int, val visibleBounds: RectF)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundColor = runCatching { Color.parseColor(scene.canvas.backgroundColor) }.getOrDefault(Color.rgb(7, 10, 24))

    // Sorting and concatenating the layer lists once matters: draw() runs up to 60x a second and
    // used to rebuild and re-sort this list on every frame.
    private val orderedLayers: List<LayerSpec> = (scene.layers + scene.animatedLayers).sortedBy { it.z }

    // Reused across frames so drawing allocates nothing and does not feed the GC mid-scroll.
    private val dstRect = RectF()
    private val drawableRect = Rect()
    private val coverBounds = RectF()

    private val targetWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
    private val targetHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)

    private val bitmaps: Map<String, LoadedLayer> = loadBitmaps()
    val hasAnimations: Boolean = scene.animatedLayers.isNotEmpty()

    fun draw(canvas: Canvas, tiltX: Float, tiltY: Float) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.drawColor(backgroundColor)
        if (bitmaps.isNotEmpty()) drawBitmapLayers(canvas, width, height, tiltX, tiltY) else drawProceduralScene(canvas, width, height, tiltX, tiltY)
    }

    /** Frees decoded layer bitmaps. The engine owns the renderer, so it must call this on teardown. */
    fun release() {
        for (loaded in bitmaps.values) {
            (loaded.drawable as? AnimatedImageDrawable)?.stop()
            loaded.bitmap?.recycle()
        }
    }

    private fun loadBitmaps(): Map<String, LoadedLayer> {
        val repo = SceneRepository(context)
        return (scene.layers + scene.animatedLayers).mapNotNull { layer ->
            if (layer.asset.startsWith("generated://") || layer.asset.startsWith("res://")) return@mapNotNull null
            val file = repo.cachedAssetFile(scene, layer.asset)
            if (!file.exists()) return@mapNotNull null
            val drawable = loadAnimatedDrawableIfSupported(file, layer)
            if (drawable != null) {
                layer.id to LoadedLayer(
                    bitmap = null,
                    drawable = drawable,
                    width = drawable.intrinsicWidth.coerceAtLeast(1),
                    height = drawable.intrinsicHeight.coerceAtLeast(1),
                    visibleBounds = RectF(0f, 0f, drawable.intrinsicWidth.coerceAtLeast(1).toFloat(), drawable.intrinsicHeight.coerceAtLeast(1).toFloat())
                )
            } else {
                val bitmap = decodeScaled(file) ?: return@mapNotNull null
                // Only "contain" positions content by its visible pixels; for cover/stretch the
                // bounds are never read, so skip the per-pixel scan entirely.
                val bounds = if (resolvedFit(layer) == "contain") alphaBounds(bitmap)
                             else RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                layer.id to LoadedLayer(bitmap, null, bitmap.width, bitmap.height, bounds)
            }
        }.toMap()
    }

    /**
     * Decodes at most one halving below the display resolution, so a 1344x2992 layer stops costing
     * ~16 MB of heap on a phone that cannot show that many pixels. Never samples past the point
     * where the bitmap would be smaller than the screen, so quality is unchanged.
     */
    private fun decodeScaled(file: java.io.File): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return BitmapFactory.decodeFile(file.absolutePath)
        var sample = 1
        while (probe.outWidth / (sample * 2) >= targetWidth && probe.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun loadAnimatedDrawableIfSupported(file: java.io.File, layer: LayerSpec): Drawable? {
        if (layer.animation == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = if (layer.animation.loop) AnimatedImageDrawable.REPEAT_INFINITE else 0
                drawable.start()
                drawable
            } else null
        }.getOrNull()
    }

    private fun drawBitmapLayers(canvas: Canvas, width: Float, height: Float, tiltX: Float, tiltY: Float) {
        for (layer in orderedLayers) {
            val loaded = bitmaps[layer.id] ?: continue
            val bitmap = loaded.bitmap
            val fit = resolvedFit(layer)
            val backgroundLayer = fit == "cover"
            val rawOffsetX = -tiltX * layer.parallaxX
            val rawOffsetY = tiltY * layer.parallaxY
            val dst = dstRect
            when (fit) {
                "stretch" -> {
                    val insetX = width * (1f - layer.scale.coerceAtLeast(0.01f)) / 2f
                    val insetY = height * (1f - layer.scale.coerceAtLeast(0.01f)) / 2f
                    dst.set(insetX + rawOffsetX, insetY + rawOffsetY, width - insetX + rawOffsetX, height - insetY + rawOffsetY)
                }
                else -> {
                    val sourceWidth = bitmap?.width ?: loaded.width
                    val sourceHeight = bitmap?.height ?: loaded.height
                    val baseScale = if (fit == "cover") {
                        max(width / sourceWidth.toFloat(), height / sourceHeight.toFloat())
                    } else {
                        min(width / sourceWidth.toFloat(), height / sourceHeight.toFloat())
                    }
                    val renderScale = baseScale * layer.scale
                    val renderedWidth = sourceWidth * renderScale
                    val renderedHeight = sourceHeight * renderScale
                    val bounds = if (fit == "cover") {
                        coverBounds.apply { set(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat()) }
                    } else {
                        loaded.visibleBounds
                    }
                    val visibleLeft = bounds.left * renderScale
                    val visibleTop = bounds.top * renderScale
                    val visibleWidth = bounds.width() * renderScale
                    val visibleHeight = bounds.height() * renderScale
                    val baseLeft = width * layer.anchorX - visibleWidth * layer.pivotX - visibleLeft
                    val baseTop = height * layer.anchorY - visibleHeight * layer.pivotY - visibleTop
                    val offsetX = if (backgroundLayer) rawOffsetX else clampOffset(rawOffsetX, baseLeft, renderedWidth, width)
                    val offsetY = if (backgroundLayer) rawOffsetY else clampOffset(rawOffsetY, baseTop, renderedHeight, height)
                    dst.set(baseLeft + offsetX, baseTop + offsetY, baseLeft + offsetX + renderedWidth, baseTop + offsetY + renderedHeight)
                }
            }
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, dst, paint)
            } else {
                loaded.drawable?.alpha = paint.alpha
                drawableRect.set(dst.left.toInt(), dst.top.toInt(), dst.right.toInt(), dst.bottom.toInt())
                loaded.drawable?.bounds = drawableRect
                loaded.drawable?.draw(canvas)
                loaded.drawable?.alpha = 255
            }
            paint.alpha = 255
        }
    }

    /**
     * Scans one row at a time via getPixels(). The previous getPixel()-per-pixel version made over
     * a million JNI calls per layer and ran on the wallpaper engine's onCreate, stalling startup.
     */
    private fun alphaBounds(bitmap: Bitmap): RectF {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        val step = 2
        val row = IntArray(bitmap.width)
        var y = 0
        while (y < bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            var x = 0
            while (x < bitmap.width) {
                if ((row[x] ushr 24) > 8) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
                x += step
            }
            y += step
        }
        return if (right >= left && bottom >= top) RectF(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat()) else RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    }

    private fun resolvedFit(layer: LayerSpec): String {
        val requested = layer.fit.lowercase()
        if (requested == "cover" || requested == "contain" || requested == "stretch") return requested
        val id = layer.id.lowercase()
        return when {
            id == "background" || id == "sky" || id.contains("back") || layer.z <= 0.2f -> "cover"
            id.contains("effect") || id.contains("mid") -> "stretch"
            else -> "contain"
        }
    }

    private fun clampOffset(offset: Float, baseStart: Float, renderedSize: Float, screenSize: Float): Float {
        if (renderedSize > screenSize) return offset
        val minOffset = -baseStart
        val maxOffset = screenSize - renderedSize - baseStart
        return offset.coerceIn(minOffset, maxOffset)
    }

    private fun drawProceduralScene(canvas: Canvas, width: Float, height: Float, tiltX: Float, tiltY: Float) {
        val layersById = scene.layers.associateBy { it.id }
        drawSky(canvas, width, height, layersById["sky"], tiltX, tiltY)
        drawFarMountains(canvas, width, height, layersById["mountains_far"], tiltX, tiltY)
        drawNearMountains(canvas, width, height, layersById["mountains_near"], tiltX, tiltY)
        drawForeground(canvas, width, height, layersById["foreground"], tiltX, tiltY)
    }

    private fun offset(layer: LayerSpec?, tiltX: Float, tiltY: Float): Pair<Float, Float> {
        if (layer == null) return 0f to 0f
        return (-tiltX * layer.parallaxX) to (tiltY * layer.parallaxY)
    }

    private fun drawSky(canvas: Canvas, width: Float, height: Float, layer: LayerSpec?, tiltX: Float, tiltY: Float) {
        val (ox, oy) = offset(layer, tiltX, tiltY)
        canvas.save(); canvas.translate(ox, oy); paint.style = Paint.Style.FILL
        for (i in 0..24) {
            val t = i / 24f
            paint.color = Color.rgb((7 + 20 * t).toInt(), (10 + 8 * t).toInt(), (24 + 50 * t).toInt())
            canvas.drawRect(0f, height * t, width, height * (t + 1f / 24f) + 2f, paint)
        }
        paint.color = Color.argb(48, 124, 77, 255); canvas.drawCircle(width * 0.72f, height * 0.13f, max(60f, width * 0.12f), paint)
        paint.color = Color.argb(210, 255, 255, 255)
        val stars = arrayOf(0.15f to 0.11f, 0.45f to 0.08f, 0.72f to 0.17f, 0.86f to 0.06f, 0.29f to 0.23f)
        for ((x, y) in stars) canvas.drawCircle(width * x, height * y, max(2f, width * 0.004f), paint)
        canvas.restore()
    }

    private fun drawFarMountains(canvas: Canvas, width: Float, height: Float, layer: LayerSpec?, tiltX: Float, tiltY: Float) {
        val (ox, oy) = offset(layer, tiltX, tiltY); canvas.save(); canvas.translate(ox, oy)
        drawPoly(canvas, Color.rgb(34, 48, 95), -0.06f, 0.67f, 0.17f, 0.47f, 0.40f, 0.67f, width, height)
        drawPoly(canvas, Color.rgb(42, 57, 119), 0.19f, 0.67f, 0.50f, 0.42f, 0.82f, 0.67f, width, height)
        drawPoly(canvas, Color.rgb(30, 43, 88), 0.58f, 0.67f, 0.86f, 0.49f, 1.08f, 0.67f, width, height)
        paint.color = Color.argb(120, 64, 196, 255); paint.strokeWidth = max(3f, width * 0.006f); canvas.drawLine(0f, height * 0.67f, width, height * 0.67f, paint); canvas.restore()
    }

    private fun drawNearMountains(canvas: Canvas, width: Float, height: Float, layer: LayerSpec?, tiltX: Float, tiltY: Float) {
        val (ox, oy) = offset(layer, tiltX, tiltY); canvas.save(); canvas.translate(ox, oy)
        drawPoly(canvas, Color.rgb(38, 18, 72), -0.10f, 0.79f, 0.25f, 0.50f, 0.62f, 0.79f, width, height)
        drawPoly(canvas, Color.rgb(52, 23, 92), 0.35f, 0.79f, 0.72f, 0.45f, 1.12f, 0.79f, width, height)
        paint.color = Color.argb(165, 124, 77, 255); paint.strokeWidth = max(4f, width * 0.008f); canvas.drawLine(0f, height * 0.787f, width, height * 0.787f, paint); canvas.restore()
    }

    private fun drawForeground(canvas: Canvas, width: Float, height: Float, layer: LayerSpec?, tiltX: Float, tiltY: Float) {
        val (ox, oy) = offset(layer, tiltX, tiltY); canvas.save(); canvas.translate(ox, oy); paint.style = Paint.Style.FILL
        paint.color = Color.rgb(8, 8, 20); canvas.drawRect(RectF(-width * 0.1f, height * 0.82f, width * 1.1f, height * 1.05f), paint)
        paint.color = Color.argb(170, 64, 196, 255); paint.strokeWidth = max(3f, width * 0.006f); canvas.drawLine(0f, height * 0.82f, width, height * 0.82f, paint)
        for (x in floatArrayOf(0.12f, 0.26f, 0.86f)) drawLantern(canvas, width * x, height, width)
        canvas.restore()
    }

    private fun drawLantern(canvas: Canvas, x: Float, height: Float, width: Float) {
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(13, 16, 34); canvas.drawRect(x, height * 0.72f, x + width * 0.018f, height * 0.92f, paint)
        paint.color = Color.argb(180, 255, 64, 129); canvas.drawOval(RectF(x - width * 0.025f, height * 0.708f, x + width * 0.045f, height * 0.748f), paint)
        paint.color = Color.argb(225, 255, 245, 180); canvas.drawOval(RectF(x - width * 0.006f, height * 0.719f, x + width * 0.027f, height * 0.739f), paint)
    }

    private fun drawPoly(canvas: Canvas, color: Int, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, width: Float, height: Float) {
        paint.color = color; paint.style = Paint.Style.FILL
        val path = Path().apply { moveTo(width * x1, height * y1); lineTo(width * x2, height * y2); lineTo(width * x3, height * y3); close() }
        canvas.drawPath(path, paint)
    }
}
