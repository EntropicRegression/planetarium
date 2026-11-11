package app.wallengine.engine

import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import app.wallengine.engine.gl.ShaderProgram
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack

/**
 * A looping, muted video, scaled to cover the screen and panning like [ImageLayer].
 *
 * ExoPlayer decodes into a [SurfaceTexture] backed by an external OES texture. The player's
 * application looper is the render thread's, so every player call and callback happens there.
 *
 * The layer is not [animated]: each decoded frame asks the renderer for exactly one draw, so the
 * video's own frame rate is honoured and nothing is drawn (or decoded) while playback is paused.
 */
@OptIn(UnstableApi::class)
class VideoLayer(pack: WallpaperPack, spec: LayerSpec, private val host: LayerHost) : Layer {
    override val animated = false

    private val parallax = Parallax.of(pack)
    private val depth = spec.depth
    override val usesTilt: Boolean = parallax.strength > 0f && parallax.factor(depth) != 0f

    private val program = ShaderProgram(VERT, FRAG)
    private val uScale = program.uniform("uScale")
    private val uOffset = program.uniform("uOffset")
    private val uTexMatrix = program.uniform("uTexMatrix")
    private val uTex = program.uniform("uTex")
    private val uCentre = program.uniform("uCentre")
    private val uSize = program.uniform("uSize")
    private val uRotation = program.uniform("uRotation")
    private val uCanvasAspect = program.uniform("uCanvasAspect")
    private val uOpacity = program.uniform("uOpacity")

    private enum class Blend { NORMAL, ADD, SCREEN }

    // Placement on the pack's canvas (see docs/PACK_FORMAT.md). The defaults are a video that
    // simply fills the screen.
    private val offsetX = spec.options.optDouble("x", 0.0).toFloat()
    private val offsetY = spec.options.optDouble("y", 0.0).toFloat()
    private val scale = spec.options.optDouble("scale", 1.0).toFloat().coerceIn(0.01f, 20f)
    private val opacity = spec.options.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
    private val rotationCos: Float
    private val rotationSin: Float
    private val blend = when (spec.options.optString("blend")) {
        "add" -> Blend.ADD
        "screen" -> Blend.SCREEN
        else -> Blend.NORMAL
    }
    /** Width / height of the canvas the pack was laid out on; 0 if the pack does not say. */
    private val canvasAspect: Float = pack.manifest.optJSONObject("canvas")?.let {
        val w = it.optDouble("width", 0.0)
        val h = it.optDouble("height", 0.0)
        if (w > 0 && h > 0) (w / h).toFloat() else 0f
    } ?: 0f

    init {
        val radians = Math.toRadians(spec.options.optDouble("rotation", 0.0))
        rotationCos = Math.cos(radians).toFloat()
        rotationSin = Math.sin(radians).toFloat()
    }

    private val textureId: Int
    private val surfaceTexture: SurfaceTexture
    private val surface: Surface
    private val player: ExoPlayer
    private val texMatrix = FloatArray(16)

    /** Set once the decoder has queued its first frame; until then the layer draws nothing. */
    private var hasFrame = false
    /** Displayed (rotation and pixel aspect applied) width / height; 0 while unknown. */
    private var videoAspect = 0f
    private var released = false
    private var lastRequestNanos = 0L
    private var drawPending = false

