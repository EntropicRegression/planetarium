package app.wallengine.engine

import android.content.Context
import android.opengl.GLES30
import android.os.Handler
import android.util.Log
import app.wallengine.engine.gl.FullscreenQuad
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack

/** Inputs that drive a scene. Written from the main thread, read by the render thread. */
class InputState {
    /** Launcher page scroll, 0..1 (0.5 = centred). */
    @Volatile var offsetX: Float = 0.5f

    /** Last touch position in surface pixels, origin top-left. Keeps its value after the finger lifts. */
    @Volatile var touchX: Float = 0f
    @Volatile var touchY: Float = 0f
    @Volatile var touchDown: Boolean = false
}

class FrameContext(
    val width: Int,
    val height: Int,
    /** Seconds since the renderer started; keeps counting while the wallpaper is hidden. */
    val timeSec: Float,
    val input: InputState,
    val quad: FullscreenQuad,
)

/** What a layer can reach outside itself. */
class LayerHost(
    val context: Context,
    /** Handler of the render thread (it has a Looper, so players/listeners can be bound to it). */
    val handler: Handler,
    /** Asks for one more frame; needed only by layers that are not [Layer.animated]. Any thread. */
    val requestRender: () -> Unit,
)

/**
 * One drawable layer. Created, drawn and released on the render thread with the GL context
 * current (except [release], which may also run after the surface is gone: GL deletes are then
 * no-ops, but non-GL resources such as players must still be freed).
 */
interface Layer {
    /** True if the layer changes over time and needs continuous rendering. */
    val animated: Boolean
    fun draw(frame: FrameContext)
    /** Wallpaper shown/hidden. Pause decoders, sensors etc. while hidden. Called once after load too. */
    fun onVisibilityChanged(visible: Boolean) {}
    fun release()
}

/** A loaded pack: its layers, drawn back to front. All methods must run on the render thread. */
class Scene private constructor(private val layers: List<Layer>) {
    private val quad = FullscreenQuad()

    val animated: Boolean = layers.any { it.animated }

    fun draw(width: Int, height: Int, timeSec: Float, input: InputState) {
        val frame = FrameContext(width, height, timeSec, input, quad)
        layers.forEach {
            // Layers may leave GL state changed; reset what the next one relies on.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            it.draw(frame)
        }
    }

    fun onVisibilityChanged(visible: Boolean) = layers.forEach { it.onVisibilityChanged(visible) }

    fun release() {
        layers.forEach {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "layer release failed", e)
            }
        }
        quad.release()
    }

    companion object {
        private const val TAG = "Scene"

        fun load(pack: WallpaperPack, host: LayerHost, maxTextureSize: Int): Scene {
            // Far layers first. A layer that fails to load is skipped rather than failing the whole pack.
            val layers = pack.layers.sortedByDescending { it.depth }.mapNotNull { spec ->
                try {
                    create(pack, spec, host, maxTextureSize)
                } catch (e: Exception) {
                    Log.e(TAG, "layer ${spec.src} of pack ${pack.id} failed", e)
                    null
                }
            }
            return Scene(layers)
        }

        private fun create(pack: WallpaperPack, spec: LayerSpec, host: LayerHost, maxTextureSize: Int): Layer =
            when (spec.type) {
                WallpaperPack.TYPE_IMAGE -> ImageLayer(pack, spec, maxTextureSize)
                WallpaperPack.TYPE_VIDEO -> VideoLayer(pack, spec, host)
                WallpaperPack.TYPE_SHADER -> ShaderLayer(pack, spec, host, maxTextureSize)
                else -> throw IllegalArgumentException("unknown layer type ${spec.type}")
            }
    }
}
