package app.wallengine.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import android.util.Log
import app.wallengine.engine.ShaderConfig.Channel
import app.wallengine.engine.gl.BitmapLoader
import app.wallengine.engine.gl.FullscreenQuad
import app.wallengine.engine.gl.ShaderProgram
import app.wallengine.engine.gl.Texture2D
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack
import kotlin.math.roundToInt

/**
 * A user-supplied fragment shader drawn on a fullscreen quad. See docs/PACK_FORMAT.md for the
 * authoring contract (uniforms, channels, feedback, params).
 *
 * Drawn straight to the screen unless a channel is "self" (ping-pong feedback buffers) or the
 * render scale is below 1 (one offscreen buffer, upscaled).
 */
class ShaderLayer(pack: WallpaperPack, spec: LayerSpec, host: LayerHost, maxTextureSize: Int) : Layer {
    private val tag = "ShaderLayer(${spec.src})"
    private val config = ShaderConfig(pack, spec)
    private val hasSelf = config.channels.any { it is Channel.Self }

    // Throws ShaderException with the compiler log if the author's shader is broken.
    private val program = ShaderProgram(ShaderSource.VERTEX, config.fragment)
    private val textures = arrayOfNulls<Texture2D>(ShaderConfig.MAX_CHANNELS)

    private val uResolution = program.uniform("iResolution")
    private val uTime = program.uniform("iTime")
    private val uTimeDelta = program.uniform("iTimeDelta")
    private val uFrame = program.uniform("iFrame")
    private val uMouse = program.uniform("iMouse")
    private val uChannels = IntArray(ShaderConfig.MAX_CHANNELS) { program.uniform("iChannel$it") }
    private val uChannelResolution = program.uniform("iChannelResolution")
    private val uOffset = program.uniform("uOffset")
    private val uTouch = program.uniform("uTouch")
    private val uPass = program.uniform("uPass")
    private val uTilt = program.uniform("uTilt")
    override val usesTilt: Boolean = uTilt >= 0
    private val paramUniforms = config.params.map { program.uniform(it.id) to it.value }.filter { it.first >= 0 }

    /** A shader that reads neither the clock nor its own previous frame is redrawn only on input. */
    override val animated = uTime >= 0 || uTimeDelta >= 0 || uFrame >= 0 || hasSelf

    private var blit: ShaderProgram? = null
    private var uBlitTex = -1
    private var targets: List<RenderTarget> = emptyList()
    /** Index of the target holding the newest frame. */
    private var current = 0
    private var fboBroken = false

    private var frameCount = 0
    private var lastTimeSec = Float.NaN
    private var timeDelta = 1f / 30f
    private val channelResolution = FloatArray(ShaderConfig.MAX_CHANNELS * 3)

    // Pointer state in surface pixels (origin top-left), turned into iMouse / uTouch per pass.
    private var pointerSeen = false
    private var lastX = 0f
    private var lastY = 0f
    private var wasPressed = false
    private var everPressed = false
    private var pressed = false
    private var clickFrame = false
    private var pressX = 0f
    private var pressY = 0f

    // Performance guard, see [watchFrameRate].
    private var guardFactor = 1f
    private var lastDrawNanos = 0L
    private var warmup = WARMUP_FRAMES
    private var samples = 0
    private var slowSamples = 0

    init {
        try {
            config.channels.forEachIndexed { i, channel ->
                if (channel is Channel.Image) textures[i] = loadTexture(channel, maxTextureSize)
            }
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    override fun onVisibilityChanged(visible: Boolean) {
        // The gap while hidden is not a slow frame.
        lastDrawNanos = 0L
    }

    override fun draw(frame: FrameContext) {
        if (animated) watchFrameRate()
        updateClock(frame.timeSec)
        updatePointer(frame.input)

        val scale = (config.scale * guardFactor).coerceIn(ShaderConfig.MIN_SCALE, 1f)
        val offscreen = (hasSelf || scale < 1f) && !fboBroken &&
            ensureTargets(
                (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1),
                frame.quad,
            )

        if (!offscreen) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, frame.width, frame.height)
            GLES30.glEnable(GLES30.GL_BLEND)
            if (config.additive) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            render(frame, frame.width, frame.height, 0, 0, 0, 0)
        } else {
            val source = targets[current]
            val dest = if (hasSelf) targets[1 - current] else targets[0]
            // The buffer stores exactly what the shader wrote: no blending, no dithering.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dest.framebuffer)
            GLES30.glViewport(0, 0, dest.width, dest.height)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glDisable(GLES30.GL_DITHER)
            if (!hasSelf) {
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            render(frame, dest.width, dest.height, 0, source.texture, source.width, source.height)
            if (hasSelf) current = 1 - current

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, frame.width, frame.height)
            GLES30.glEnable(GLES30.GL_DITHER)
            GLES30.glEnable(GLES30.GL_BLEND)
            if (config.additive) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            if (hasSelf && uPass >= 0) {
                // The shader presents its own buffer: second pass, full resolution, uPass = 1.
                render(frame, frame.width, frame.height, 1, dest.texture, dest.width, dest.height)
            } else {
                blit(dest.texture, frame.quad)
            }
        }
        if (config.additive) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        frameCount++
    }

