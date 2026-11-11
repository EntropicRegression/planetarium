package app.wallengine.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.wallengine.engine.ShaderValidator
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** The blocking work behind the shader / plugin-pack import flow. Call off the main thread. */
internal object ShaderPackImporter {
    sealed interface Outcome {
        class Imported(val pack: WallpaperPack) : Outcome
        class Rejected(val title: String, val detail: String) : Outcome
    }

    /** A built-in example pack: a directory under assets/samples holding a complete pack. */
    class Sample(val assetDir: String, val name: String, val layered: Boolean = false)

    private const val TAG = "ShaderPackImporter"
    private const val SAMPLES_DIR = "samples"
    private const val SEEDED_FILE = "samples-installed.txt"
    private const val SHADER_FILE = "shader.frag"
    private const val MAX_SHADER_BYTES = 1 shl 20
    private const val MAX_ZIP_BYTES = 300L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 2000

    private val SHADER_EXTENSIONS = setOf("frag", "glsl", "fs", "txt")

    /**
     * What a plugin pack may contain: text, images and video. Files without an extension are
     * accepted too (packs made by this app name assets "layer0"), subject to the content check.
     */
    private val DATA_EXTENSIONS = SHADER_EXTENSIONS + setOf(
        "json", "md", "vert",
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "avif",
        "mp4", "m4v", "webm", "mkv", "mov", "3gp",
    )

    private val KNOWN_TYPES = setOf(WallpaperPack.TYPE_IMAGE, WallpaperPack.TYPE_VIDEO, WallpaperPack.TYPE_SHADER)

    fun samples(context: Context): List<Sample> =
        (context.assets.list(SAMPLES_DIR) ?: emptyArray()).sorted().mapNotNull { dir ->
            try {
                val manifest = context.assets.open("$SAMPLES_DIR/$dir/${WallpaperPack.MANIFEST}").use {
                    JSONObject(it.readBytes().decodeToString())
                }
                val layers = manifest.optJSONArray("layers")
                val layered = layers != null && (0 until layers.length()).any {
                    layers.optJSONObject(it)?.optString("type") != WallpaperPack.TYPE_SHADER
                }
                Sample("$SAMPLES_DIR/$dir", manifest.optString("name", dir), layered)
            } catch (e: Exception) {
                Log.w(TAG, "bad sample $dir", e)
                null
            }
        }

    /**
     * Puts the built-in samples into the library without being asked: on first launch all of
     * them, after an update only the ones that are new. Each sample is offered once, so deleting
     * one does not bring it back. On a library with no wallpaper chosen yet, the last one added
     * becomes the wallpaper. Returns how many packs were added.
     */
    @Synchronized
    fun installNewSamples(context: Context, store: PackStore): Int {
        val record = File(context.createDeviceProtectedStorageContext().filesDir, SEEDED_FILE)
        val seeded = try {
            record.readLines().filter { it.isNotBlank() }.toMutableSet()
        } catch (e: IOException) {
            mutableSetOf()
        }
        // Layered scenes last: the library lists the newest pack first.
        val fresh = samples(context).filter { it.assetDir !in seeded }.sortedBy { it.layered }
        if (fresh.isEmpty()) return 0
        var last: WallpaperPack? = null
        var added = 0
        fresh.forEach { sample ->
            when (val outcome = importSample(context, store, sample)) {
                is Outcome.Imported -> {
                    last = outcome.pack
                    added++
                }
                is Outcome.Rejected -> Log.w(TAG, "sample ${sample.assetDir} not installed: ${outcome.detail}")
            }
            // Recorded even when rejected: a sample this device cannot run is not retried every launch.
            seeded += sample.assetDir
        }
        record.writeText(seeded.sorted().joinToString(System.lineSeparator()))
        val chosen = last
        if (chosen != null && store.activeId(PackStore.Target.HOME) == null) {
            store.setActive(chosen.id, PackStore.Target.HOME, PackStore.Target.LOCK)
        }
        return added
    }

