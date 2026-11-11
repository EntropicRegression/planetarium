package app.wallengine.pack

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import app.wallengine.engine.gl.BitmapLoader
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Pack library on disk. Lives in device-protected storage so the wallpaper process can read it
 * on the lock screen before the first unlock after boot.
 */
class PackStore(context: Context) {
    private val appContext = context.applicationContext
    private val base: File = appContext.createDeviceProtectedStorageContext().filesDir
    private val packsDir = File(base, "packs").apply { mkdirs() }
    private val activeFile = File(base, "active.json")

    enum class Target(val key: String) { HOME("home"), LOCK("lock") }

    fun list(): List<WallpaperPack> =
        (packsDir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { load(it.name) }

    fun load(id: String): WallpaperPack? {
        val dir = File(packsDir, id)
        if (!File(dir, WallpaperPack.MANIFEST).isFile) return null
        return try {
            WallpaperPack.read(dir)
        } catch (e: Exception) {
            Log.w(TAG, "unreadable pack $id", e)
            null
        }
    }

    /** Copies a picked image into a new single-layer pack. */
    fun importImage(uri: Uri, name: String): WallpaperPack {
        val id = UUID.randomUUID().toString()
        val dir = File(packsDir, id).apply { mkdirs() }
        try {
            val src = "layer0"
            appContext.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot open $uri" }
                File(dir, src).outputStream().use { input.copyTo(it) }
            }
            WallpaperPack.writeManifest(dir, name, listOf(LayerSpec(WallpaperPack.TYPE_IMAGE, src, 1f)))
            return WallpaperPack.read(dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    fun delete(id: String) {
        File(packsDir, id).deleteRecursively()
        val home = activeId(Target.HOME).takeIf { it != id }
        val lock = activeId(Target.LOCK).takeIf { it != id }
        writeActive(home, lock)
    }

    fun activeId(target: Target): String? = try {
        JSONObject(activeFile.readText()).optString(target.key).ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    fun activePack(target: Target): WallpaperPack? = activeId(target)?.let(::load)

    fun setActive(id: String?, vararg targets: Target) {
        val home = if (Target.HOME in targets) id else activeId(Target.HOME)
        val lock = if (Target.LOCK in targets) id else activeId(Target.LOCK)
        writeActive(home, lock)
    }

    private fun writeActive(home: String?, lock: String?) {
        val json = JSONObject()
        home?.let { json.put(Target.HOME.key, it) }
        lock?.let { json.put(Target.LOCK.key, it) }
        val tmp = File(base, "active.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(activeFile)
        // The wallpaper service runs in another process; tell it to re-read the selection.
        appContext.sendBroadcast(Intent(ACTION_RELOAD).setPackage(appContext.packageName))
    }

    /** Small preview of the first image layer, for the library grid. */
    fun thumbnail(pack: WallpaperPack, maxSize: Int = 512): Bitmap? {
        val layer = pack.layers.firstOrNull { it.type == WallpaperPack.TYPE_IMAGE } ?: return null
        return try {
            BitmapLoader.load(pack.asset(layer.src), maxSize)
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed for ${pack.id}", e)
            null
        }
    }

    companion object {
        private const val TAG = "PackStore"
        const val ACTION_RELOAD = "app.wallengine.action.RELOAD"
    }
}
