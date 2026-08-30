package xyz.abhinava.depthwallpaper.data

data class WallpaperPack(
    val id: String,
    val code: String,
    val title: String,
    val source: String,
    val publishedAt: String?,
    val previewUrl: String,
    val sceneUrl: String,
    val viewCount: Int = 0,
    val likeCount: Int = 0
)