    init {
        val file = pack.asset(spec.src)
        if (!file.isFile) {
            program.release()
            throw IllegalArgumentException("video file missing: ${spec.src}")
        }
        val loop = spec.options.optBoolean("loop", true)

        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({
            if (!released) {
                hasFrame = true
                requestFrame()
            }
        }, host.handler)
        surface = Surface(surfaceTexture)

        try {
            player = ExoPlayer.Builder(host.context)
                .setLooper(host.handler.looper)
                // A local file needs no deep buffer; the default would hold many MB of samples in RAM.
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, PLAYBACK_BUFFER_MS)
                        .build(),
                )
                .build()
        } catch (e: Exception) {
            releaseGlAndSurface()
            throw e
        }
        // With the audio track disabled no audio decoder or AudioTrack is ever created.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        player.volume = 0f
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // ExoPlayer has the codec apply rotation metadata, so this is already the displayed
                // size and the rotation itself arrives through the SurfaceTexture transform matrix.
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    host.requestRender()
                }
            }

            override fun onRenderedFirstFrame() = host.requestRender()

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback failed for ${file.name}: ${error.errorCodeName}", error)
            }
        })
        player.setVideoSurface(surface)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        // Stays paused until onVisibilityChanged(true); a paused player still decodes one frame.
        player.playWhenReady = false
        player.prepare()
    }

    override fun draw(frame: FrameContext) {
        if (released || !hasFrame) return
        // Latch the newest frame. Always called (a no-op when nothing new is queued): skipping it
        // could leave a frame pending for which no further onFrameAvailable is delivered.
        try {
            surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        surfaceTexture.getTransformMatrix(texMatrix)

        val aspect = if (videoAspect > 0f) videoAspect else frame.width.toFloat() / frame.height
        // Without a declared canvas the video is the canvas, which is the plain cover-fitted,
        // page-scrolling video. With one, the video sits on that canvas like a layer in an editor.
        val canvas = if (canvasAspect > 0f) canvasAspect else aspect
        // Same cover fit, page-scroll pan and tilt parallax as an ImageLayer of the canvas' shape.
        val fit = coverFit(canvas, frame, parallax.margin(depth), parallax.strength * parallax.factor(depth))
        // Size at scale 1 is the smallest that covers the canvas.
        val sizeX = if (aspect > canvas) scale * aspect / canvas else scale
        val sizeY = if (aspect > canvas) scale else scale * canvas / aspect

        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform2f(uScale, fit.scaleX, fit.scaleY)
        GLES30.glUniform2f(uOffset, fit.offsetX, fit.offsetY)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform2f(uCentre, 0.5f + offsetX, 0.5f + offsetY)
        GLES30.glUniform2f(uSize, sizeX, sizeY)
        GLES30.glUniform2f(uRotation, rotationCos, rotationSin)
        GLES30.glUniform1f(uCanvasAspect, canvas)
        GLES30.glUniform1f(uOpacity, opacity)
        when (blend) {
            Blend.ADD -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            Blend.SCREEN -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_COLOR)
            Blend.NORMAL -> {}
        }
        frame.quad.draw()
        if (blend != Blend.NORMAL) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Asks for a draw for a newly decoded frame, at most [MAX_FPS] times a second: a 60 fps video
     * would otherwise redraw the whole scene twice as often as the wallpaper needs. A frame that
     * arrives too early is not lost, it is drawn when its turn comes. Render thread.
     */
    private fun requestFrame() {
        if (drawPending) return
        val wait = lastRequestNanos + 1_000_000_000L / MAX_FPS - System.nanoTime()
        if (wait <= 0) {
            lastRequestNanos = System.nanoTime()
            host.requestRender()
        } else {
            drawPending = true
            host.handler.postDelayed({
                drawPending = false
                if (!released) {
                    lastRequestNanos = System.nanoTime()
                    host.requestRender()
                }
            }, wait / 1_000_000)
        }
    }

    override fun onVisibilityChanged(visible: Boolean) {
        if (released) return
        if (visible) {
            // A non-looping video that reached its end stays on its last frame.
            player.play()
            host.requestRender()
        } else {
            player.pause()
        }
    }

    override fun release() {
        if (released) return
        released = true
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "player release failed", e)
        }
        releaseGlAndSurface()
    }

    /** Safe without a current GL context: the GL deletes are then no-ops, the rest is not GL. */
    private fun releaseGlAndSurface() {
        surfaceTexture.setOnFrameAvailableListener(null)
        surface.release()
        surfaceTexture.release()
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        program.release()
    }

    private companion object {
        const val TAG = "VideoLayer"
        const val MAX_FPS = 30
        const val MIN_BUFFER_MS = 2_000
        const val MAX_BUFFER_MS = 5_000
        const val PLAYBACK_BUFFER_MS = 250

        // The vertex shader maps the screen to the pack's canvas (cover fit, page-scroll pan, tilt
        // parallax), exactly like an image layer's texture coordinates. The fragment shader then
        // finds where that canvas point falls inside the video's own rectangle.
        const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uScale;
uniform vec2 uOffset;
out vec2 vCanvas;
void main() {
    vec2 uv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    vCanvas = (uv - 0.5) * uScale + 0.5 + uOffset;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
        const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform mat4 uTexMatrix;
uniform vec2 uCentre;        // of the video on the canvas, 0..1, v downwards
uniform vec2 uSize;          // of the video, as fractions of the canvas width and height
uniform vec2 uRotation;      // cos, sin of the clockwise rotation
uniform float uCanvasAspect;
uniform float uOpacity;
in vec2 vCanvas;
out vec4 fragColor;
void main() {
    // Work in units of canvas height so that rotation does not shear.
    vec2 d = vCanvas - uCentre;
    d.x *= uCanvasAspect;
    vec2 p = vec2(d.x * uRotation.x + d.y * uRotation.y, d.y * uRotation.x - d.x * uRotation.y);
    vec2 uv = vec2(p.x / (uSize.x * uCanvasAspect), p.y / uSize.y) + 0.5;
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0);
        return;
    }
    // The SurfaceTexture matrix expects GL texture space, whose v runs upwards.
    vec2 t = (uTexMatrix * vec4(uv.x, 1.0 - uv.y, 0.0, 1.0)).xy;
    fragColor = vec4(texture(uTex, t).rgb * uOpacity, uOpacity);
}
"""
    }
}
