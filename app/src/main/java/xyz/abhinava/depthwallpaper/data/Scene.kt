package xyz.abhinava.depthwallpaper.data

import org.json.JSONObject

data class DepthScene(
    val id: String,
    val title: String,
    val canvas: CanvasSpec,
    val sensor: SensorSpec,
    val layers: List<LayerSpec>,
    val animatedLayers: List<LayerSpec>
) {
    companion object {
        private fun parseLayer(item: JSONObject): LayerSpec {
            val parallax = item.optJSONObject("parallax") ?: JSONObject()
            val placement = item.optJSONObject("placement") ?: JSONObject()
            val animation = item.optJSONObject("animation") ?: item.optJSONObject("effect")
            return LayerSpec(
                id = item.getString("id"),
                asset = item.getString("asset"),
                z = item.optDouble("z", 0.5).toFloat(),
                parallaxX = parallax.optDouble("x", 0.0).toFloat(),
                parallaxY = parallax.optDouble("y", 0.0).toFloat(),
                scale = item.optDouble("scale", 1.0).toFloat(),
                opacity = item.optDouble("opacity", 1.0).toFloat(),
                fit = item.optString("fit", item.optString("renderMode", "auto")),
                anchorX = placement.optDouble("anchorX", 0.5).toFloat(),
                anchorY = placement.optDouble("anchorY", 0.5).toFloat(),
                pivotX = placement.optDouble("pivotX", 0.5).toFloat(),
                pivotY = placement.optDouble("pivotY", 0.5).toFloat(),
                animation = animation?.let {
                    AnimationSpec(
                        type = it.optString("type", "frame_sequence"),
                        fps = it.optDouble("fps", 12.0).toFloat(),
                        loop = it.optBoolean("loop", true)
                    )
                }
            )
        }

        fun fromJson(json: String): DepthScene {
            val root = JSONObject(json)
            val canvasJson = root.getJSONObject("canvas")
            val sensorJson = root.optJSONObject("sensor") ?: JSONObject()
            val layerArray = root.getJSONArray("layers")
            val layers = buildList {
                for (i in 0 until layerArray.length()) {
                    add(parseLayer(layerArray.getJSONObject(i)))
                }
            }
            val animatedLayerArray = root.optJSONArray("animatedLayers")
            val animatedLayers = buildList {
                if (animatedLayerArray != null) {
                    for (i in 0 until animatedLayerArray.length()) {
                        add(parseLayer(animatedLayerArray.getJSONObject(i)))
                    }
                }
            }
            return DepthScene(
                id = root.optString("id", "unknown"),
                title = root.optString("title", "Depth Wallpaper"),
                canvas = CanvasSpec(
                    width = canvasJson.optInt("width", 720),
                    height = canvasJson.optInt("height", 1560),
                    backgroundColor = canvasJson.optString("backgroundColor", "#070A18")
                ),
                sensor = SensorSpec(
                    maxTiltDegrees = sensorJson.optDouble("maxTiltDegrees", 18.0).toFloat(),
                    smoothing = sensorJson.optDouble("smoothing", 0.12).toFloat(),
                    intensity = sensorJson.optDouble("intensity", 1.0).toFloat()
                ),
                layers = layers.sortedBy { it.z },
                animatedLayers = animatedLayers.sortedBy { it.z }
            )
        }
    }
}

data class CanvasSpec(
    val width: Int,
    val height: Int,
    val backgroundColor: String
)

data class SensorSpec(
    val maxTiltDegrees: Float,
    val smoothing: Float,
    val intensity: Float
)

data class LayerSpec(
    val id: String,
    val asset: String,
    val z: Float,
    val parallaxX: Float,
    val parallaxY: Float,
    val scale: Float,
    val opacity: Float,
    val fit: String,
    val anchorX: Float,
    val anchorY: Float,
    val pivotX: Float,
    val pivotY: Float,
    val animation: AnimationSpec? = null
)

data class AnimationSpec(
    val type: String,
    val fps: Float,
    val loop: Boolean
)
