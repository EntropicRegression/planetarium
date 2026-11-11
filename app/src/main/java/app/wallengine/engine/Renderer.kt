package app.wallengine.engine

import android.content.Context
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import app.wallengine.engine.gl.EglCore
import app.wallengine.pack.WallpaperPack
import java.util.concurrent.CountDownLatch

/**
 * One render thread + EGL context per wallpaper engine. Public methods may be called from any
 * thread; everything else runs on the render thread.
 *
 * Rendering is on demand: a frame is drawn only when something changed, or continuously (capped
 * at [maxFps]) while the scene is animated and visible.
 */
class Renderer(context: Context) : Choreographer.FrameCallback {
    private val thread = HandlerThread("wallpaper-render").also { it.start() }
    private val handler = Handler(thread.looper)
    private val host = LayerHost(context.applicationContext, handler) { requestRender() }
    private val egl = EglCore()
    private var eglReady = false

    private var visible = false
    private var width = 0
    private var height = 0
    private var frameScheduled = false
    private var dirty = false
    private var lastDrawNanos = 0L
    private val startNanos = System.nanoTime()

    private var scene: Scene? = null
    private var pendingPack: WallpaperPack? = null
    private var packChanged = false

    val input = InputState()
    @Volatile var maxFps = 30

    private val tilt = TiltSensor(context.applicationContext, handler, input) { invalidate() }

    /** The motion sensor runs only while a visible scene actually uses it. Render thread. */
    private fun updateTilt() {
        if (visible && scene?.usesTilt == true) tilt.start() else tilt.stop()
    }

    fun setPack(pack: WallpaperPack?) {
        handler.post {
            pendingPack = pack
            packChanged = true
            invalidate()
        }
    }

    fun onSurfaceCreated(surface: Surface) {
        handler.post {
            try {
                if (!eglReady) {
                    egl.init()
                    eglReady = true
                }
                egl.createSurface(surface)
                invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "surface setup failed", e)
            }
        }
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        handler.post {
            width = w
            height = h
            invalidate()
        }
    }

    /** Blocks until the render thread has let go of the surface; the system frees it right after. */
    fun onSurfaceDestroyed() = runBlocking { egl.destroySurface() }

    fun setVisible(v: Boolean) {
        handler.post {
            visible = v
            scene?.onVisibilityChanged(v)
            updateTilt()
            if (v) invalidate()
        }
    }

    fun setOffset(x: Float) {
        input.offsetX = x
        requestRender()
    }

    fun setTouch(x: Float, y: Float, down: Boolean) {
        input.touchX = x
        input.touchY = y
        input.touchDown = down
        requestRender()
    }

    /** Asks for a frame; safe from any thread. */
    fun requestRender() {
        handler.post { invalidate() }
    }

    fun release() {
        runBlocking {
            Choreographer.getInstance().removeFrameCallback(this)
            tilt.stop()
            // GL objects die with the context, but layers may hold players and other non-GL resources.
            scene?.release()
            scene = null
            egl.release()
        }
        thread.quitSafely()
    }

    private fun invalidate() {
        dirty = true
        schedule()
    }

    private fun schedule() {
        if (frameScheduled || !visible || !egl.hasSurface) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!visible || !egl.hasSurface || width == 0 || height == 0) return
        val animated = scene?.animated == true
        if (!dirty && !animated) return
        // Animated scenes are throttled; an explicit invalidate always draws on the next vsync.
        if (!dirty && frameTimeNanos - lastDrawNanos < 1_000_000_000L / maxFps - VSYNC_SLACK_NANOS) {
            schedule()
            return
        }
        draw(frameTimeNanos)
        if (scene?.animated == true) schedule()
    }

    private fun draw(frameTimeNanos: Long) {
        if (packChanged) {
            packChanged = false
            scene?.release()
            scene = try {
                pendingPack?.let { Scene.load(it, host, maxTextureSize()) }
            } catch (e: Exception) {
                Log.e(TAG, "pack load failed", e)
                null
            }
            scene?.onVisibilityChanged(visible)
            updateTilt()
        }
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.04f, 0.04f, 0.06f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        scene?.draw(width, height, (System.nanoTime() - startNanos) / 1e9f, input)
        if (!egl.swap()) Log.w(TAG, "eglSwapBuffers failed")
        dirty = false
        lastDrawNanos = frameTimeNanos
    }

    /** Big enough to stay sharp while panning, small enough to keep memory in check. */
    private fun maxTextureSize(): Int {
        val limit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
        return minOf(limit[0], maxOf(width, height) * 2, 4096).coerceAtLeast(1024)
    }

    private fun runBlocking(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "render thread task failed", e)
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await()
    }

    private companion object {
        const val TAG = "Renderer"
        const val VSYNC_SLACK_NANOS = 2_000_000L
    }
}
