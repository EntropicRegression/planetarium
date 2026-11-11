package app.wallengine.engine

import android.util.Log
import app.wallengine.engine.gl.ShaderException
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack
import org.json.JSONObject
import java.io.File

/**
 * Turns an author's fragment shader into the source that is handed to the GL compiler. Used by
 * [ShaderLayer], and therefore by everything that compiles shaders (wallpaper and validator).
 */
object ShaderSource {
    /** Vertex stage for every shader layer. `vUv` is 0..1 with the origin bottom-left. */
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /** Uniforms declared for Shadertoy-style shaders: GLSL type, name, array suffix. */
    private val BUILTINS = listOf(
        Triple("vec3", "iResolution", ""),
        Triple("float", "iTime", ""),
        Triple("float", "iTimeDelta", ""),
        Triple("int", "iFrame", ""),
        Triple("vec4", "iMouse", ""),
        Triple("sampler2D", "iChannel0", ""),
        Triple("sampler2D", "iChannel1", ""),
        Triple("sampler2D", "iChannel2", ""),
        Triple("sampler2D", "iChannel3", ""),
        Triple("vec3", "iChannelResolution", "[4]"),
        Triple("float", "uOffset", ""),
        Triple("vec3", "uTouch", ""),
        Triple("vec2", "uTilt", ""),
        Triple("int", "uPass", ""),
    )

    val RESERVED_NAMES: Set<String> = BUILTINS.map { it.second }.toSet() +
        setOf("main", "mainImage", "wallengineFragColor", "vUv", "aPos")

    private val COMMENTS = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")
    private val VERSION_LINE = Regex("""^[ \t]*#[ \t]*version\b[^\n]*""", RegexOption.MULTILINE)
    private val MAIN = Regex("""\bvoid\s+main\s*\(""")
    private val MAIN_IMAGE = Regex("""\bvoid\s+mainImage\s*\(""")

    /**
     * Two authoring styles, told apart by looking at the source with comments removed:
     *  - Shadertoy style: defines `void mainImage(out vec4, in vec2)` and no `main()`. It is wrapped
     *    with a header (version, precision, uniforms) and a `main()`. A `#line 1` directive after
     *    the header keeps the compiler's line numbers equal to the author's.
     *  - Complete shader: has its own `#version` line and `main()`. Used as it is.
     */
    fun build(userSource: String, paramIds: List<String>): String {
        val source = userSource.removePrefix("﻿").replace("\r\n", "\n")
        val code = COMMENTS.replace(source, " ")
        val hasVersion = VERSION_LINE.containsMatchIn(code)
        val hasMain = MAIN.containsMatchIn(code)
        val hasMainImage = MAIN_IMAGE.containsMatchIn(code)
        return when {
            hasMainImage && !hasMain -> wrap(source, code, paramIds)
            hasVersion && hasMain -> source
            hasMain -> throw ShaderException(
                "完整的 shader 第一行必須是 #version 300 es;或改寫成 Shadertoy 風格,只定義 " +
                    "void mainImage(out vec4 fragColor, in vec2 fragCoord)。",
            )
            else -> throw ShaderException(
                "找不到進入點:請定義 void mainImage(out vec4 fragColor, in vec2 fragCoord)," +
                    "或提供含 #version 300 es 與 void main() 的完整 shader。",
            )
        }
    }

