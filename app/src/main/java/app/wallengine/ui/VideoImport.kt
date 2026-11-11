package app.wallengine.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack

/**
 * Placeholder (milestone M1). Returns a function that starts the "import a video" flow;
 * [onImported] is called on the main thread with the new pack once it exists.
 */
@Composable
fun rememberVideoImporter(store: PackStore, onImported: (WallpaperPack) -> Unit): () -> Unit {
    val context = LocalContext.current
    return { Toast.makeText(context, "影片匯入尚未實作", Toast.LENGTH_SHORT).show() }
}
