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
                host.requestRender()
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
        // Same cover fit, page-scroll pan and tilt parallax as ImageLayer.
        val fit = coverFit(aspect, frame, parallax.margin(depth), parallax.strength * parallax.factor(depth))

        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glUniform2f(uScale, fit.scaleX, fit.scaleY)
        // This shader's V runs upwards (the SurfaceTexture matrix flips it), CoverFit's downwards.
        GLES30.glUniform2f(uOffset, fit.offsetX, -fit.offsetY)
        GLES30.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        frame.quad.draw()
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
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
        const val MIN_BUFFER_MS = 2_000
        const val MAX_BUFFER_MS = 5_000
        const val PLAYBACK_BUFFER_MS = 250

        // uv starts in GL texture space (origin bottom-left), as the SurfaceTexture matrix expects.
        // Cover-fit scale and pan are symmetric about the centre / horizontal only, so they can be
        // applied before the matrix.
        const val VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uScale;
uniform vec2 uOffset;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    uv = (uv - 0.5) * uScale + 0.5 + uOffset;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
        const val FRAG = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(uTex, vUv).rgb, 1.0);
}
"""
    }
}
