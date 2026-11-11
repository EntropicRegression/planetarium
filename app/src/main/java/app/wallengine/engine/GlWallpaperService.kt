package app.wallengine.engine

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import app.wallengine.pack.PackStore

class GlWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = GlEngine()

    inner class GlEngine : Engine() {
        private val renderer = Renderer(this@GlWallpaperService)
        private val store by lazy { PackStore(this@GlWallpaperService) }

        private val reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = loadActivePack()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            ContextCompat.registerReceiver(
                this@GlWallpaperService, reloadReceiver, IntentFilter(PackStore.ACTION_RELOAD),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            loadActivePack()
        }

        override fun onDestroy() {
            unregisterReceiver(reloadReceiver)
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
            loadActivePack()
        }

        private fun loadActivePack() {
            renderer.setPack(store.activePack(target()))
        }

        /** An engine showing only on the lock screen uses the lock selection; everything else uses home. */
        private fun target(): PackStore.Target {
            val lockOnly = Build.VERSION.SDK_INT >= 34 && !isPreview &&
                wallpaperFlags == WallpaperManager.FLAG_LOCK
            return if (lockOnly) PackStore.Target.LOCK else PackStore.Target.HOME
        }
    }
}
