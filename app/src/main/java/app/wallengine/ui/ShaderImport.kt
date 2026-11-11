package app.wallengine.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack
import app.wallengine.ui.ShaderPackImporter.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ImportStep { Idle, Choose, Samples, Busy }

/**
 * Returns a function that starts the "import a shader / plugin pack" flow: a chooser (single
 * shader file, plugin pack zip, or a built-in sample), then import and shader validation off the
 * main thread. [onImported] is called on the main thread with the new pack; a pack whose shader
 * does not compile is removed again and the compiler log is shown instead.
 */
@Composable
fun rememberShaderImporter(store: PackStore, onImported: (WallpaperPack) -> Unit): () -> Unit {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)

    var step by remember { mutableStateOf(ImportStep.Idle) }
    var samples by remember { mutableStateOf(emptyList<ShaderPackImporter.Sample>()) }
    var failure by remember { mutableStateOf<Outcome.Rejected?>(null) }

    fun startImport(work: () -> Outcome) {
        step = ImportStep.Busy
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { work() }
            } catch (e: Exception) {
                Outcome.Rejected("匯入失敗", e.message ?: e.toString())
            }
            step = ImportStep.Idle
            when (outcome) {
                is Outcome.Imported -> currentOnImported(outcome.pack)
                is Outcome.Rejected -> failure = outcome
            }
        }
    }

    val shaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport { ShaderPackImporter.importShaderFile(appContext, store, uri) }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport { ShaderPackImporter.importZip(appContext, store, uri) }
    }

    fun launchPicker(launch: () -> Unit) {
        step = ImportStep.Idle
        try {
            launch()
        } catch (e: Exception) {
            Toast.makeText(context, "此裝置沒有可用的檔案選擇器", Toast.LENGTH_LONG).show()
        }
    }

    when (step) {
        ImportStep.Idle -> {}
        ImportStep.Choose -> AlertDialog(
            onDismissRequest = { step = ImportStep.Idle },
            title = { Text("匯入 Shader / 插件包") },
            text = {
                Column {
                    ChoiceRow("Shader 檔案", "單一 GLSL 片段著色器(.frag、.glsl、.fs、.txt)") {
                        // Shader files have no dependable MIME type; the extension is checked after picking.
                        launchPicker { shaderPicker.launch(arrayOf("*/*")) }
                    }
                    ChoiceRow("插件包(.zip)", "含 manifest.json 的桌布包,可包含圖片、影片與 shader") {
                        launchPicker {
                            zipPicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                            )
                        }
                    }
                    ChoiceRow("內建範例", "加入內建的空間桌布或 shader 桌布") {
                        step = ImportStep.Busy
                        scope.launch {
                            samples = withContext(Dispatchers.IO) {
                                runCatching { ShaderPackImporter.samples(appContext) }.getOrDefault(emptyList())
                            }
                            step = ImportStep.Samples
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { step = ImportStep.Idle }) { Text("取消") } },
        )
        ImportStep.Samples -> AlertDialog(
            onDismissRequest = { step = ImportStep.Idle },
            title = { Text("內建範例") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (samples.isEmpty()) Text("沒有可用的範例")
                    samples.forEach { sample ->
                        ChoiceRow(sample.name, null) {
                            startImport { ShaderPackImporter.importSample(appContext, store, sample) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { step = ImportStep.Idle }) { Text("取消") } },
            dismissButton = { TextButton(onClick = { step = ImportStep.Choose }) { Text("返回") } },
        )
        ImportStep.Busy -> Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(20.dp))
                    Text("處理中…")
                }
            }
        }
    }

    failure?.let { rejected ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(rejected.title) },
            text = {
                // Compiler logs are long: scrollable, and selectable so the author can copy them.
                SelectionContainer {
                    Text(
                        rejected.detail,
                        Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("關閉") } },
        )
    }

    return { step = ImportStep.Choose }
}

@Composable
private fun ChoiceRow(title: String, description: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
