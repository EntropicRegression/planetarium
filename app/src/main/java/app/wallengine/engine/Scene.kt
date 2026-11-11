package app.wallengine.engine

import android.opengl.GLES30
import android.util.Log
import app.wallengine.engine.gl.BitmapLoader
import app.wallengine.engine.gl.FullscreenQuad
import app.wallengine.engine.gl.ShaderProgram
import app.wallengine.engine.gl.Texture2D
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack

/** Inputs that drive a scene. Written from the main thread, read by the render thread. */
class InputState {
    /** Launcher page scroll, 0..1 (0.5 = centred). */
    @Volatile var offsetX: Float = 0.5f
}

class FrameContext(
    val width: Int,
    val height: Int,
    val timeSec: Float,
    val input: InputState,
    val quad: FullscreenQuad,
)

interface Layer {
    /** True if the layer changes over time and needs continuous rendering. */
    val animated: Boolean
    fun draw(frame: FrameContext)
    fun release()
}

/** A loaded pack: its layers, drawn back to front. All methods must run on the GL thread. */
class Scene private constructor(private val layers: List<Layer>) {
    private val quad = FullscreenQuad()

    val animated: Boolean = layers.any { it.animated }

    fun draw(width: Int, height: Int, timeSec: Float, input: InputState) {
        val frame = FrameContext(width, height, timeSec, input, quad)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        layers.forEach { it.draw(frame) }
    }

    fun release() {
        layers.forEach { it.release() }
        quad.release()
    }

    companion object {
        private const val TAG = "Scene"

        fun load(pack: WallpaperPack, maxTextureSize: Int): Scene {
            // Far layers first. A layer that fails to load is skipped rather than failing the whole pack.
            val layers = pack.layers.sortedByDescending { it.depth }.mapNotNull { spec ->
                try {
                    create(pack, spec, maxTextureSize)
                } catch (e: Exception) {
                    Log.e(TAG, "layer ${spec.src} of pack ${pack.id} failed", e)
                    null
                }
            }
            return Scene(layers)
        }

        private fun create(pack: WallpaperPack, spec: LayerSpec, maxTextureSize: Int): Layer = when (spec.type) {
            WallpaperPack.TYPE_IMAGE -> ImageLayer(pack, spec, maxTextureSize)
            else -> throw IllegalArgumentException("unknown layer type ${spec.type}")
        }
    }
}

/** A still image, scaled to cover the screen, panning horizontally with the launcher page scroll. */
class ImageLayer(pack: WallpaperPack, spec: LayerSpec, maxTextureSize: Int) : Layer {
    override val animated = false

    private val texture: Texture2D
    private val program = ShaderProgram(VERT, FRAG)
    private val uScale = program.uniform("uScale")
    private val uOffset = program.uniform("uOffset")
    private val uTex = program.uniform("uTex")

    init {
        val bitmap = BitmapLoader.load(pack.asset(spec.src), maxTextureSize)
        texture = Texture2D(bitmap)
        bitmap.recycle()
    }

    override fun draw(frame: FrameContext) {
        val imageAspect = texture.width.toFloat() / texture.height
        val viewAspect = frame.width.toFloat() / frame.height
        // Fraction of the image visible on each axis (cover fit: one axis is 1, the other < 1).
        val scaleX = if (imageAspect > viewAspect) viewAspect / imageAspect else 1f
        val scaleY = if (imageAspect > viewAspect) 1f else imageAspect / viewAspect
        // Spend the hidden horizontal part on page scrolling.
        val offsetX = (frame.input.offsetX - 0.5f) * (1f - scaleX)

        program.use()
        texture.bind(0)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform2f(uScale, scaleX, scaleY)
        GLES30.glUniform2f(uOffset, offsetX, 0f)
        frame.quad.draw()
    }

    override fun release() {
        texture.release()
        program.release()
    }

    private companion object {
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
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUv);
}
"""
    }
}
