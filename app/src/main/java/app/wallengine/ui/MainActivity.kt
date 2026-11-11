package app.wallengine.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wallengine.engine.GlWallpaperService
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { LibraryScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    val store = remember { PackStore(context) }
    val scope = rememberCoroutineScope()

    var packs by remember { mutableStateOf(emptyList<WallpaperPack>()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var serviceActive by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val (list, active) = withContext(Dispatchers.IO) { store.list() to store.activeId(PackStore.Target.HOME) }
        packs = list
        activeId = active
    }

    // Also re-checks whether we are the system wallpaper after returning from the system picker.
    LifecycleResumeEffect(Unit) {
        serviceActive = isOurWallpaperActive(context)
        scope.launch { refresh() }
        onPauseOrDispose { }
    }

    fun select(id: String?) {
        scope.launch {
            withContext(Dispatchers.IO) { store.setActive(id, PackStore.Target.HOME, PackStore.Target.LOCK) }
            refresh()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val name = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                val pack = withContext(Dispatchers.IO) { store.importImage(uri, "圖片 $name") }
                select(pack.id)
            } catch (e: Exception) {
                Toast.makeText(context, "匯入失敗:${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("桌布庫") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("匯入圖片") },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                if (serviceActive) {
                    Text(
                        "已設為系統桌布,點選下方任一桌布即可切換",
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Button(onClick = { openSystemWallpaperPicker(context) }, Modifier.fillMaxWidth()) {
                        Text("設為系統桌布")
                    }
                }
            }
        },
    ) { padding ->
        if (packs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("還沒有桌布,先匯入一張圖片")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(packs, key = { it.id }) { pack ->
                    PackCard(
                        pack = pack,
                        store = store,
                        selected = pack.id == activeId,
                        onSelect = { select(pack.id) },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.IO) { store.delete(pack.id) }
                                refresh()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: WallpaperPack,
    store: PackStore,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(null, pack.id) {
        value = withContext(Dispatchers.IO) { store.thumbnail(pack)?.asImageBitmap() }
    }
    Card(
        modifier = Modifier.clickable(onClick = onSelect),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            thumbnail?.let {
                Image(it, contentDescription = pack.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "${pack.name}(使用中)" else pack.name,
                Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "刪除 ${pack.name}")
            }
        }
    }
}

private fun isOurWallpaperActive(context: Context): Boolean =
    WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName

/** Third-party apps cannot set a live wallpaper silently; this opens the system preview to confirm. */
private fun openSystemWallpaperPicker(context: Context) {
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
        ComponentName(context, GlWallpaperService::class.java),
    )
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "此裝置無法開啟動態桌布設定", Toast.LENGTH_LONG).show()
    }
}
