package xyz.abhinava.depthwallpaper.ui

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import xyz.abhinava.depthwallpaper.data.DepthScene
import xyz.abhinava.depthwallpaper.data.SceneRepository
import xyz.abhinava.depthwallpaper.render.DepthSceneRenderer
import kotlin.math.sin

class PreviewView(context: Context) : View(context) {
    private val repository = SceneRepository(context)
    private var scene: DepthScene = repository.loadCachedOrBundled()
    private var renderer = DepthSceneRenderer(context, scene)
    private val startedAt = SystemClock.uptimeMillis()

    fun reloadFromCache() {
        scene = repository.loadCachedOrBundled()
        renderer = DepthSceneRenderer(context, scene)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = (SystemClock.uptimeMillis() - startedAt) / 1000f
        val tiltX = sin(elapsed * 0.85f) * 0.75f
        val tiltY = sin(elapsed * 0.55f) * 0.45f
        renderer.draw(canvas, tiltX, tiltY)
        postInvalidateDelayed(33L)
    }
}
