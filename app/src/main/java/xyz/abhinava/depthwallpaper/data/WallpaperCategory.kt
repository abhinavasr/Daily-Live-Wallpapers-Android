package xyz.abhinava.depthwallpaper.data

data class WallpaperCategory(
    val slug: String,
    val title: String,
    val count: Int = 0
) {
    companion object {
        val fallback = listOf(
            WallpaperCategory("anime", "Anime"),
            WallpaperCategory("nature", "Nature"),
            WallpaperCategory("devotional", "Devotional"),
            WallpaperCategory("vehicles", "Vehicles"),
            WallpaperCategory("neon", "Neon & Abstract"),
            WallpaperCategory("other", "Other")
        )

        fun titleFor(slug: String): String {
            return fallback.firstOrNull { it.slug == slug }?.title
                ?: slug.replace('-', ' ').replaceFirstChar { it.titlecase() }
        }
    }
}
