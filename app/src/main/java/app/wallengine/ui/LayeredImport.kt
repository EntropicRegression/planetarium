package app.wallengine.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wallengine.engine.Parallax
import app.wallengine.engine.gl.BitmapLoader
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "LayeredImport"
private const val MAX_LAYERS = 8
private const val MAX_STRENGTH = 0.1f

/**
 * Returns a function that starts the "layered spatial wallpaper" flow: pick several images
 * (back to front), set each one's depth and the parallax strength, then build the pack.
 * [onImported] is called on the main thread with the new pack.
 */
@Composable
fun rememberLayeredImporter(store: PackStore, onImported: (WallpaperPack) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)

    var picked by remember { mutableStateOf<List<Uri>?>(null) }
    val depths = remember { mutableStateListOf<Float>() }
    var strength by remember { mutableStateOf(Parallax.DEFAULT_STRENGTH) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_LAYERS),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        depths.clear()
        // Selection order is back to front: spread the layers evenly from far (1) to near (0).
        uris.indices.forEach { i -> depths += if (uris.size == 1) 1f else 1f - i.toFloat() / (uris.size - 1) }
        picked = uris
    }

    picked?.let { uris ->
        AlertDialog(
            onDismissRequest = { if (!busy) picked = null },
            title = { Text("分層空間桌布") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "圖層依選取順序由後到前疊放。深度越近,傾斜手機時移動越多;最遠的圖層不動。" +
                            "前景圖層請使用透明背景的 PNG。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    uris.indices.forEach { i ->
                        Text(
                            "圖層 ${i + 1}:${depthLabel(depths[i])}",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Slider runs far -> near, the manifest's depth runs near (0) -> far (1).
                        Slider(value = 1f - depths[i], onValueChange = { depths[i] = 1f - it }, enabled = !busy)
                    }
                    Text(
                        "視差強度:${(strength / MAX_STRENGTH * 100).roundToInt()}%",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = strength,
                        onValueChange = { strength = it },
                        valueRange = 0f..MAX_STRENGTH,
                        enabled = !busy,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val layerDepths = depths.toList()
                        val parallaxStrength = strength
                        scope.launch {
                            try {
                                val pack = withContext(Dispatchers.IO) {
                                    buildLayeredPack(store, uris, layerDepths, parallaxStrength)
                                }
                                picked = null
                                currentOnImported(pack)
                            } catch (e: Exception) {
                                Log.e(TAG, "layered import failed", e)
                                Toast.makeText(context, "匯入失敗:${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "建立中…" else "建立") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { picked = null }) { Text("取消") }
            },
        )
    }

    return remember(picker) {
        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

private fun depthLabel(depth: Float): String = when {
    depth >= 0.995f -> "最遠(不動)"
    depth <= 0.005f -> "最近"
    else -> "距離 ${((1f - depth) * 100).roundToInt()}%"
}

private fun buildLayeredPack(store: PackStore, uris: List<Uri>, depths: List<Float>, strength: Float): WallpaperPack {
    val name = "空間 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
    val extras = JSONObject().put(
        "parallax",
        JSONObject().put("strength", strength.toDouble()).put("focus", 1.0),
    )
    return store.createPack(name, extras) { dir ->
        val layers = uris.mapIndexed { i, uri ->
            val src = "layer$i"
            store.copyUri(uri, File(dir, src))
            LayerSpec(WallpaperPack.TYPE_IMAGE, src, depths[i])
        }
        try {
            writeThumbnail(dir, layers)
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail failed", e)
        }
        layers
    }
}

/** Flattens the layers, far to near, each centre-cropped, into the pack's preview image. */
private fun writeThumbnail(dir: File, layers: List<LayerSpec>) {
    val width = 360
    val height = 640
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    layers.sortedByDescending { it.depth }.forEach { layer ->
        val bitmap = BitmapLoader.load(File(dir, layer.src), 640)
        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
        bitmap.recycle()
    }
    File(dir, WallpaperPack.THUMBNAIL).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    out.recycle()
}
