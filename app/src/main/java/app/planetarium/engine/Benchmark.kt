package app.planetarium.engine

import android.content.Context
import android.opengl.GLES30
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.planetarium.engine.gl.FullscreenQuad
import app.planetarium.pack.LayerSpec
import app.planetarium.pack.WallpaperPack

/**
 * Measures what a pack costs the GPU of this device: the time to draw one frame, and each
 * layer's share of it. Runs in an offscreen GL context at the size of the screen, with the very
 * layer objects the wallpaper uses, so the numbers are the wallpaper's own.
 *
 * Video layers are left out: they need a player and a surface of their own.
 */
object Benchmark {
    /** [millis] is null for a layer that could not be measured; [note] then says why. */
    class LayerCost(val src: String, val type: String, val millis: Float?, val note: String = "")

    class Report(
        val width: Int,
        val height: Int,
        val gpu: String,
        /** One whole frame, all layers, finished once at the end: the cost that matters. */
        val frameMillis: Float,
        /**
         * Far to near. Each layer is finished on its own, which costs extra on a phone's GPU, so
         * these add up to more than [frameMillis]; they are for comparing layers with each other.
         */
        val layers: List<LayerCost>,
    )

    private const val TAG = "Benchmark"
    private const val WARMUP_FRAMES = 10
    private const val FRAMES = 40
    private const val START_TIME = 3f

    /** Blocking, takes a few seconds; call from a background thread. Throws if there is no GL context. */
    @Synchronized
    fun run(context: Context, pack: WallpaperPack, width: Int, height: Int): Report {
        val egl = ShaderValidator.PbufferContext(width, height)
        val layers = ArrayList<Pair<LayerSpec, Layer>>()
        val skipped = HashMap<LayerSpec, String>()
        val quad = FullscreenQuad()
        try {
            val host = LayerHost(context.applicationContext, Handler(Looper.getMainLooper())) {}
            val limit = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
            // The renderer's own rule, so pictures are loaded at the size the wallpaper uses.
            val maxTextureSize = minOf(limit[0], maxOf(width, height) * 2, 4096).coerceAtLeast(1024)
            val specs = pack.layers.sortedByDescending { it.depth }
            for (spec in specs) {
                try {
                    when (spec.type) {
                        WallpaperPack.TYPE_IMAGE -> layers += spec to ImageLayer(pack, spec, maxTextureSize, width, height)
                        WallpaperPack.TYPE_SHADER -> layers += spec to ShaderLayer(pack, spec, host, maxTextureSize)
                        else -> skipped[spec] = "影片圖層不在測量範圍"
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "layer ${spec.src} failed to load", e)
                    skipped[spec] = "載入失敗"
                }
            }

            val input = InputState().apply {
                tiltX = 0.2f
                tiltY = -0.1f
            }
            val perLayer = LongArray(layers.size)
            var frameNanos = 0L
            var clock = START_TIME

            fun frame(each: Boolean): Long {
                clock += 1f / 30f
                val frameContext = FrameContext(width, height, clock, input, quad)
                GLES30.glFinish()
                val start = System.nanoTime()
                // The order Scene.draw keeps: every layer's offscreen pass, then the screen.
                layers.forEachIndexed { i, (_, layer) ->
                    if (each) {
                        val before = System.nanoTime()
                        layer.prepare(frameContext)
                        GLES30.glFinish()
                        perLayer[i] += System.nanoTime() - before
                    } else {
                        layer.prepare(frameContext)
                    }
                }
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, width, height)
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
                GLES30.glClearColor(0.04f, 0.04f, 0.06f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                layers.forEachIndexed { i, (_, layer) ->
                    // What Scene.draw guarantees to each layer.
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    GLES30.glViewport(0, 0, width, height)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    if (each) {
                        GLES30.glFinish()
                        val before = System.nanoTime()
                        layer.draw(frameContext)
                        GLES30.glFinish()
                        perLayer[i] += System.nanoTime() - before
                    } else {
                        layer.draw(frameContext)
                    }
                }
                GLES30.glFinish()
                return System.nanoTime() - start
            }

            repeat(WARMUP_FRAMES) { frame(each = false) }
            repeat(FRAMES) { frameNanos += frame(each = false) }
            perLayer.fill(0L)
            repeat(FRAMES) { frame(each = true) }

            val costs = specs.map { spec ->
                val index = layers.indexOfFirst { it.first === spec }
                if (index < 0) LayerCost(spec.src, spec.type, null, skipped[spec] ?: "")
                else LayerCost(spec.src, spec.type, perLayer[index] / 1e6f / FRAMES)
            }
            return Report(
                width, height,
                gpu = GLES30.glGetString(GLES30.GL_RENDERER) ?: "?",
                frameMillis = frameNanos / 1e6f / FRAMES,
                layers = costs,
            )
        } finally {
            layers.forEach {
                try {
                    it.second.release()
                } catch (e: Exception) {
                    Log.w(TAG, "layer release failed", e)
                }
            }
            quad.release()
            egl.release()
        }
    }

    /** The report as plain text, to show and to copy. */
    fun describe(pack: WallpaperPack, report: Report, live: Renderer.Stats?): String = buildString {
        append(pack.name).append('\n')
        append("${Build.MODEL} · ${report.gpu} · ${report.width}×${report.height}\n\n")
        val share = report.frameMillis / (1000f / 30f) * 100f
        append("整格:%.1f ms(30 fps 時約佔 GPU %.0f%%)\n".format(report.frameMillis, share))
        if (live != null) {
            append("桌布上次顯示時:每秒 %.0f 格,每格繪製 %.1f ms\n".format(live.fps, live.drawMillis))
        } else {
            append("桌布上次顯示時:沒有紀錄(這張桌布還沒套用,或還沒顯示滿兩秒)\n")
        }
        append("\n逐層(由遠到近):\n")
        for (layer in report.layers) {
            val kind = when (layer.type) {
                WallpaperPack.TYPE_IMAGE -> "圖片"
                WallpaperPack.TYPE_SHADER -> "動態"
                WallpaperPack.TYPE_VIDEO -> "影片"
                else -> layer.type
            }
            if (layer.millis != null) append("%6.2f ms  %s  %s\n".format(layer.millis, kind, layer.src))
            else append("     —     %s  %s(%s)\n".format(kind, layer.src, layer.note))
        }
        append("\n逐層的數字是每層單獨結算,加總會比整格高,用來比較哪一層最重。")
    }
}
