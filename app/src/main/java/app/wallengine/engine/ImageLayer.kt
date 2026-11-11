package app.wallengine.engine

import android.opengl.GLES30
import app.wallengine.engine.gl.BitmapLoader
import app.wallengine.engine.gl.ShaderProgram
import app.wallengine.engine.gl.Texture2D
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack

/**
 * A still image, scaled to cover the screen, panning horizontally with the launcher page scroll
 * and sliding with device tilt according to its depth.
 *
 * With the option "depthMap" (a greyscale image, white = near) the image is instead displaced
 * per pixel by its depth, which gives one photo continuous parallax.
 */
class ImageLayer(pack: WallpaperPack, private val spec: LayerSpec, maxTextureSize: Int) : Layer {
    override val animated = false

    private val parallax = Parallax.of(pack)
    private val texture: Texture2D
    private val depthMap: Texture2D?

    private val program = ShaderProgram(VERT, FRAG)
    private val uScale = program.uniform("uScale")
    private val uOffset = program.uniform("uOffset")
    private val uTex = program.uniform("uTex")
    private val uDepth = program.uniform("uDepth")
    private val uHasDepth = program.uniform("uHasDepth")
    private val uShift = program.uniform("uShift")
    private val uFocus = program.uniform("uFocus")

    init {
        texture = load(pack, spec.src, maxTextureSize)
        depthMap = try {
            spec.options.optString("depthMap").ifEmpty { null }?.let { load(pack, it, DEPTH_MAP_SIZE) }
        } catch (e: Exception) {
            texture.release()
            program.release()
            throw e
        }
    }

    override val usesTilt: Boolean =
        parallax.strength > 0f && (depthMap != null || parallax.factor(spec.depth) != 0f)

    override fun draw(frame: FrameContext) {
        val aspect = texture.width.toFloat() / texture.height
        val fit = if (depthMap != null) {
            // Every pixel slides by its own depth; overscan for the largest possible slide.
            val margin = parallax.strength * maxOf(parallax.focus, 1f - parallax.focus)
            coverFit(aspect, frame, margin, 0f)
        } else {
            coverFit(aspect, frame, parallax.margin(spec.depth), parallax.strength * parallax.factor(spec.depth))
        }

        program.use()
        texture.bind(0)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform2f(uScale, fit.scaleX, fit.scaleY)
        GLES30.glUniform2f(uOffset, fit.offsetX, fit.offsetY)
        if (depthMap != null) {
            depthMap.bind(1)
            GLES30.glUniform1i(uDepth, 1)
            GLES30.glUniform1f(uHasDepth, 1f)
            GLES30.glUniform1f(uFocus, parallax.focus)
            GLES30.glUniform2f(
                uShift,
                -frame.input.tiltX * parallax.strength * fit.scaleX,
                -frame.input.tiltY * parallax.strength * fit.scaleY,
            )
        } else {
            GLES30.glUniform1f(uHasDepth, 0f)
        }
        frame.quad.draw()
    }

    override fun release() {
        texture.release()
        depthMap?.release()
        program.release()
    }

    private fun load(pack: WallpaperPack, src: String, maxSize: Int): Texture2D {
        val bitmap = BitmapLoader.load(pack.asset(src), maxSize)
        try {
            return Texture2D(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** Depth is low-frequency; a small map is enough and keeps the lookup cheap. */
        const val DEPTH_MAP_SIZE = 1024

        const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uScale;
uniform vec2 uOffset;
out vec2 vUv;
void main() {
    vec2 uv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    vUv = (uv - 0.5) * uScale + 0.5 + uOffset;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
        const val FRAG = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
uniform sampler2D uDepth;
uniform float uHasDepth;
uniform vec2 uShift;
uniform float uFocus;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 uv = vUv;
    if (uHasDepth > 0.5) {
        // White in the map is near (layer depth 0), black is far (layer depth 1).
        float depth = 1.0 - texture(uDepth, vUv).r;
        uv += uShift * (uFocus - depth);
    }
    fragColor = texture(uTex, uv);
}
"""
    }
}