    private fun wrap(source: String, code: String, paramIds: List<String>): String {
        fun declaredByAuthor(name: String) =
            Regex("""\buniform\s+(?:(?:highp|mediump|lowp)\s+)?\w+\s+""" + Regex.escape(name) + """\b""")
                .containsMatchIn(code)

        val out = StringBuilder()
        out.append("#version 300 es\n")
        out.append("precision highp float;\nprecision highp int;\nprecision highp sampler2D;\n")
        BUILTINS.forEach { (type, name, suffix) ->
            if (!declaredByAuthor(name)) out.append("uniform ").append(type).append(' ').append(name).append(suffix).append(";\n")
        }
        paramIds.forEach { if (!declaredByAuthor(it)) out.append("uniform float ").append(it).append(";\n") }
        // GLSL ES 3.00: the line after "#line N" is line N.
        out.append("#line 1\n")
        // A stray #version in the author's text would no longer be on the first line: blank it,
        // keeping the line so numbering is unchanged.
        out.append(VERSION_LINE.replace(source, ""))
        out.append("\nout vec4 wallengineFragColor;\n")
        out.append("void main() {\n")
        out.append("    vec4 color = vec4(0.0, 0.0, 0.0, 1.0);\n")
        out.append("    mainImage(color, gl_FragCoord.xy);\n")
        out.append("    wallengineFragColor = color;\n")
        out.append("}\n")
        return out.toString()
    }
}

/** Everything about one shader layer that is decided without a GL context. */
class ShaderConfig(pack: WallpaperPack, spec: LayerSpec) {
    sealed interface Channel {
        data object None : Channel
        /** The layer's own previous frame. */
        data object Self : Channel
        data class Image(val file: File) : Channel
    }

    class Param(val id: String, val value: Float)

    val channels: List<Channel>
    /** Render scale requested by the pack, 0.25..1. */
    val scale: Float
    val additive: Boolean
    val params: List<Param>
    /** Final fragment source, ready to compile. */
    val fragment: String

    init {
        val options = spec.options
        val list = options.optJSONArray("channels")
        channels = (0 until MAX_CHANNELS).map { i ->
            val entry = if (list != null && i < list.length() && !list.isNull(i)) list.optString(i) else ""
            when {
                entry.isEmpty() -> Channel.None
                entry == "self" -> Channel.Self
                else -> {
                    val file = pack.asset(entry)
                    require(file.isFile) { "channels[$i]:找不到檔案 $entry" }
                    Channel.Image(file)
                }
            }
        }
        val s = options.optDouble("scale", 1.0).toFloat()
        scale = if (s.isNaN()) 1f else s.coerceIn(MIN_SCALE, 1f)
        additive = options.optString("blend", "normal") == "add"
        params = readParams(pack)

        val file = pack.asset(spec.src)
        require(file.isFile) { "找不到 shader 檔案 ${spec.src}" }
        require(file.length() <= MAX_SOURCE_BYTES) { "shader 檔案過大:${spec.src}" }
        fragment = ShaderSource.build(file.readText(), params.map { it.id })
    }

    private fun readParams(pack: WallpaperPack): List<Param> {
        val defs = pack.manifest.optJSONArray("params") ?: return emptyList()
        val values = try {
            val file = File(pack.dir, PARAMS_FILE)
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        } catch (e: Exception) {
            Log.w(TAG, "unreadable $PARAMS_FILE in pack ${pack.id}", e)
            JSONObject()
        }
        val seen = HashSet<String>()
        return (0 until defs.length()).mapNotNull { i ->
            val def = defs.optJSONObject(i) ?: return@mapNotNull null
            val id = def.optString("id")
            val type = def.optString("type", "float")
            if (type != "float" || !IDENTIFIER.matches(id) || id.startsWith("gl_") || id.contains("__") ||
                id in ShaderSource.RESERVED_NAMES || !seen.add(id)
            ) {
                Log.w(TAG, "ignoring param '$id' (type $type) of pack ${pack.id}")
                return@mapNotNull null
            }
            val default = def.optDouble("default", 0.0)
            var value = values.optDouble(id, default)
            if (value.isNaN()) value = if (default.isNaN()) 0.0 else default
            val min = def.optDouble("min", Double.NaN)
            val max = def.optDouble("max", Double.NaN)
            if (!min.isNaN()) value = maxOf(value, min)
            if (!max.isNaN()) value = minOf(value, max)
            Param(id, value.toFloat())
        }
    }

    companion object {
        private const val TAG = "ShaderConfig"
        const val MAX_CHANNELS = 4
        const val MIN_SCALE = 0.25f
        const val PARAMS_FILE = "params.json"
        private const val MAX_SOURCE_BYTES = 1L shl 20
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
