package xyz.abhinava.depthwallpaper.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import xyz.abhinava.depthwallpaper.data.DepthScene
import xyz.abhinava.depthwallpaper.data.LayerSpec
import xyz.abhinava.depthwallpaper.data.SceneRepository
import kotlin.math.max
import kotlin.math.min

class DepthSceneRenderer(private val context: Context, private val scene: DepthScene) {
    private data class LoadedLayer(val bitmap: Bitmap, val visibleBounds: RectF)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundColor = runCatching { Color.parseColor(scene.canvas.backgroundColor) }.getOrDefault(Color.rgb(7, 10, 24))
    private val bitmaps: Map<String, LoadedLayer> = loadBitmaps()

    fun draw(canvas: Canvas, tiltX: Float, tiltY: Float) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.drawColor(backgroundColor)
        if (bitmaps.isNotEmpty()) drawBitmapLayers(canvas, width, height, tiltX, tiltY) else drawProceduralScene(canvas, width, height, tiltX, tiltY)

        paint.color = Color.argb(45, 255, 255, 255)
        paint.textSize = max(22f, width * 0.026f)
        canvas.drawText(scene.title, width * 0.04f, height - width * 0.06f, paint)
    }

    private fun loadBitmaps(): Map<String, LoadedLayer> {
        val repo = SceneRepository(context)
        return scene.layers.mapNotNull { layer ->
            if (layer.asset.startsWith("generated://") || layer.asset.startsWith("res://")) return@mapNotNull null
            val file = repo.cachedAssetFile(scene, layer.asset)
            if (!file.exists()) return@mapNotNull null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
            layer.id to LoadedLayer(bitmap, alphaBounds(bitmap))
        }.toMap()
    }

    private fun drawBitmapLayers(canvas: Canvas, width: Float, height: Float, tiltX: Float, tiltY: Float) {
        for (layer in scene.layers) {
            val loaded = bitmaps[layer.id] ?: continue
            val bitmap = loaded.bitmap
            val fit = resolvedFit(layer)
            val backgroundLayer = fit == "cover"
            val rawOffsetX = -tiltX * layer.parallaxX
            val rawOffsetY = tiltY * layer.parallaxY
            val dst = when (fit) {
                "stretch" -> {
                    val insetX = width * (1f - layer.scale.coerceAtLeast(0.01f)) / 2f
                    val insetY = height * (1f - layer.scale.coerceAtLeast(0.01f)) / 2f
                    RectF(insetX + rawOffsetX, insetY + rawOffsetY, width - insetX + rawOffsetX, height - insetY + rawOffsetY)
                }
                else -> {
                    val baseScale = if (fit == "cover") {
                        max(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
                    } else {
                        min(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
                    }
                    val renderScale = baseScale * layer.scale
                    val renderedWidth = bitmap.width * renderScale
                    val renderedHeight = bitmap.height * renderScale
                    val bounds = if (fit == "cover") RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()) else loaded.visibleBounds
                    val visibleLeft = bounds.left * renderScale
                    val visibleTop = bounds.top * renderScale
                    val visibleWidth = bounds.width() * renderScale
                    val visibleHeight = bounds.height() * renderScale
                    val baseLeft = width * layer.anchorX - visibleWidth * layer.pivotX - visibleLeft
                    val baseTop = height * layer.anchorY - visibleHeight * layer.pivotY - visibleTop
                    val offsetX = if (backgroundLayer) rawOffsetX else clampOffset(rawOffsetX, baseLeft, renderedWidth, width)
                    val offsetY = if (backgroundLayer) rawOffsetY else clampOffset(rawOffsetY, baseTop, renderedHeight, height)
                    RectF(baseLeft + offsetX, baseTop + offsetY, baseLeft + offsetX + renderedWidth, baseTop + offsetY + renderedHeight)
                }
            }
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            canvas.drawBitmap(bitmap, null, dst, paint)
            paint.alpha = 255
        }
    }

    private fun alphaBounds(bitmap: Bitmap): RectF {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        val step = 2
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 8) {
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
