package xyz.abhinava.depthwallpaper

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Gallery previews are the only large download the browser does, and Coil's default loader
 * revalidates every one of them against the server on each launch. An explicit disk cache that
 * ignores cache headers means a preview is downloaded once and read from disk after that.
 */
class WallpaperApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("gallery_previews"))
                    .maxSizeBytes(PREVIEW_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    private companion object {
        private const val PREVIEW_CACHE_BYTES = 192L * 1024 * 1024
    }
}