    /** One fullscreen pass of the user program into the bound framebuffer of [width] x [height]. */
    private fun render(frame: FrameContext, width: Int, height: Int, pass: Int, selfTex: Int, selfW: Int, selfH: Int) {
        program.use()
        val sx = width.toFloat() / frame.width
        val sy = height.toFloat() / frame.height
        if (uResolution >= 0) GLES30.glUniform3f(uResolution, width.toFloat(), height.toFloat(), 1f)
        if (uTime >= 0) GLES30.glUniform1f(uTime, frame.timeSec)
        if (uTimeDelta >= 0) GLES30.glUniform1f(uTimeDelta, timeDelta)
        if (uFrame >= 0) GLES30.glUniform1i(uFrame, frameCount)
        if (uMouse >= 0) {
            if (everPressed) {
                // Shadertoy: xy = last position while pressed, zw = where the press started;
                // z > 0 while pressed, w > 0 only on the frame the press began. Origin bottom-left.
                val startX = pressX * sx
                val startY = (frame.height - pressY) * sy
                GLES30.glUniform4f(
                    uMouse,
                    lastX * sx,
                    (frame.height - lastY) * sy,
                    if (pressed) startX else -startX,
                    if (clickFrame) startY else -startY,
                )
            } else {
                GLES30.glUniform4f(uMouse, 0f, 0f, 0f, 0f)
            }
        }
        if (uOffset >= 0) GLES30.glUniform1f(uOffset, frame.input.offsetX)
        if (uTilt >= 0) GLES30.glUniform2f(uTilt, frame.input.tiltX, frame.input.tiltY)
        if (uTouch >= 0) {
            if (everPressed) {
                GLES30.glUniform3f(uTouch, lastX / frame.width, 1f - lastY / frame.height, if (pressed) 1f else 0f)
            } else {
                GLES30.glUniform3f(uTouch, 0.5f, 0.5f, 0f)
            }
        }
        if (uPass >= 0) GLES30.glUniform1i(uPass, pass)
        paramUniforms.forEach { (location, value) -> GLES30.glUniform1f(location, value) }

        for (i in 0 until ShaderConfig.MAX_CHANNELS) {
            var texId = 0
            var w = 0
            var h = 0
            when (config.channels[i]) {
                is Channel.Self -> {
                    texId = selfTex
                    w = selfW
                    h = selfH
                }
                is Channel.Image -> textures[i]?.let {
                    texId = it.id
                    w = it.width
                    h = it.height
                }
                Channel.None -> {}
            }
            channelResolution[i * 3] = w.toFloat()
            channelResolution[i * 3 + 1] = h.toFloat()
            channelResolution[i * 3 + 2] = 1f
            if (uChannels[i] < 0) continue
            // Unused channels get "no texture" (samples as black) rather than another layer's leftovers.
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
            GLES30.glUniform1i(uChannels[i], i)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        if (uChannelResolution >= 0) {
            GLES30.glUniform3fv(uChannelResolution, ShaderConfig.MAX_CHANNELS, channelResolution, 0)
        }
        frame.quad.draw()
    }

    private fun blit(texture: Int, quad: FullscreenQuad) {
        val p = blit ?: ShaderProgram(ShaderSource.VERTEX, BLIT_FRAGMENT).also {
            blit = it
            uBlitTex = it.uniform("uTex")
        }
        p.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(uBlitTex, 0)
        quad.draw()
    }

    /** Makes sure the offscreen buffers exist at this size. False if the GPU refuses them. */
    private fun ensureTargets(width: Int, height: Int, quad: FullscreenQuad): Boolean {
        val old = targets
        if (old.isNotEmpty() && old[0].width == width && old[0].height == height) return true
        val fresh = List(if (hasSelf) 2 else 1) { RenderTarget(width, height) }
        if (fresh.any { !it.complete }) {
            Log.w(tag, "offscreen buffer ${width}x$height unsupported; drawing directly")
            (fresh + old).forEach { it.release() }
            targets = emptyList()
            fboBroken = true
            return false
        }
        if (hasSelf && old.isNotEmpty()) {
            // Carry the feedback state over to the new size. Nearest, so that shaders which pack
            // data into the channels still read values they once wrote.
            val from = old[current]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fresh[0].framebuffer)
            GLES30.glViewport(0, 0, width, height)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, from.texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            blit(from.texture, quad)
        }
        old.forEach { it.release() }
        targets = fresh
        current = 0
        return true
    }

