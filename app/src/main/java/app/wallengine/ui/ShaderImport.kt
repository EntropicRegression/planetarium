package app.wallengine.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack

/**
 * Placeholder (milestone M2). Returns a function that starts the "import a shader / plugin pack"
 * flow; [onImported] is called on the main thread with the new pack once it exists.
 */
@Composable
fun rememberShaderImporter(store: PackStore, onImported: (WallpaperPack) -> Unit): () -> Unit {
    val context = LocalContext.current
    return { Toast.makeText(context, "Shader 匯入尚未實作", Toast.LENGTH_SHORT).show() }
}
