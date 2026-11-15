package app.planetarium.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import app.planetarium.studio.StudioActivity
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.planetarium.engine.ShaderValidator
import app.planetarium.engine.Benchmark
import app.planetarium.engine.Renderer
import app.planetarium.pack.PackStore
import app.planetarium.pack.WallpaperPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val TAG = "PackActions"

private enum class Mode { Menu, Params, Rename, Editor, ConfirmDelete, Benchmark }

/** A user-adjustable shader parameter, from the manifest's "params" array. */
private class ParamDef(val id: String, val label: String, val min: Float, val max: Float, val default: Float)

private fun paramDefs(pack: WallpaperPack): List<ParamDef> {
    val array = pack.manifest.optJSONArray("params") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val json = array.optJSONObject(i) ?: return@mapNotNull null
        val id = json.optString("id").ifEmpty { return@mapNotNull null }
        val min = json.optDouble("min", 0.0).toFloat()
        val max = json.optDouble("max", 1.0).toFloat()
        if (!(max > min)) return@mapNotNull null
        ParamDef(
            id = id,
            label = json.optString("name").ifEmpty { id },
            min = min,
            max = max,
            default = json.optDouble("default", min.toDouble()).toFloat().coerceIn(min, max),
        )
    }
}

private const val BLANK_SHADER = """// 新的 shader。修改後按「儲存」,會先編譯檢查,通過才套用到桌布。
// 可用的輸入(完整說明見 docs/PACK_FORMAT.md):
//   iResolution  畫面大小(像素)      iTime   經過的秒數
//   uTouch       xy = 觸控位置 0..1,z = 是否按著
//   uTilt        手機傾斜 -1..1        uOffset 桌面滑頁 0..1
//   speed        下方 manifest 裡定義的自訂參數,可在「調整參數」中改

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    uv += uTilt * 0.03;

    // 由下往上緩慢流動的色帶
    float band = sin(uv.y * 9.0 + uv.x * 2.5 - iTime * speed);
    vec3 low = vec3(0.07, 0.10, 0.28);
    vec3 high = vec3(0.95, 0.45, 0.35);
    vec3 col = mix(low, high, uv.y * 0.75 + band * 0.12);

    // 手指附近亮起來
    float glow = 1.0 - smoothstep(0.0, 0.25, distance(uv, uTouch.xy));
    col += vec3(0.25) * glow * uTouch.z;

    fragColor = vec4(col, 1.0);
}
"""

/** Creates a pack holding a small starter shader with one adjustable parameter. Blocking. */
internal fun createBlankShaderPack(context: Context, store: PackStore, name: String): WallpaperPack {
    val extras = org.json.JSONObject().put(
        "params",
        org.json.JSONArray().put(
            org.json.JSONObject()
                .put("id", "speed").put("name", "流動速度").put("type", "float")
                .put("min", 0.0).put("max", 3.0).put("default", 0.6),
        ),
    )
    val pack = store.createPack(name, extras) { dir ->
        File(dir, "main.frag").writeText(BLANK_SHADER)
        listOf(app.planetarium.pack.LayerSpec(WallpaperPack.TYPE_SHADER, "main.frag"))
    }
    // Renders the preview image; the starter is known to compile.
    ShaderValidator.validate(context, pack)
    return pack
}

private fun shaderFile(pack: WallpaperPack): File? =
    pack.layers.firstOrNull { it.type == WallpaperPack.TYPE_SHADER }?.let { pack.asset(it.src) }

