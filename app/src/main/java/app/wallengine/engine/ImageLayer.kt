package app.wallengine.engine

import android.graphics.BitmapFactory
import android.opengl.GLES30
import app.wallengine.engine.gl.BitmapLoader
import app.wallengine.engine.gl.ShaderProgram
import app.wallengine.engine.gl.Texture2D
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.WallpaperPack
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A still image, scaled to cover the screen, panning horizontally with the launcher page scroll
 * and sliding with device tilt according to its depth.
 *
 * With the option "depthMap" (a greyscale image, white = near) the image is instead displaced
 * per pixel by its depth, which gives one photo continuous parallax.
 *
 * With the option "sprites" small animated patches replace parts of the image: each is a strip of
 * frames side by side, placed at a pixel position of the image, so a mostly still picture can
 * have a few moving details without becoming a video. See docs/PACK_FORMAT.md.
 */
class ImageLayer(pack: WallpaperPack, private val spec: LayerSpec, maxTextureSize: Int) : Layer {
    private val parallax = Parallax.of(pack)
    private val texture: Texture2D
    private val depthMap: Texture2D?
    private val sprites: List<Sprite>

    private val program = ShaderProgram(VERT, FRAG)
    private val uScale = program.uniform("uScale")
    private val uOffset = program.uniform("uOffset")
    private val uTex = program.uniform("uTex")
    private val uDepth = program.uniform("uDepth")
    private val uHasDepth = program.uniform("uHasDepth")
    private val uShift = program.uniform("uShift")
    private val uFocus = program.uniform("uFocus")
    private val uSprites = IntArray(MAX_SPRITES) { program.uniform("uSprite$it") }
    private val uSpriteRects = IntArray(MAX_SPRITES) { program.uniform("uSpriteRect$it") }
    private val uSpriteFrames = IntArray(MAX_SPRITES) { program.uniform("uSpriteFrame$it") }

    init {
        texture = load(pack, spec.src, maxTextureSize)
        depthMap = try {
            spec.options.optString("depthMap").ifEmpty { null }?.let { load(pack, it, DEPTH_MAP_SIZE) }
        } catch (e: Exception) {
            texture.release()
            program.release()
            throw e
        }
        sprites = try {
            loadSprites(pack, maxTextureSize)
        } catch (e: Exception) {
            texture.release()
            depthMap?.release()
            program.release()
            throw e
        }
    }

    /** Sprites change with the clock, so the scene has to keep drawing. */
    override val animated = sprites.isNotEmpty()

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
        for (i in 0 until MAX_SPRITES) {
            val sprite = sprites.getOrNull(i)
            // Unused slots still need a texture unit of their own; a frame count of 0 switches them off.
            GLES30.glUniform1i(uSprites[i], SPRITE_UNIT + i)
            if (sprite == null) {
                GLES30.glUniform2f(uSpriteFrames[i], 0f, 0f)
                continue
            }
            sprite.texture.bind(SPRITE_UNIT + i)
            GLES30.glUniform4f(uSpriteRects[i], sprite.u, sprite.v, sprite.w, sprite.h)
            GLES30.glUniform2f(uSpriteFrames[i], sprite.frameAt(frame.timeSec).toFloat(), sprite.frames.toFloat())
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        frame.quad.draw()
    }

    override fun release() {
        texture.release()
        depthMap?.release()
        sprites.forEach { it.texture.release() }
        program.release()
    }

    private fun loadSprites(pack: WallpaperPack, maxTextureSize: Int): List<Sprite> {
        val array = spec.options.optJSONArray("sprites") ?: return emptyList()
        // Positions are given in pixels of the image as authored, which may be larger than the texture.
        val (imageW, imageH) = imageSize(pack.asset(spec.src))
        val loaded = ArrayList<Sprite>()
        try {
            for (i in 0 until minOf(array.length(), MAX_SPRITES)) {
                val json = array.optJSONObject(i) ?: continue
                val file = pack.asset(json.getString("src"))
                val frames = json.optInt("frames", 1).coerceAtLeast(1)
                val (sheetW, sheetH) = imageSize(file)
                val texture = load(pack, json.getString("src"), maxTextureSize)
                // Texture2D leaves the texture bound. Nearest, so neighbouring frames never bleed in.
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                loaded += Sprite(
                    texture = texture,
                    frames = frames,
                    u = json.optDouble("x", 0.0).toFloat() / imageW,
                    v = json.optDouble("y", 0.0).toFloat() / imageH,
                    w = sheetW.toFloat() / frames / imageW,
                    h = sheetH.toFloat() / imageH,
                    play = json.optString("play", "loop"),
                    fps = json.optDouble("fps", 8.0).toFloat().coerceIn(0.1f, 60f),
                    period = json.optDouble("period", 2.0).toFloat().coerceAtLeast(0.1f),
                    every = json.optDouble("every", 6.0).toFloat().coerceAtLeast(0.1f),
                )
            }
        } catch (e: Exception) {
            loaded.forEach { it.texture.release() }
            throw e
        }
        return loaded
    }

