package app.planetarium.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.planetarium.depth.DepthEstimator
import app.planetarium.engine.gl.BitmapLoader
import app.planetarium.pack.LayerSpec
import app.planetarium.pack.PackStore
import app.planetarium.pack.WallpaperPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "SpatialPhotoImport"
private const val MAX_STRENGTH = 0.06f
private const val DEFAULT_STRENGTH = 0.03f

/** Animated overlays that can be put in front of the photo. [asset] is a shader under assets/effects. */
private enum class Effect(val label: String, val asset: String?, val additive: Boolean) {
    None("無(只有景深視差,最省電)", null, false),
    Dust("光塵", "dust.frag", true),
    Snow("飄雪", "snow.frag", false),
    Rain("下雨", "rain.frag", false),
}

/**
 * Returns a function that starts the "photo to spatial wallpaper" flow: pick one photo, choose
 * the parallax strength and an optional animated overlay, then estimate the photo's depth on
 * the device and build the pack. [onImported] is called on the main thread with the new pack.
 */
@Composable
fun rememberSpatialPhotoImporter(store: PackStore, onImported: (WallpaperPack) -> Unit): () -> Unit {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)

    var picked by remember { mutableStateOf<Uri?>(null) }
    var strength by remember { mutableStateOf(DEFAULT_STRENGTH) }
    var effect by remember { mutableStateOf(Effect.None) }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked = uri
    }

    picked?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) picked = null },
            title = { Text("照片轉空間桌布") },
            text = {
                Column {
                    if (busy) {
                        Text("正在分析照片的景深,可能需要十幾秒…", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                    } else {
                        Text(
                            "會在手機上估算照片中每個位置的遠近,傾斜手機時近處與遠處朝相反方向移動。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "視差強度:${(strength / MAX_STRENGTH * 100).roundToInt()}%",
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.01f..MAX_STRENGTH)
                        Text("動態效果", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        Effect.entries.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = effect == option) { effect = option },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = effect == option, onClick = null, Modifier.padding(8.dp))
                                Text(option.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val chosenStrength = strength
                        val chosenEffect = effect
                        scope.launch {
                            try {
                                val pack = withContext(Dispatchers.Default) {
                                    buildSpatialPack(appContext, store, uri, chosenStrength, chosenEffect)
                                }
                                picked = null
                                currentOnImported(pack)
                            } catch (e: DepthEstimator.ModelMissingException) {
                                Toast.makeText(context, "這個版本沒有內含景深模型,無法分析照片", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Log.e(TAG, "spatial photo import failed", e)
                                Toast.makeText(context, "匯入失敗:${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("建立") }
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

private fun buildSpatialPack(
    context: Context,
    store: PackStore,
    uri: Uri,
    strength: Float,
    effect: Effect,
): WallpaperPack {
    val name = "空間照片 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
    // Focus in the middle: near and far move in opposite directions, which halves the largest
    // displacement (and so the stretching at depth edges) for the same sense of depth.
    val extras = JSONObject().put(
        "parallax",
        JSONObject().put("strength", strength.toDouble()).put("focus", 0.5),
    )
    return store.createPack(name, extras) { dir ->
        val photoFile = File(dir, "photo")
        store.copyUri(uri, photoFile)

        // The model works at about 518 px, so there is no point decoding the photo much larger.
        val photo = BitmapLoader.load(photoFile, 1024)
        val depth = try {
            DepthEstimator.estimate(context, photo)
        } finally {
            photo.recycle()
        }
        File(dir, "depth.png").outputStream().use { depth.compress(Bitmap.CompressFormat.PNG, 100, it) }
        depth.recycle()

        val layers = mutableListOf(
            LayerSpec(WallpaperPack.TYPE_IMAGE, "photo", 1f, JSONObject().put("depthMap", "depth.png")),
        )
        effect.asset?.let { asset ->
            context.assets.open("effects/$asset").use { input ->
                File(dir, "effect.frag").outputStream().use { input.copyTo(it) }
            }
            val options = JSONObject().put("blend", if (effect.additive) "add" else "normal")
            layers += LayerSpec(WallpaperPack.TYPE_SHADER, "effect.frag", 0f, options)
        }
        layers
    }
}
