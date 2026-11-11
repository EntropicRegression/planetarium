package app.wallengine.pack

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One layer of a wallpaper scene. `depth`: 1.0 = far background, 0.0 = closest to the viewer. */
data class LayerSpec(
    val type: String,
    val src: String,
    val depth: Float,
)

/** A wallpaper pack unpacked on disk: a directory holding manifest.json and its assets. */
data class WallpaperPack(
    val id: String,
    val dir: File,
    val name: String,
    val layers: List<LayerSpec>,
) {
    /** Resolves an asset path from the manifest, refusing anything that escapes the pack directory. */
    fun asset(src: String): File {
        val root = dir.canonicalFile
        val file = File(root, src).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "asset outside pack: $src" }
        return file
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val FORMAT = 1

        const val TYPE_IMAGE = "image"

        fun read(dir: File): WallpaperPack {
            val json = JSONObject(File(dir, MANIFEST).readText())
            val format = json.optInt("format", 1)
            require(format <= FORMAT) { "pack format $format is newer than supported $FORMAT" }
            val layersJson = json.optJSONArray("layers") ?: JSONArray()
            val layers = (0 until layersJson.length()).map { i ->
                val l = layersJson.getJSONObject(i)
                LayerSpec(
                    type = l.getString("type"),
                    src = l.getString("src"),
                    depth = l.optDouble("depth", 1.0).toFloat(),
                )
            }
            return WallpaperPack(dir.name, dir, json.optString("name", dir.name), layers)
        }

        fun writeManifest(dir: File, name: String, layers: List<LayerSpec>) {
            val json = JSONObject()
                .put("format", FORMAT)
                .put("name", name)
                .put("layers", JSONArray().apply {
                    layers.forEach {
                        put(JSONObject().put("type", it.type).put("src", it.src).put("depth", it.depth.toDouble()))
                    }
                })
            File(dir, MANIFEST).writeText(json.toString(2))
        }
    }
}
