package app.planetarium.engine

import android.content.Context
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import app.planetarium.engine.gl.EglCore
import app.planetarium.pack.WallpaperPack
import java.util.concurrent.CountDownLatch

/**
 * One render thread + EGL context per wallpaper engine. Public methods may be called from any
 * thread; everything else runs on the render thread.
 *
 * Rendering is on demand: a frame is drawn only when something changed, or continuously (capped
 * at [maxFps]) while the scene is animated and visible.
 *
 * An engine that serves both the home and the lock screen can hold two scenes, one per screen,
 * and flips between them with [setLocked]; both stay loaded so the flip is instant.
 */
class Renderer(context: Context) : Choreographer.FrameCallback {
    private val thread = HandlerThread("wallpaper-render").also { it.start() }
    private val handler = Handler(thread.looper)
    private val host = LayerHost(context.applicationContext, handler) { requestRender() }
    private val egl = EglCore()
    private var eglReady = false

    private var visible = false
    private var powerSave = false
    private var width = 0
    private var height = 0
    private var frameScheduled = false
    private var dirty = false
    private var lastDrawNanos = 0L
    /** Seconds of animation shown so far. Stands still while nothing is drawn. */
    private var sceneTime = 0.0

    private class Slot {
        var scene: Scene? = null
        var pending: WallpaperPack? = null
        var changed = false
    }

    private val homeSlot = Slot()
    private val lockSlot = Slot()
    private var hasLockScene = false
    private var locked = false
    private val current: Slot get() = if (locked && hasLockScene) lockSlot else homeSlot

    /** Whether the shown scene may run its players, sensors and animations right now. */
    private val playing: Boolean get() = visible && !powerSave

    val input = InputState()
    @Volatile var maxFps = 30

    private val tilt = TiltSensor(context.applicationContext, handler, input) { invalidate() }

    /**
     * Sets what to show. [lock] is the scene for the lock screen when this engine serves both
     * screens and they differ; pass null to show [home] everywhere.
     */
    fun setPacks(home: WallpaperPack?, lock: WallpaperPack?) {
        handler.post {
            homeSlot.pending = home
            homeSlot.changed = true
            lockSlot.pending = lock
            lockSlot.changed = true
            hasLockScene = lock != null
            invalidate()
        }
    }

    /** Device locked/unlocked: picks the lock or the home scene. */
    fun setLocked(value: Boolean) {
        handler.post {
            val before = current
            locked = value
            if (current !== before) {
                before.scene?.onVisibilityChanged(false)
                current.scene?.onVisibilityChanged(playing)
                updateTilt()
                invalidate()
            }
        }
    }

    /** Battery saver: animated scenes freeze on a still frame, videos pause, the tilt sensor stops. */
    fun setPowerSave(value: Boolean) {
        handler.post {
            if (powerSave == value) return@post
            powerSave = value
            current.scene?.onVisibilityChanged(playing)
            updateTilt()
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
            current.scene?.onVisibilityChanged(playing)
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
            for (slot in arrayOf(homeSlot, lockSlot)) {
                slot.scene?.release()
                slot.scene = null
            }
            egl.release()
        }
        thread.quitSafely()
    }

    /** The motion sensor runs only while a shown scene actually uses it. */
    private fun updateTilt() {
        if (playing && current.scene?.usesTilt == true) tilt.start() else tilt.stop()
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

    private fun animating(): Boolean = !powerSave && current.scene?.animated == true

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!visible || !egl.hasSurface || width == 0 || height == 0) return
        if (!dirty && !animating()) return
        // Animated scenes are throttled; an explicit invalidate always draws on the next vsync.
        if (!dirty && frameTimeNanos - lastDrawNanos < 1_000_000_000L / maxFps - VSYNC_SLACK_NANOS) {
            schedule()
            return
        }
        draw(frameTimeNanos)
        if (animating()) schedule()
    }

    private fun draw(frameTimeNanos: Long) {
        reload(homeSlot)
        reload(lockSlot)

        // Only time actually spent showing frames counts, so animations neither jump after the
        // wallpaper was hidden nor lose float precision after days of uptime.
        if (lastDrawNanos != 0L) {
            sceneTime += ((frameTimeNanos - lastDrawNanos) / 1e9).coerceIn(0.0, MAX_FRAME_STEP_SEC)
        }

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.04f, 0.04f, 0.06f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        current.scene?.draw(width, height, sceneTime.toFloat(), input)
        if (!egl.swap()) Log.w(TAG, "eglSwapBuffers failed")
        dirty = false
        lastDrawNanos = frameTimeNanos
    }

    /** (Re)loads a slot's scene if its pack changed. Needs the GL context, so it runs from draw. */
    private fun reload(slot: Slot) {
        if (!slot.changed) return
        slot.changed = false
        slot.scene?.release()
        slot.scene = try {
            slot.pending?.let { Scene.load(it, host, maxTextureSize()) }
        } catch (e: Exception) {
            Log.e(TAG, "pack load failed", e)
            null
        }
        slot.scene?.onVisibilityChanged(slot === current && playing)
        updateTilt()
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
        const val MAX_FRAME_STEP_SEC = 0.1
    }
}