/**
 * Everything that can be done with one pack: apply it to a screen, tune its shader parameters,
 * edit its shader, rename, export, delete. [onChanged] is called after the library changed and
 * should refresh the list; [onDismiss] closes the whole thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackActionsDialog(
    pack: WallpaperPack,
    store: PackStore,
    kindLabel: String,
    startInEditor: Boolean,
    onApply: (Array<PackStore.Target>) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val params = remember(pack.id) { paramDefs(pack) }
    val shader = remember(pack.id) { shaderFile(pack) }
    var mode by remember(pack.id) { mutableStateOf(if (startInEditor && shader != null) Mode.Editor else Mode.Menu) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { store.exportZip(pack, uri) }.exceptionOrNull()
            }
            if (error != null) Log.e(TAG, "export failed", error)
            Toast.makeText(context, if (error == null) "已匯出" else "匯出失敗:${error.message}", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    when (mode) {
        // Opened at full height: half-open, the lower actions (export, delete) were out of sight.
        Mode.Menu -> ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(54.dp)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        rememberThumbnail(pack, store)?.let {
                            Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(pack.name, maxLines = 2, style = MaterialTheme.typography.titleLarge)
                        Text(
                            kindLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Text(
                    "套用到",
                    Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleSmall,
                )
                Button(
                    onClick = {
                        onApply(arrayOf(PackStore.Target.HOME, PackStore.Target.LOCK))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) { Text("主畫面與鎖定畫面") }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            onApply(arrayOf(PackStore.Target.HOME))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = null, Modifier.size(18.dp))
                        Text("只有主畫面", Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(
                        onClick = {
                            onApply(arrayOf(PackStore.Target.LOCK))
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, Modifier.size(18.dp))
                        Text("只有鎖定畫面", Modifier.padding(start = 6.dp))
                    }
                }

                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                if (params.isNotEmpty()) SheetAction(Icons.Filled.Settings, "調整參數") { mode = Mode.Params }
                if (shader != null) SheetAction(Icons.Filled.Edit, "編輯 Shader") { mode = Mode.Editor }
                // The studio works on picture and video layers; a pack of shaders has nothing for it.
                if (pack.layers.any { it.type != WallpaperPack.TYPE_SHADER }) {
                    SheetAction(Icons.Filled.Face, "用 AI 修改(另存為新桌布)") {
                        StudioActivity.open(context, pack.id)
                        onDismiss()
                    }
                }
                SheetAction(Icons.Filled.Info, "測量效能負擔") { mode = Mode.Benchmark }
                SheetAction(Icons.Filled.Create, "重新命名") { mode = Mode.Rename }
                SheetAction(Icons.Filled.Share, "匯出為 .zip") {
                    try {
                        exporter.launch(pack.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".zip")
                    } catch (e: Exception) {
                        Toast.makeText(context, "此裝置沒有可用的檔案選擇器", Toast.LENGTH_LONG).show()
                    }
                }
                SheetAction(Icons.Filled.Delete, "刪除", MaterialTheme.colorScheme.error) { mode = Mode.ConfirmDelete }
            }
        }

        Mode.Params -> ParamsDialog(pack, store, params, onDismiss)

        Mode.Rename -> {
            var name by remember { mutableStateOf(pack.name) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("重新命名") },
                text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.rename(pack, name.trim()) }
                                onChanged()
                                onDismiss()
                            }
                        },
                    ) { Text("儲存") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }

        Mode.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("刪除「${pack.name}」?") },
            text = { Text("桌布包和裡面的檔案會永久刪除,無法復原。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(pack.id) }
                        onChanged()
                        onDismiss()
                    }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )

        Mode.Editor -> shader?.let {
            ShaderEditor(pack, store, it, onSaved = onChanged, onDismiss = onDismiss)
        }

        Mode.Benchmark -> BenchmarkDialog(pack, onDismiss)
    }
}

/** Measures the pack on this device's GPU and shows what one frame and each layer cost. */
@Composable
private fun BenchmarkDialog(pack: WallpaperPack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pack.id) {
        text = withContext(Dispatchers.Default) {
            try {
                val bounds = context.getSystemService(android.view.WindowManager::class.java).let {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        it.maximumWindowMetrics.bounds
                    } else {
                        @Suppress("DEPRECATION")
                        android.graphics.Point().also { size -> it.defaultDisplay.getRealSize(size) }
                            .let { size -> android.graphics.Rect(0, 0, size.x, size.y) }
                    }
                }
                // At the resolution the wallpaper is set to draw at, so the numbers are the wallpaper's.
                val scale = PackStore(context).renderScale()
                val report = Benchmark.run(
                    context, pack,
                    (bounds.width() * scale).toInt().coerceAtLeast(1),
                    (bounds.height() * scale).toInt().coerceAtLeast(1),
                )
                Benchmark.describe(pack, report, Renderer.lastStats(context)?.takeIf { it.packId == pack.id })
            } catch (e: Exception) {
                Log.e(TAG, "benchmark failed", e)
                "測量失敗:${e.message ?: e}"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("效能負擔") },
        text = {
            val result = text
            if (result == null) {
                Column {
                    Text("正在這支手機上測量,大約需要十秒…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            } else {
                SelectionContainer {
                    Text(
                        result,
                        Modifier.verticalScroll(rememberScrollState()),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        dismissButton = {
            val result = text
            if (result != null) {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Planetarium", result))
                    Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
                }) { Text("複製") }
            }
        },
    )
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    )
}

@Composable
private fun ParamsDialog(pack: WallpaperPack, store: PackStore, defs: List<ParamDef>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val values = remember(pack.id) { mutableStateMapOf<String, Float>() }
    LaunchedEffect(pack.id) {
        val saved = withContext(Dispatchers.IO) { store.readParams(pack) }
        defs.forEach { values[it.id] = (saved[it.id] ?: it.default).coerceIn(it.min, it.max) }
    }

    // The wallpaper reloads the pack to pick up new values, so apply when a drag ends, not during it.
    fun apply() {
        val snapshot = values.toMap()
        scope.launch {
            withContext(Dispatchers.IO) {
                store.writeParams(pack, snapshot)
                store.notifyChanged()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("調整參數") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                defs.forEach { def ->
                    val value = values[def.id] ?: def.default
                    Text(
                        "${def.label}:${String.format(Locale.US, "%.2f", value)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = value,
                        onValueChange = { values[def.id] = it },
                        onValueChangeFinished = { apply() },
                        valueRange = def.min..def.max,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = {
                defs.forEach { values[it.id] = it.default }
                apply()
            }) { Text("還原預設") }
        },
    )
}

/**
 * Full-screen source editor for a pack's shader. Saving compiles the new source first; a shader
 * that does not compile is not saved and the compiler log is shown instead.
 */
@Composable
private fun ShaderEditor(
    pack: WallpaperPack,
    store: PackStore,
    file: File,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(pack.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(pack.id) {
        source = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrDefault("") }
    }

    // Drawn in the activity itself rather than a dialog window: the activity is edge-to-edge, so
    // the system bar and keyboard insets below are exact and the error panel stays on screen.
    BackHandler(enabled = !busy, onBack = onDismiss)
    run {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pack.name,
                        Modifier.weight(1f).padding(start = 8.dp),
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                    TextButton(
                        enabled = !busy && source != null,
                        onClick = {
                            val text = source ?: return@TextButton
                            busy = true
                            scope.launch {
                                val log = withContext(Dispatchers.IO) { saveShader(context, store, pack, file, text) }
                                busy = false
                                error = log
                                if (log == null) {
                                    onSaved()
                                    Toast.makeText(context, "已儲存並套用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) { Text("儲存") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                val code = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Long lines scroll sideways instead of wrapping, so the code keeps its shape.
                BasicTextField(
                    value = source ?: "",
                    onValueChange = { source = it },
                    enabled = source != null && !busy,
                    textStyle = code,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )

                error?.let { log ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            "編譯失敗,尚未儲存",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.padding(top = 4.dp))
                        SelectionContainer {
                            Text(
                                log,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compiles [text] as the pack's shader and keeps it only if it compiles. Returns null on
 * success, else the compiler log; on failure the old shader and preview are put back.
 */
private fun saveShader(context: Context, store: PackStore, pack: WallpaperPack, file: File, text: String): String? {
    val thumb = File(pack.dir, WallpaperPack.THUMBNAIL)
    val oldSource = file.readBytes()
    val oldThumb = if (thumb.isFile) thumb.readBytes() else null
    fun restore() {
        file.writeBytes(oldSource)
        if (oldThumb != null) thumb.writeBytes(oldThumb) else thumb.delete()
    }
    return try {
        file.writeText(text)
        // The validator renders a new preview only when there is none.
        thumb.delete()
        when (val result = ShaderValidator.validate(context, pack)) {
            is ShaderValidator.Result.Ok -> {
                if (!thumb.isFile && oldThumb != null) thumb.writeBytes(oldThumb)
                store.notifyChanged()
                null
            }
            is ShaderValidator.Result.Failed -> {
                restore()
                result.log
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "saving shader failed", e)
        restore()
        e.message ?: e.toString()
    }
}