    private fun imageSize(file: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image: ${file.name}" }
        return bounds.outWidth to bounds.outHeight
    }

    /** One animated patch: where it sits on the image (as UV fractions) and how its frames play. */
    private class Sprite(
        val texture: Texture2D,
        val frames: Int,
        val u: Float,
        val v: Float,
        val w: Float,
        val h: Float,
        val play: String,
        val fps: Float,
        val period: Float,
        val every: Float,
    ) {
        fun frameAt(timeSec: Float): Int {
            if (frames <= 1) return 0
            val t = timeSec.toDouble()
            val index = when (play) {
                // Back and forth through the frames, easing at both ends like a pendulum.
                "swing" -> ((0.5 - 0.5 * cos(2.0 * PI * t / period)) * (frames - 1)).roundToInt()
                // Rests on frame 0; once per [every] seconds, at a different moment each time,
                // the remaining frames play through once.
                "sometimes" -> {
                    val duration = (frames - 1) / fps.toDouble()
                    val slot = floor(t / every)
                    val noise = sin(slot * 12.9898) * 43758.5453
                    val jitter = noise - floor(noise)
                    val into = t - (slot * every + jitter * (every - duration).coerceAtLeast(0.0))
                    if (into < 0.0 || into >= duration) 0 else 1 + (into * fps).toInt()
                }
                else -> (t * fps).toLong().mod(frames.toLong()).toInt()
            }
            return index.coerceIn(0, frames - 1)
        }
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
        const val MAX_SPRITES = 4
        /** Texture units 0 and 1 are the image and its depth map. */
        const val SPRITE_UNIT = 2

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
uniform sampler2D uSprite0;
uniform sampler2D uSprite1;
uniform sampler2D uSprite2;
uniform sampler2D uSprite3;
uniform vec4 uSpriteRect0;
uniform vec4 uSpriteRect1;
uniform vec4 uSpriteRect2;
uniform vec4 uSpriteRect3;
// x = frame to show, y = frames in the strip (0: slot unused).
uniform vec2 uSpriteFrame0;
uniform vec2 uSpriteFrame1;
uniform vec2 uSpriteFrame2;
uniform vec2 uSpriteFrame3;
in vec2 vUv;
out vec4 fragColor;

// Inside the sprite's rectangle its current frame replaces the image, transparency included.
vec4 sprite(sampler2D tex, vec4 rect, vec2 frame, vec2 uv, vec4 base) {
    vec2 p = (uv - rect.xy) / rect.zw;
    if (frame.y < 0.5 || p.x < 0.0 || p.x >= 1.0 || p.y < 0.0 || p.y >= 1.0) return base;
    return texture(tex, vec2((p.x + frame.x) / frame.y, p.y));
}

void main() {
    vec2 uv = vUv;
    if (uHasDepth > 0.5) {
        // White in the map is near (layer depth 0), black is far (layer depth 1).
        float depth = 1.0 - texture(uDepth, vUv).r;
        uv += uShift * (uFocus - depth);
    }
    vec4 color = texture(uTex, uv);
    color = sprite(uSprite0, uSpriteRect0, uSpriteFrame0, uv, color);
    color = sprite(uSprite1, uSpriteRect1, uSpriteFrame1, uv, color);
    color = sprite(uSprite2, uSpriteRect2, uSpriteFrame2, uv, color);
    color = sprite(uSprite3, uSpriteRect3, uSpriteFrame3, uv, color);
    fragColor = color;
}
"""
    }
}
