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
import java.io.File
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
    /** A frame was asked for that should not wait for the frame-rate cap. */
    private var urgent = false
    /** Longest side of the surface the loaded scenes sized their textures for. */
    private var loadedForSize = 0
    private var lastDrawNanos = 0L
    /** Seconds of animation shown so far. Stands still while nothing is drawn. */
    private var sceneTime = 0.0
    // What the frames of the current window of [STATS_WINDOW_NANOS] added up to, for [lastStats].
    private var statsStartNanos = 0L
    private var statsFrames = 0
    private var statsDrawNanos = 0L
    private var statsSavedNanos = 0L
    private val statsFile = statsFile(context)

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

    /**
     * Most frames per second drawn for animation, tilt and touch. A change of page (the launcher
     * scrolling) or of what is shown is still drawn on the next refresh of the screen.
     */
    @Volatile var maxFps = 30

    // The sensor reports far more often than is worth drawing: its frames keep to the cap.
    private val tilt = TiltSensor(context.applicationContext, handler, input) { invalidate(urgent = false) }

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
            // Textures were sized for the screen they were loaded on; a clearly bigger one needs them again.
            if (loadedForSize > 0 && maxOf(w, h) > loadedForSize * 1.1f) {
                homeSlot.changed = homeSlot.pending != null
                lockSlot.changed = lockSlot.pending != null
            }
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
        // A finger reports far more often than is worth drawing: its frames keep to the cap.
        handler.post { invalidate(urgent = false) }
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

    private fun invalidate(urgent: Boolean = true) {
        dirty = true
        if (urgent) this.urgent = true
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
        // Animation and tilt are throttled; anything else asked for draws on the next vsync.
        if (!urgent && frameTimeNanos - lastDrawNanos < 1_000_000_000L / maxFps.coerceAtLeast(1) - VSYNC_SLACK_NANOS) {
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

        val started = System.nanoTime()
        val scene = current.scene
        if (scene != null) {
            // Clears the screen itself, after its layers' offscreen passes.
            scene.draw(width, height, sceneTime.toFloat(), input, maxFps)
        } else {
            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0.04f, 0.04f, 0.06f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        if (!egl.swap()) Log.w(TAG, "eglSwapBuffers failed")
        dirty = false
        urgent = false
        countFrame(frameTimeNanos, System.nanoTime() - started)
        lastDrawNanos = frameTimeNanos
    }

    /** Keeps [lastStats] up to date: how often frames are really drawn, and how long one takes. */
    private fun countFrame(frameTimeNanos: Long, drawNanos: Long) {
        // A pause (hidden, or a still scene waiting for input) is not part of a frame rate.
        if (statsFrames == 0 || frameTimeNanos - lastDrawNanos > STATS_PAUSE_NANOS) {
            statsStartNanos = frameTimeNanos
            statsFrames = 0
            statsDrawNanos = 0L
        }
        statsFrames++
        statsDrawNanos += drawNanos
        val elapsed = frameTimeNanos - statsStartNanos
        if (elapsed < STATS_WINDOW_NANOS) return
        // The first frame only opens the window: N frames span N - 1 intervals.
        val fps = (statsFrames - 1) * 1e9f / elapsed
        val drawMillis = statsDrawNanos / 1e6f / statsFrames
        statsFrames = 0
        // Often enough to be current, rarely enough not to matter.
        if (statsSavedNanos != 0L && frameTimeNanos - statsSavedNanos < STATS_SAVE_NANOS) return
        statsSavedNanos = frameTimeNanos
        try {
            statsFile.writeText("${current.pending?.id.orEmpty()}\n$fps\n$drawMillis\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not save the frame statistics", e)
        }
    }

    /** (Re)loads a slot's scene if its pack changed. Needs the GL context, so it runs from draw. */
    private fun reload(slot: Slot) {
        if (!slot.changed) return
        slot.changed = false
        slot.scene?.release()
        slot.scene = try {
            slot.pending?.let { Scene.load(it, host, maxTextureSize(), width, height) }
        } catch (e: Exception) {
            Log.e(TAG, "pack load failed", e)
            null
        }
        loadedForSize = maxOf(width, height)
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

    /**
     * How a wallpaper was really being drawn: frames per second, and the time the render thread
     * spent on one (drawing plus handing it over, which waits when the GPU is behind).
     */
    class Stats(val packId: String, val fps: Float, val drawMillis: Float)

    companion object {
        private const val TAG = "Renderer"
        private const val VSYNC_SLACK_NANOS = 2_000_000L
        private const val MAX_FRAME_STEP_SEC = 0.1
        private const val STATS_WINDOW_NANOS = 2_000_000_000L
        private const val STATS_PAUSE_NANOS = 500_000_000L
        private const val STATS_SAVE_NANOS = 10_000_000_000L

        // A file, because the wallpaper runs in a process of its own and the app reads this.
        private fun statsFile(context: Context) = File(context.applicationContext.cacheDir, "render-stats")

        /** The latest two seconds a wallpaper engine spent drawing steadily, or null if none was saved. */
        fun lastStats(context: Context): Stats? = try {
            val lines = statsFile(context).readLines()
            Stats(lines[0], lines[1].toFloat(), lines[2].toFloat())
        } catch (e: Exception) {
            null
        }
    }
}
