package xyz.abhinava.depthwallpaper.wallpaper

import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import xyz.abhinava.depthwallpaper.data.SceneRepository
import xyz.abhinava.depthwallpaper.render.DepthSceneRenderer

class DepthWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DepthEngine()

    inner class DepthEngine : Engine() {
        private var visible = false
        private var holderRef: SurfaceHolder? = null
        private val handler = Handler(Looper.getMainLooper())
        private var renderer: DepthSceneRenderer? = null
        private var motion: MotionController? = null
        private val redraw = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            try {
                val scene = SceneRepository(this@DepthWallpaperService).loadCachedOrBundled()
                renderer = DepthSceneRenderer(this@DepthWallpaperService, scene)
                motion = MotionController(
                    context = this@DepthWallpaperService,
                    maxTiltDegrees = scene.sensor.maxTiltDegrees,
                    smoothing = scene.sensor.smoothing,
                    intensity = scene.sensor.intensity,
                    onMotion = { scheduleDraw() }
                )
            } catch (_: Throwable) {
                // Keep service alive; drawFrame has a plain fallback.
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            holderRef = holder
            scheduleDraw()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            holderRef = holder
            scheduleDraw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                motion?.start()
                scheduleDraw()
            } else {
                handler.removeCallbacks(redraw)
                motion?.stop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(redraw)
            motion?.stop()
            holderRef = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            handler.removeCallbacks(redraw)
            motion?.stop()
            super.onDestroy()
        }

        private fun scheduleDraw() {
            if (!visible && holderRef == null) return
            handler.removeCallbacks(redraw)
            handler.postDelayed(redraw, 16L)
        }

        private fun drawFrame() {
            val holder = holderRef ?: surfaceHolder ?: return
            val canvas = try { holder.lockCanvas() } catch (_: Throwable) { null } ?: return
            try {
                val m = motion
                val r = renderer
                if (r != null && m != null) {
                    r.draw(canvas, m.tiltX, m.tiltY)
                } else {
                    canvas.drawColor(0xFF070A18.toInt())
                }
            } catch (_: Throwable) {
                canvas.drawColor(0xFF070A18.toInt())
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Throwable) {}
            }
            if (visible && renderer?.hasAnimations == true) {
                handler.removeCallbacks(redraw)
                handler.postDelayed(redraw, 33L)
            }
        }
    }
}
