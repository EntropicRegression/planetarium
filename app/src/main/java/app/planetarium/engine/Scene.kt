package app.planetarium.engine

import android.content.Context
import android.opengl.GLES30
import android.os.Handler
import android.util.Log
import app.planetarium.engine.gl.FullscreenQuad
import app.planetarium.pack.LayerSpec
import app.planetarium.pack.WallpaperPack

/** Inputs that drive a scene. Written from the main thread, read by the render thread. */
class InputState {
    /** Launcher page scroll, 0..1 (0.5 = centred). */
    @Volatile var offsetX: Float = 0.5f

    /** Last touch position in surface pixels, origin top-left. Keeps its value after the finger lifts. */
    @Volatile var touchX: Float = 0f
    @Volatile var touchY: Float = 0f
    @Volatile var touchDown: Boolean = false

    /**
     * Device tilt away from its resting pose, each axis -1..1 (see [TiltSensor]).
     * tiltX > 0: right edge turned away from the viewer. tiltY > 0: top edge turned towards them.
     */
    @Volatile var tiltX: Float = 0f
    @Volatile var tiltY: Float = 0f
}

/**
 * Pack-level parallax settings (manifest key "parallax"). A layer at depth d slides by
 * `tilt * strength * (focus - d)` of the screen size, so layers at the focus depth stay put and
 * layers nearer to the viewer than the focus move against the tilt.
 */
class Parallax(val strength: Float, val focus: Float) {
    /** Signed share of [strength] a layer at [depth] moves by. */
    fun factor(depth: Float): Float = focus - depth

    /** Largest slide of a layer at [depth], as a fraction of the screen: how much to overscan it. */
    fun margin(depth: Float): Float = strength * kotlin.math.abs(focus - depth)

    companion object {
        const val DEFAULT_STRENGTH = 0.04f

        fun of(pack: WallpaperPack): Parallax {
            val json = pack.manifest.optJSONObject("parallax")
            return Parallax(
                strength = (json?.optDouble("strength", DEFAULT_STRENGTH.toDouble()) ?: DEFAULT_STRENGTH.toDouble())
                    .toFloat().coerceIn(0f, 0.2f),
                focus = (json?.optDouble("focus", 1.0) ?: 1.0).toFloat().coerceIn(0f, 1f),
            )
        }
    }
}

/** UV transform for a cover-fitted, panned and parallax-shifted texture. V runs downwards. */
class CoverFit(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float)

/**
 * Cover fit for content of [aspect] (width / height), panned with the launcher page scroll and
 * zoomed in by [margin] (a screen fraction) on every side so that a parallax slide of up to
 * [margin] never shows the edge. [shift] is the slide at full tilt, as a signed screen fraction.
 */
fun coverFit(aspect: Float, frame: FrameContext, margin: Float, shift: Float): CoverFit {
    val viewAspect = frame.width.toFloat() / frame.height
    // Fraction of the content visible on each axis (cover fit: one axis is 1, the other < 1).
    val baseX = if (aspect > viewAspect) viewAspect / aspect else 1f
    val baseY = if (aspect > viewAspect) 1f else aspect / viewAspect
    val zoom = 1f / (1f + 2f * margin)
    val scaleX = baseX * zoom
    val scaleY = baseY * zoom
    // The part hidden horizontally by the fit is spent on page scrolling.
    val pan = (frame.input.offsetX - 0.5f) * (1f - baseX)
    return CoverFit(
        scaleX, scaleY,
        pan - frame.input.tiltX * shift * scaleX,
        -frame.input.tiltY * shift * scaleY,
    )
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
    /** True if the layer reads [InputState.tiltX]/[InputState.tiltY]; the motion sensor runs only then. */
    val usesTilt: Boolean get() = false
    fun draw(frame: FrameContext)
    /** Wallpaper shown/hidden. Pause decoders, sensors etc. while hidden. Called once after load too. */
    fun onVisibilityChanged(visible: Boolean) {}
    fun release()
}

/** A loaded pack: its layers, drawn back to front. All methods must run on the render thread. */
class Scene private constructor(private val layers: List<Layer>) {
    private val quad = FullscreenQuad()

    val animated: Boolean = layers.any { it.animated }
    val usesTilt: Boolean = layers.any { it.usesTilt }

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