    private fun updateClock(timeSec: Float) {
        // Clamped so that a simulation does not explode on the first frame after being hidden.
        timeDelta = if (lastTimeSec.isNaN()) 1f / 30f else (timeSec - lastTimeSec).coerceIn(0f, 0.1f)
        lastTimeSec = timeSec
    }

    private fun updatePointer(input: InputState) {
        val down = input.touchDown
        val x = input.touchX
        val y = input.touchY
        // A tap can begin and end between two frames; the position still moves, so treat that
        // as a one-frame press instead of missing it.
        val tapped = !down && pointerSeen && (x != lastX || y != lastY)
        pressed = down || tapped
        clickFrame = pressed && !wasPressed
        if (clickFrame) {
            pressX = x
            pressY = y
            everPressed = true
        }
        wasPressed = pressed
        lastX = x
        lastY = y
        pointerSeen = true
    }

    /**
     * Performance guard. The renderer draws animated scenes at 30 fps; when the GPU cannot keep
     * up, buffer swaps back up and the gaps between draws grow. If most frames of a two-second
     * window arrive later than [SLOW_FRAME_NANOS], the render scale drops by a quarter, down to
     * the minimum. It never goes back up, so it cannot oscillate.
     */
    private fun watchFrameRate() {
        val now = System.nanoTime()
        val gap = now - lastDrawNanos
        val first = lastDrawNanos == 0L
        lastDrawNanos = now
        if (first || gap > PAUSE_NANOS) {
            // Not a steady stream of frames (just shown, or something else stalled): start over.
            warmup = maxOf(warmup, RESUME_FRAMES)
            samples = 0
            slowSamples = 0
            return
        }
        if (warmup > 0) {
            warmup--
            return
        }
        samples++
        if (gap > SLOW_FRAME_NANOS) slowSamples++
        if (samples < WINDOW_FRAMES) return
        val slow = slowSamples
        samples = 0
        slowSamples = 0
        val scale = config.scale * guardFactor
        if (slow * 10 < WINDOW_FRAMES * 6 || scale <= ShaderConfig.MIN_SCALE || fboBroken) return
        guardFactor = (scale * GUARD_STEP).coerceAtLeast(ShaderConfig.MIN_SCALE) / config.scale
        warmup = WARMUP_FRAMES
        Log.w(tag, "$slow of $WINDOW_FRAMES frames were late; render scale $scale -> ${config.scale * guardFactor}")
    }

    private fun loadTexture(channel: Channel.Image, maxTextureSize: Int): Texture2D {
        val bitmap = BitmapLoader.load(channel.file, maxTextureSize)
        // Shadertoy convention: v = 0 is the bottom row of the image.
        val flipped = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { preScale(1f, -1f) }, false,
        )
        if (flipped !== bitmap) bitmap.recycle()
        val texture = Texture2D(flipped)
        flipped.recycle()
        // Texture2D leaves the texture bound. Tile it, and use mipmaps if the format allows.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        if (GLES30.glGetError() == GLES30.GL_NO_ERROR) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        }
        return texture
    }

    override fun release() {
        program.release()
        blit?.release()
        blit = null
        textures.forEach { it?.release() }
        textures.fill(null)
        targets.forEach { it.release() }
        targets = emptyList()
    }

    /** An RGBA8 texture with a framebuffer around it, cleared to transparent black. */
    private class RenderTarget(val width: Int, val height: Int) {
        val texture: Int
        val framebuffer: Int
        val complete: Boolean

        init {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
            )
            complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            if (complete) {
                GLES30.glClearColor(0f, 0f, 0f, 0f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
        }

        fun release() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private companion object {
        /** Later than this (about 22 fps) counts as a missed frame at the renderer's 30 fps. */
        const val SLOW_FRAME_NANOS = 45_000_000L
        /** A gap this long is a pause, not a slow frame. */
        const val PAUSE_NANOS = 1_000_000_000L
        const val WINDOW_FRAMES = 60
        const val WARMUP_FRAMES = 30
        const val RESUME_FRAMES = 10
        const val GUARD_STEP = 0.75f

        const val BLIT_FRAGMENT = """#version 300 es
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