    fun importShaderFile(context: Context, store: PackStore, uri: Uri): Outcome = finish(context, store) {
        val fileName = displayName(context, uri) ?: "shader"
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in SHADER_EXTENSIONS) {
            throw IOException("不支援的檔案類型:$fileName(可用 .frag、.glsl、.fs、.txt)")
        }
        val source = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "無法開啟檔案" }
            readCapped(input, MAX_SHADER_BYTES)
        }
        store.createPack(fileName.substringBeforeLast('.').ifBlank { "Shader" }) { dir ->
            File(dir, SHADER_FILE).writeBytes(source)
            listOf(LayerSpec(WallpaperPack.TYPE_SHADER, SHADER_FILE))
        }
    }

    fun importZip(context: Context, store: PackStore, uri: Uri): Outcome = finish(context, store) {
        store.createPackRaw { dir ->
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "無法開啟檔案" }
                extractZip(input, dir)
            }
            hoistSingleFolder(dir)
            if (!File(dir, WallpaperPack.MANIFEST).isFile) {
                throw IOException("壓縮檔內找不到 manifest.json(應位於根目錄,或唯一的最上層資料夾內)")
            }
        }
    }

    fun importSample(context: Context, store: PackStore, sample: Sample): Outcome = finish(context, store) {
        store.createPackRaw { dir -> copyAssetDir(context, sample.assetDir, dir) }
    }

    /** Creates the pack, checks it, and for packs with shader layers compiles them and makes a thumbnail. */
    private fun finish(context: Context, store: PackStore, create: () -> WallpaperPack): Outcome {
        val pack = try {
            create()
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            return Outcome.Rejected("匯入失敗", e.message ?: e.toString())
        }
        try {
            checkLayers(pack)
        } catch (e: Exception) {
            store.delete(pack.id)
            return Outcome.Rejected("匯入失敗", e.message ?: e.toString())
        }
        if (pack.layers.none { it.type == WallpaperPack.TYPE_SHADER }) return Outcome.Imported(pack)
        return when (val result = ShaderValidator.validate(context, pack)) {
            is ShaderValidator.Result.Ok -> Outcome.Imported(pack)
            is ShaderValidator.Result.Failed -> {
                store.delete(pack.id)
                Outcome.Rejected("Shader 編譯失敗", result.log)
            }
        }
    }

    private fun checkLayers(pack: WallpaperPack) {
        if (pack.layers.isEmpty()) throw IOException("這個插件包沒有任何圖層")
        pack.layers.forEach { layer ->
            if (layer.type !in KNOWN_TYPES) throw IOException("不支援的圖層類型:${layer.type}")
            val file = try {
                pack.asset(layer.src)
            } catch (e: Exception) {
                throw IOException("圖層路徑超出插件包範圍:${layer.src}")
            }
            if (!file.isFile) throw IOException("找不到圖層檔案:${layer.src}")
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val bytes = input.readNBytesCompat(max + 1)
        if (bytes.size > max) throw IOException("檔案過大")
        return bytes
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Unpacks untrusted zip data into [dir]. Entries that would land outside [dir] abort the
     * import; so does exceeding the size or entry limits (measured on the bytes actually
     * inflated, not on the sizes the archive claims). Anything that is not a plain data file is
     * left out: nothing from a pack is ever loaded as code, and nothing executable is kept.
     */
    private fun extractZip(input: InputStream, dir: File) {
        val root = dir.canonicalFile
        val buffer = ByteArray(64 * 1024)
        var entries = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw IOException("壓縮檔內的項目太多(上限 $MAX_ZIP_ENTRIES)")
                val name = entry.name.replace('\\', '/')
                val segments = name.split('/').filter { it.isNotEmpty() }
                if (name.startsWith("/") || name.contains('\u0000') || name.contains(':') || segments.any { it == ".." }) {
                    throw IOException("壓縮檔含有不安全的路徑:$name")
                }
                if (entry.isDirectory || segments.isEmpty()) continue
                val target = File(root, segments.joinToString(File.separator)).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) {
                    throw IOException("壓縮檔含有不安全的路徑:$name")
                }
                if (!isDataFileName(segments)) {
                    Log.i(TAG, "zip entry skipped: $name")
                    continue
                }

                // Look at the first bytes before anything is written.
                val head = ByteArray(8)
                var headLength = 0
                while (headLength < head.size) {
                    val n = zip.read(head, headLength, head.size - headLength)
                    if (n < 0) break
                    headLength += n
                }
                if (looksExecutable(head, headLength)) {
                    Log.w(TAG, "zip entry skipped (not a data file): $name")
                    continue
                }
                total += headLength
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    out.write(head, 0, headLength)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ZIP_BYTES) throw IOException("壓縮檔解開後超過 300 MB 上限")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun isDataFileName(segments: List<String>): Boolean {
        // Archive tool leftovers and hidden files.
        if (segments.any { it.startsWith(".") || it == "__MACOSX" }) return false
        val fileName = segments.last()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.isEmpty() || extension in DATA_EXTENSIONS
    }

    /** ELF, DEX, zip-based containers (apk/jar), Windows executables and scripts with a shebang. */
    private fun looksExecutable(head: ByteArray, length: Int): Boolean {
        fun startsWith(vararg magic: Int) =
            length >= magic.size && magic.indices.all { (head[it].toInt() and 0xff) == magic[it] }
        return startsWith(0x7f, 'E'.code, 'L'.code, 'F'.code) ||
            startsWith('d'.code, 'e'.code, 'x'.code, '\n'.code) ||
            startsWith('P'.code, 'K'.code) ||
            startsWith('M'.code, 'Z'.code) ||
            startsWith('#'.code, '!'.code)
    }

    /** Accepts archives made by zipping the pack's folder instead of its contents. */
    private fun hoistSingleFolder(dir: File) {
        if (File(dir, WallpaperPack.MANIFEST).isFile) return
        val top = dir.listFiles() ?: return
        val folder = top.singleOrNull()?.takeIf { it.isDirectory } ?: return
        if (!File(folder, WallpaperPack.MANIFEST).isFile) return
        // Rename first: a child may have the same name as the folder itself.
        val staging = File(dir, ".hoist")
        if (!folder.renameTo(staging)) throw IOException("無法整理插件包資料夾")
        staging.listFiles()?.forEach { child ->
            if (!child.renameTo(File(dir, child.name))) throw IOException("無法整理插件包資料夾")
        }
        staging.delete()
    }

    private fun copyAssetDir(context: Context, assetDir: String, dest: File) {
        val names = context.assets.list(assetDir) ?: emptyArray()
        names.forEach { name ->
            val path = "$assetDir/$name"
            val children = context.assets.list(path) ?: emptyArray()
            if (children.isNotEmpty()) {
                copyAssetDir(context, path, File(dest, name).apply { mkdirs() })
            } else {
                context.assets.open(path).use { input -> File(dest, name).outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
