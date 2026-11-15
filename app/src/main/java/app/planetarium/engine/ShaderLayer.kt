package app.planetarium.engine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES30
import android.util.Log
import app.planetarium.engine.ShaderConfig.Channel
import app.planetarium.engine.gl.BitmapLoader
import app.planetarium.engine.gl.FullscreenQuad
import app.planetarium.engine.gl.ShaderProgram
import app.planetarium.engine.gl.Texture2D
import app.planetarium.pack.LayerSpec
import app.planetarium.pack.WallpaperPack
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A user-supplied fragment shader drawn on a fullscreen quad. See docs/PACK_FORMAT.md for the
 * authoring contract (uniforms, channels, feedback, params).
 *
 * Drawn straight to the screen unless a channel is "self" (ping-pong feedback buffers) or the
 * render scale is below 1 (one offscreen buffer, upscaled). The offscreen pass happens in
 * [prepare], ahead of the frame.
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
    private val uOrigin = program.uniform(ShaderSource.ORIGIN)
    /**
     * Shape of the canvas the pack was laid out on. A feedback shader reads its own buffer by
     * screen position, so it keeps drawing on the screen itself.
     */
    private val canvasAspect: Float? = if (hasSelf) null else canvasAspect(pack)
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
    /** [prepare] ran for the frame being drawn. */
    private var prepared = false
    /** The buffer [prepare] rendered this frame, which [draw] puts on the screen; null if none. */
    private var presented: RenderTarget? = null

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

    /**
     * The offscreen pass, if the layer has one. Done before anything is drawn to the screen:
     * leaving the screen's framebuffer halfway through a frame makes a phone's GPU write out
     * everything drawn so far and read it back in, which costs as much as a full-screen layer.
     */
    override fun prepare(frame: FrameContext) {
        prepared = true
        if (animated) watchFrameRate(frame.targetFps)
        updateClock(frame.timeSec)
        updatePointer(frame.input)

        presented = null
        val scale = (config.scale * guardFactor).coerceIn(ShaderConfig.MIN_SCALE, 1f)
        val offscreen = (hasSelf || scale < 1f) && !fboBroken &&
            ensureTargets(
                (frame.width * scale).roundToInt().coerceAtLeast(1),
                (frame.height * scale).roundToInt().coerceAtLeast(1),
                frame.quad,
            )
        if (!offscreen) return

        val source = targets[current]
        val dest = if (hasSelf) targets[1 - current] else targets[0]
        // The buffer stores exactly what the shader wrote: no blending, no dithering.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dest.framebuffer)
        GLES30.glViewport(0, 0, dest.width, dest.height)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DITHER)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if (!hasSelf) {
            // The whole buffer, so the GPU has nothing of the previous frame to bring back.
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        clipToRegion(dest.width, dest.height)
        render(frame, dest.width, dest.height, 0, source.texture, source.width, source.height)
        if (hasSelf) current = 1 - current
        presented = dest

        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glEnable(GLES30.GL_DITHER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, frame.width, frame.height)
    }

    override fun draw(frame: FrameContext) {
        // A caller that draws layers one by one without the preparing round still gets a frame.
        if (!prepared) prepare(frame)
        prepared = false

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, frame.width, frame.height)
        GLES30.glEnable(GLES30.GL_BLEND)
        if (config.additive) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        clipToRegion(frame.width, frame.height)
        val buffer = presented
        when {
            buffer == null -> render(frame, frame.width, frame.height, 0, 0, 0, 0)
            // The shader presents its own buffer: second pass, full resolution, uPass = 1.
            hasSelf && uPass >= 0 -> render(frame, frame.width, frame.height, 1, buffer.texture, buffer.width, buffer.height)
            else -> blit(buffer.texture, frame.quad)
        }
        if (config.additive) GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        frameCount++
    }

    /** The canvas the shader draws on when its target is [width] x [height]: see [render]. */
    private fun canvasSize(width: Int, height: Int): Pair<Float, Float> {
        var canvasW = width.toFloat()
        var canvasH = height.toFloat()
        val aspect = canvasAspect
        if (uOrigin >= 0 && aspect != null) {
            if (aspect > canvasW / canvasH) canvasW = canvasH * aspect else canvasH = canvasW / aspect
        }
        return canvasW to canvasH
    }

    /**
     * Limits drawing into a target of [width] x [height] to the layer's region, so the pixels the
     * shader would leave empty are not processed at all: on a phone a full-screen pass costs
     * about the same whatever the shader does. A feedback shader keeps the whole target.
     */
    private fun clipToRegion(width: Int, height: Int) {
        val region = config.region
        if (region == null || hasSelf) {
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            return
        }
        val (canvasW, canvasH) = canvasSize(width, height)
        val originX = (canvasW - width) / 2f
        val originY = (canvasH - height) / 2f
        // One pixel more on every side, for the upscaling filter. GL counts rows from the bottom.
        val left = (floor(region[0] * canvasW - originX).toInt() - 1).coerceIn(0, width)
        val right = (ceil(region[2] * canvasW - originX).toInt() + 1).coerceIn(0, width)
        val bottom = (floor((1f - region[3]) * canvasH - originY).toInt() - 1).coerceIn(0, height)
        val top = (ceil((1f - region[1]) * canvasH - originY).toInt() + 1).coerceIn(0, height)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(left, bottom, right - left, top - bottom)
    }

    /** One fullscreen pass of the user program into the bound framebuffer of [width] x [height]. */
    private fun render(frame: FrameContext, width: Int, height: Int, pass: Int, selfTex: Int, selfW: Int, selfH: Int) {
        program.use()
        val sx = width.toFloat() / frame.width
        val sy = height.toFloat() / frame.height
        // The canvas the shader draws on: the screen, or the pack's own canvas scaled to cover
        // the screen, of which the screen then shows the middle (like the pack's pictures).
        val (canvasW, canvasH) = canvasSize(width, height)
        val originX = (canvasW - width) / 2f
        val originY = (canvasH - height) / 2f
        if (uOrigin >= 0) GLES30.glUniform2f(uOrigin, originX, originY)
        if (uResolution >= 0) GLES30.glUniform3f(uResolution, canvasW, canvasH, 1f)
        if (uTime >= 0) GLES30.glUniform1f(uTime, frame.timeSec)
        if (uTimeDelta >= 0) GLES30.glUniform1f(uTimeDelta, timeDelta)
        if (uFrame >= 0) GLES30.glUniform1i(uFrame, frameCount)
        if (uMouse >= 0) {
            if (everPressed) {
                // Shadertoy: xy = last position while pressed, zw = where the press started;
                // z > 0 while pressed, w > 0 only on the frame the press began. Origin bottom-left.
                val startX = pressX * sx + originX
                val startY = (frame.height - pressY) * sy + originY
                GLES30.glUniform4f(
                    uMouse,
                    lastX * sx + originX,
                    (frame.height - lastY) * sy + originY,
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
                GLES30.glUniform3f(
                    uTouch,
                    (lastX * sx + originX) / canvasW,
                    ((frame.height - lastY) * sy + originY) / canvasH,
                    if (pressed) 1f else 0f,
                )
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
     * Performance guard. The renderer draws animated scenes at a set rate; when the GPU cannot
     * keep up, buffer swaps back up and the gaps between draws grow. If most frames of a window
     * of [WINDOW_FRAMES] arrive [SLOW_FRAME_FACTOR] frame intervals or more after the last, the
     * render scale drops by a quarter, down to
     * the minimum. It never goes back up, so it cannot oscillate.
     */
    private fun watchFrameRate(targetFps: Int) {
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
        // Late means late for the rate the renderer is asked to keep, which the person can lower.
        if (gap > 1_000_000_000L / targetFps.coerceIn(1, 240) * SLOW_FRAME_FACTOR) slowSamples++
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
        /** A frame this many times its interval after the last one counts as missed (45 ms at 30 fps). */
        const val SLOW_FRAME_FACTOR = 1.35f
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
