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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    /**
     * Creates a new pack. [build] puts the asset files into the fresh pack directory and returns
     * the layers; the manifest is written for it. The directory is removed if anything throws.
     */
    fun createPack(name: String, extras: JSONObject? = null, build: (dir: File) -> List<LayerSpec>): WallpaperPack =
        createPackRaw { dir -> WallpaperPack.writeManifest(dir, name, build(dir), extras) }

    /** Like [createPack], but [populate] must itself leave a valid manifest.json in the directory. */
    fun createPackRaw(populate: (dir: File) -> Unit): WallpaperPack {
        val dir = File(packsDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            populate(dir)
            return WallpaperPack.read(dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Copies a content Uri into [dest]. */
    fun copyUri(uri: Uri, dest: File) {
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open $uri" }
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    /** Copies a picked image into a new single-layer pack. */
    fun importImage(uri: Uri, name: String): WallpaperPack = createPack(name) { dir ->
        copyUri(uri, File(dir, "layer0"))
        listOf(LayerSpec(WallpaperPack.TYPE_IMAGE, "layer0"))
    }

    /** Tells the wallpaper process to re-read the selection and reload its packs from disk. */
    fun notifyChanged() {
        appContext.sendBroadcast(Intent(ACTION_RELOAD).setPackage(appContext.packageName))
    }

    fun rename(pack: WallpaperPack, name: String) {
        val json = JSONObject(pack.manifest.toString()).put("name", name)
        replaceFile(File(pack.dir, WallpaperPack.MANIFEST), json.toString(2))
    }

    /** Saves the user's values for the pack's shader parameters (see "params" in the manifest). */
    fun writeParams(pack: WallpaperPack, values: Map<String, Float>) {
        val json = JSONObject()
        values.forEach { (id, value) -> json.put(id, value.toDouble()) }
        replaceFile(File(pack.dir, PARAMS_FILE), json.toString())
    }

    fun readParams(pack: WallpaperPack): Map<String, Float> = try {
        val json = JSONObject(File(pack.dir, PARAMS_FILE).readText())
        json.keys().asSequence().associateWith { json.optDouble(it, 0.0).toFloat() }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Writes the whole pack directory as a zip that the zip import reads back. */
    fun exportZip(pack: WallpaperPack, dest: Uri) {
        val root = pack.dir
        val output = requireNotNull(appContext.contentResolver.openOutputStream(dest)) { "cannot write $dest" }
        ZipOutputStream(output.buffered()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Replaces a file in one step, so the wallpaper process never reads a half-written one. */
    private fun replaceFile(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        check(tmp.renameTo(file)) { "could not write ${file.name}" }
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

    /** Small preview for the library grid: the pack's thumb.jpg if present, else its first image layer. */
    fun thumbnail(pack: WallpaperPack, maxSize: Int = 512): Bitmap? {
        val thumb = File(pack.dir, WallpaperPack.THUMBNAIL)
        val file = if (thumb.isFile) thumb else {
            val layer = pack.layers.firstOrNull { it.type == WallpaperPack.TYPE_IMAGE } ?: return null
            pack.asset(layer.src)
        }
        return try {
            BitmapLoader.load(file, maxSize)
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed for ${pack.id}", e)
            null
        }
    }

    companion object {
        private const val TAG = "PackStore"
        const val ACTION_RELOAD = "app.wallengine.action.RELOAD"
        /** Same file the shader layer reads its parameter values from. */
        const val PARAMS_FILE = "params.json"
    }
}
