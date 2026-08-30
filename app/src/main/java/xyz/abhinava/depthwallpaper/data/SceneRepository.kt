package xyz.abhinava.depthwallpaper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.abhinava.depthwallpaper.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class SceneRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("depth_scene_cache", Context.MODE_PRIVATE)

    fun loadBundledSample(): DepthScene {
        val json = context.assets.open("wallpapers/sample/scene.json")
            .bufferedReader()
            .use { it.readText() }
        return DepthScene.fromJson(json)
    }

    fun loadCachedOrBundled(): DepthScene {
        val activeId = prefs.getString(KEY_ACTIVE_SCENE_ID, null)
        if (!activeId.isNullOrBlank()) {
            loadStoredScene(activeId)?.let { return it }
        }

        // Backwards compatibility for users who installed the earlier build where the download
        // cache doubled as the active wallpaper cache.
        val cached = prefs.getString(KEY_SCENE_JSON, null)
        if (!cached.isNullOrBlank()) runCatching { return DepthScene.fromJson(cached) }
        return loadBundledSample()
    }

    fun loadSceneForWallpaper(isPreview: Boolean): DepthScene {
        if (isPreview) {
            val pendingId = prefs.getString(KEY_PENDING_SCENE_ID, null)
            if (!pendingId.isNullOrBlank()) {
                loadStoredScene(pendingId)?.let { return it }
            }
        } else {
            val pendingId = prefs.getString(KEY_PENDING_SCENE_ID, null)
            if (!pendingId.isNullOrBlank()) {
                loadStoredScene(pendingId)?.let { scene ->
                    markSceneActive(scene)
                    clearPendingScene()
                    return scene
                }
            }
        }
        return loadCachedOrBundled()
    }

    fun cachedSceneTitle(): String? = prefs.getString(KEY_SCENE_TITLE, null)

    fun cachedAssetFile(scene: DepthScene, asset: String): File {
        return File(File(context.filesDir, "wallpapers/${scene.id}/assets"), asset.substringAfterLast('/'))
    }

    fun fetchAndCacheToday(): DepthScene {
        val endpoint = "${BuildConfig.API_BASE_URL.trimEnd('/')}/wallpapers/today"
        return fetchAndCacheScene(endpoint)
    }

    fun fetchAndCacheScene(
        sceneUrlOrPath: String,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ): DepthScene {
        val endpoint = absoluteUrl(sceneUrlOrPath)
        val json = httpGetText(endpoint)
        val scene = DepthScene.fromJson(json)
        val downloadableLayers = (scene.layers + scene.animatedLayers).filter { layer ->
            val asset = layer.asset
            !asset.startsWith("generated://") && !asset.startsWith("res://")
        }
        val total = downloadableLayers.size
        onProgress?.invoke(0, total)

        val assetDir = File(context.filesDir, "wallpapers/${scene.id}/assets")
        assetDir.mkdirs()
        var completed = 0
        var firstFailure: Throwable? = null

        for (layer in downloadableLayers) {
            val asset = layer.asset
            val file = cachedAssetFile(scene, asset)
            val assetUrl = resolveAssetUrl(endpoint, asset)
            val result = runCatching {
                if (!file.exists() || file.length() <= 0L) {
                    httpDownload(assetUrl, file)
                }
            }
            if (result.isFailure && firstFailure == null) firstFailure = result.exceptionOrNull()
            completed += 1
            onProgress?.invoke(completed, total)
        }

        // Do not cache a scene whose media failed to download; otherwise the wallpaper can look
        // broken while the UI claims success. Keep the previously cached/bundled wallpaper intact.
        firstFailure?.let { throw IllegalStateException("Could not download wallpaper assets. Check your connection and try again.", it) }

        storeSceneJson(scene, json)
        return scene
    }

    fun markScenePending(scene: DepthScene) {
        prefs.edit()
            .putString(KEY_PENDING_SCENE_ID, scene.id)
            .putString(KEY_PENDING_SCENE_TITLE, scene.title)
            .putLong(KEY_PENDING_AT, System.currentTimeMillis())
            .apply()
    }

    private fun markSceneActive(scene: DepthScene) {
        val json = sceneJsonFile(scene.id).takeIf { it.exists() }?.readText()
        prefs.edit()
            .putString(KEY_ACTIVE_SCENE_ID, scene.id)
            .putString(KEY_SCENE_ID, scene.id)
            .putString(KEY_SCENE_TITLE, scene.title)
            .apply {
                if (!json.isNullOrBlank()) putString(KEY_SCENE_JSON, json)
            }
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearPendingScene() {
        prefs.edit()
            .remove(KEY_PENDING_SCENE_ID)
            .remove(KEY_PENDING_SCENE_TITLE)
            .remove(KEY_PENDING_AT)
            .apply()
    }

    private fun loadStoredScene(sceneId: String): DepthScene? {
        val file = sceneJsonFile(sceneId)
        if (!file.exists()) return null
        return runCatching { DepthScene.fromJson(file.readText()) }.getOrNull()
    }

    private fun storeSceneJson(scene: DepthScene, json: String) {
        val file = sceneJsonFile(scene.id)
        file.parentFile?.mkdirs()
        file.writeText(json)
    }

    private fun sceneJsonFile(sceneId: String): File {
        return File(context.filesDir, "wallpapers/$sceneId/scene.json")
    }

    fun loadCachedGallery(): List<WallpaperPack> {
        val cached = prefs.getString(KEY_GALLERY_JSON, null) ?: return emptyList()
        return runCatching { parseGallery(cached) }.getOrDefault(emptyList())
    }

    fun fetchGallery(): List<WallpaperPack> {
        val json = httpGetText("${BuildConfig.API_BASE_URL.trimEnd('/')}/wallpaper-api/gallery")
        val fresh = parseGallery(json)
        // A successful refresh is authoritative. The API already filters unpublished/hidden packs,
        // so merging older cached packs back in would resurrect wallpapers hidden from the gallery.
        prefs.edit().putString(KEY_GALLERY_JSON, galleryToJson(fresh)).apply()
        return fresh
    }

    private fun parseGallery(json: String): List<WallpaperPack> {
        val arr = JSONArray(json)
        return buildList {
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                add(
                    WallpaperPack(
                        id = item.optString("id"),
                        code = item.optString("code", item.optString("id")),
                        title = item.optString("title", item.optString("id")),
                        source = item.optString("source", "unknown"),
                        publishedAt = item.optString("publishedAt").ifBlank { null },
                        previewUrl = item.optString("previewUrl"),
                        sceneUrl = item.optString("sceneUrl"),
                        viewCount = item.optInt("viewCount", item.optInt("views", 0)),
                        likeCount = item.optInt("likeCount", item.optInt("likes", 0)),
                        viewUserCount = item.optInt("viewUserCount", 0),
                        likeUserCount = item.optInt("likeUserCount", 0)
                    )
                )
            }
        }
    }

    private fun mergeGallery(old: List<WallpaperPack>, fresh: List<WallpaperPack>): List<WallpaperPack> {
        val map = LinkedHashMap<String, WallpaperPack>()
        for (p in fresh) map[p.id] = p
        for (p in old) if (!map.containsKey(p.id)) map[p.id] = p
        return map.values.toList()
    }

    private fun galleryToJson(packs: List<WallpaperPack>): String {
        val arr = JSONArray()
        for (p in packs) arr.put(JSONObject().apply {
            put("id", p.id); put("code", p.code); put("title", p.title); put("source", p.source)
            put("publishedAt", p.publishedAt ?: ""); put("previewUrl", p.previewUrl); put("sceneUrl", p.sceneUrl)
            put("viewCount", p.viewCount); put("likeCount", p.likeCount)
            put("viewUserCount", p.viewUserCount); put("likeUserCount", p.likeUserCount)
        })
        return arr.toString()
    }

    fun fetchPackByCode(code: String): WallpaperPack {
        val clean = code.trim()
        val json = httpGetText("${BuildConfig.API_BASE_URL.trimEnd('/')}/wallpaper-api/wallpaper-code/${java.net.URLEncoder.encode(clean, "UTF-8")}")
        val item = org.json.JSONObject(json)
        return WallpaperPack(
            id = item.optString("id"),
            code = item.optString("code", item.optString("id")),
            title = item.optString("title", item.optString("id")),
            source = item.optString("source", "unknown"),
            publishedAt = item.optString("publishedAt").ifBlank { null },
            previewUrl = item.optString("previewUrl"),
            sceneUrl = item.optString("sceneUrl"),
            viewCount = item.optInt("viewCount", item.optInt("views", 0)),
            likeCount = item.optInt("likeCount", item.optInt("likes", 0)),
            viewUserCount = item.optInt("viewUserCount", 0),
            likeUserCount = item.optInt("likeUserCount", 0)
        )
    }

    fun recordPackView(packId: String) {
        runCatching { httpPostJson("${BuildConfig.API_BASE_URL.trimEnd('/')}/wallpaper-api/packs/${java.net.URLEncoder.encode(packId, "UTF-8")}/view", statPayload()) }
    }

    fun likePack(packId: String) {
        runCatching { httpPostJson("${BuildConfig.API_BASE_URL.trimEnd('/')}/wallpaper-api/packs/${java.net.URLEncoder.encode(packId, "UTF-8")}/like", statPayload(liked = true)) }
    }

    private fun statPayload(liked: Boolean? = null): String {
        return JSONObject().apply {
            put("installationId", installationId())
            if (liked != null) put("liked", liked)
        }.toString()
    }

    private fun installationId(): String {
        val existing = prefs.getString(KEY_INSTALLATION_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALLATION_ID, fresh).apply()
        return fresh
    }

    fun absoluteUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        val api = BuildConfig.API_BASE_URL.trimEnd('/')
        val origin = api.removeSuffix("/agent")
        return if (pathOrUrl.startsWith("/agent/")) origin + pathOrUrl else "$api/${pathOrUrl.trimStart('/')}"
    }

    private fun resolveAssetUrl(sceneUrl: String, asset: String): String {
        if (asset.startsWith("http://") || asset.startsWith("https://")) return asset
        val base = if (sceneUrl.endsWith(".json")) sceneUrl.substringBeforeLast('/') else sceneUrl.trimEnd('/')
        return "$base/$asset"
    }

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(url: String, file: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 20000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("Asset HTTP $code")
            file.parentFile?.mkdirs()
            file.outputStream().use { out -> conn.inputStream.use { input -> input.copyTo(out) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(url: String, json: String): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val KEY_SCENE_JSON = "scene_json"
        private const val KEY_SCENE_TITLE = "scene_title"
        private const val KEY_SCENE_ID = "scene_id"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_GALLERY_JSON = "gallery_json"
        private const val KEY_ACTIVE_SCENE_ID = "active_scene_id"
        private const val KEY_PENDING_SCENE_ID = "pending_scene_id"
        private const val KEY_PENDING_SCENE_TITLE = "pending_scene_title"
        private const val KEY_PENDING_AT = "pending_at"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
