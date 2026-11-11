package app.wallengine.engine

import android.app.KeyguardManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack

class GlWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GlEngine()

    inner class GlEngine : Engine() {
        private val renderer = Renderer(this@GlWallpaperService)
        private val store by lazy { PackStore(this@GlWallpaperService) }
        private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
        private val power by lazy { getSystemService(PowerManager::class.java) }

        /** The pack whose colours the system theme should follow. */
        private var colorPack: WallpaperPack? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    PackStore.ACTION_RELOAD -> loadActivePacks()
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> renderer.setPowerSave(power.isPowerSaveMode)
                    // Unlocked for sure; isKeyguardLocked can still say true while the keyguard animates away.
                    Intent.ACTION_USER_PRESENT -> setLocked(false)
                    else -> setLocked(keyguard.isKeyguardLocked)
                }
            }
        }

        private val mainHandler = Handler(Looper.getMainLooper())
        private var engineVisible = false
        private var engineLocked = false

        /**
         * USER_PRESENT is not sent for every kind of unlock (a swipe-only lock screen, some vendor
         * skins), so while the lock scene is on screen the keyguard is also asked directly.
         */
        private val unlockWatch = object : Runnable {
            override fun run() {
                if (!engineVisible || !engineLocked) return
                setLocked(keyguard.isKeyguardLocked)
                mainHandler.postDelayed(this, UNLOCK_POLL_MS)
            }
        }

        private fun setLocked(locked: Boolean) {
            engineLocked = locked
            renderer.setLocked(locked)
            mainHandler.removeCallbacks(unlockWatch)
            if (locked && engineVisible) mainHandler.postDelayed(unlockWatch, UNLOCK_POLL_MS)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            val filter = IntentFilter().apply {
                addAction(PackStore.ACTION_RELOAD)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this@GlWallpaperService, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            renderer.setPowerSave(power.isPowerSaveMode)
            setLocked(keyguard.isKeyguardLocked)
            loadActivePacks()
        }

        override fun onDestroy() {
            mainHandler.removeCallbacks(unlockWatch)
            unregisterReceiver(receiver)
            renderer.release()
            super.onDestroy()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderer.onSurfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.onSurfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderer.onSurfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            engineVisible = visible
            if (visible) setLocked(keyguard.isKeyguardLocked) else mainHandler.removeCallbacks(unlockWatch)
            renderer.setVisible(visible)
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            renderer.setOffset(xOffset)
        }

        // The wallpaper only gets a copy of touches; it cannot consume them.
        override fun onTouchEvent(event: MotionEvent) {
            val down = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
            renderer.setTouch(event.x, event.y, down)
        }

        // API 34+: this engine was reassigned between home screen and lock screen.
        override fun onWallpaperFlagsChanged(which: Int) {
            loadActivePacks()
        }

        /** Lets the system theme (Material You) follow the wallpaper, using the pack's preview image. */
        override fun onComputeColors(): WallpaperColors? {
            val pack = colorPack ?: return null
            val preview = store.thumbnail(pack, 128) ?: return null
            return try {
                WallpaperColors.fromBitmap(preview)
            } finally {
                preview.recycle()
            }
        }

        private fun loadActivePacks() {
            val home = store.activePack(PackStore.Target.HOME)
            val lock = store.activePack(PackStore.Target.LOCK) ?: home
            when (screens()) {
                Screens.HOME -> show(home, null)
                Screens.LOCK -> show(lock, null)
                // One engine for both screens: flip between two scenes as the device locks and unlocks.
                Screens.BOTH -> show(home, lock.takeIf { it?.id != home?.id })
            }        }

        private fun show(main: WallpaperPack?, lock: WallpaperPack?) {
            renderer.setPacks(main, lock)
            colorPack = main
            notifyColorsChanged()
        }

        /**
         * Which screens this engine draws. Only Android 14+ says; before that the system may or
         * may not be showing this wallpaper on the lock screen, so assume both.
         */
        private fun screens(): Screens = when {
            isPreview -> Screens.HOME
            Build.VERSION.SDK_INT < 34 -> Screens.BOTH
            wallpaperFlags == WallpaperManager.FLAG_LOCK -> Screens.LOCK
            wallpaperFlags == WallpaperManager.FLAG_SYSTEM -> Screens.HOME
            else -> Screens.BOTH
        }
    }

    private enum class Screens { HOME, LOCK, BOTH }

    private companion object {
        const val UNLOCK_POLL_MS = 250L
    }
}
