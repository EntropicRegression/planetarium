package app.planetarium.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.planetarium.engine.gl.FullscreenQuad
import app.planetarium.pack.WallpaperPack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compiles a pack's shader layers outside the wallpaper, in an offscreen GL context, by building
 * the very same [ShaderLayer] objects the wallpaper would. On success it also renders a preview
 * into the pack's thumb.jpg (unless the pack ships one).
 */
object ShaderValidator {
    sealed interface Result {
        data object Ok : Result
        /** [log] holds, per failing layer, its file name and the compiler output. */
        class Failed(val log: String) : Result
    }

    private const val TAG = "ShaderValidator"
    private const val WIDTH = 360
    private const val HEIGHT = 640
    private const val PREVIEW_TIME = 1.0f
    /** A few frames, so that feedback shaders have something to show. */
    private const val PREVIEW_FRAMES = 4

    /** Blocking; call from a background thread. Everything GL happens on the calling thread. */
    @Synchronized
    fun validate(context: Context, pack: WallpaperPack): Result {
        val egl = try {
            PbufferContext(WIDTH, HEIGHT)
        } catch (e: Exception) {
            // Cannot check here; the wallpaper itself still skips a layer that fails to compile.
            Log.w(TAG, "no offscreen GL context, skipping validation", e)
            return Result.Ok
        }
        val layers = ArrayList<Layer>()
        try {
            val host = LayerHost(context.applicationContext, Handler(Looper.getMainLooper())) {}
            val limit = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
            val maxTextureSize = minOf(limit[0], 2048).coerceAtLeast(1024)

            val errors = StringBuilder()
            // Same order as Scene: far layers first. Video layers are left out of the preview.
            pack.layers.sortedByDescending { it.depth }.forEach { spec ->
                when (spec.type) {
                    WallpaperPack.TYPE_SHADER -> try {
                        layers += ShaderLayer(pack, spec, host, maxTextureSize)
                    } catch (e: Exception) {
                        errors.append(spec.src).append(":\n").append(e.message ?: e.toString()).append("\n\n")
                    }
                    WallpaperPack.TYPE_IMAGE -> try {
                        layers += ImageLayer(pack, spec, maxTextureSize)
                    } catch (e: Exception) {
                        Log.w(TAG, "image layer ${spec.src} skipped in preview", e)
                    }
                }
            }
            if (errors.isNotEmpty()) return Result.Failed(errors.toString().trim())

            val thumb = File(pack.dir, WallpaperPack.THUMBNAIL)
            if (!thumb.exists()) {
                try {
                    renderThumbnail(layers, thumb)
                } catch (e: Exception) {
                    Log.w(TAG, "thumbnail of pack ${pack.id} failed", e)
                }
            }
            return Result.Ok
        } finally {
            layers.forEach {
                try {
                    it.release()
                } catch (e: Exception) {
                    Log.w(TAG, "layer release failed", e)
                }
            }
            egl.release()
        }
    }

    private fun renderThumbnail(layers: List<Layer>, dest: File) {
        val quad = FullscreenQuad()
        val input = InputState()
        try {
            for (i in 0 until PREVIEW_FRAMES) {
                val time = PREVIEW_TIME - (PREVIEW_FRAMES - 1 - i) / 30f
                val frame = FrameContext(WIDTH, HEIGHT, time, input, quad)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, WIDTH, HEIGHT)
                GLES30.glClearColor(0.04f, 0.04f, 0.06f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                layers.forEach {
                    // What Scene.draw guarantees to each layer.
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    GLES30.glViewport(0, 0, WIDTH, HEIGHT)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                    it.draw(frame)
                }
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            val pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
            GLES30.glReadPixels(0, 0, WIDTH, HEIGHT, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GL error while rendering the preview" }
            pixels.rewind()

            val raw = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(pixels)
            // GL rows run bottom to top.
            val upright = Bitmap.createBitmap(raw, 0, 0, WIDTH, HEIGHT, Matrix().apply { preScale(1f, -1f) }, false)
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (!tmp.renameTo(dest)) tmp.delete()
            if (upright !== raw) upright.recycle()
            raw.recycle()
        } finally {
            quad.release()
        }
    }

    /**
     * A GLES 3 context current on a small pbuffer. [EglCore][app.planetarium.engine.gl.EglCore]
     * only does window surfaces. The display is deliberately not terminated: in this process it
     * is shared with the UI toolkit's renderer.
     */
    private class PbufferContext(width: Int, height: Int) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        init {
            check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "no GLES3 pbuffer config"
            }
            try {
                context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
                )
                check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
                surface = EGL14.eglCreatePbufferSurface(
                    display, configs[0],
                    intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
                )
                check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
            } catch (e: Exception) {
                release()
                throw e
            }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            surface = EGL14.EGL_NO_SURFACE
            context = EGL14.EGL_NO_CONTEXT
            EGL14.eglReleaseThread()
        }
    }
}
