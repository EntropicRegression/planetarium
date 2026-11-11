package app.planetarium.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.planetarium.engine.GlWallpaperService
import app.planetarium.pack.PackStore
import app.planetarium.pack.WallpaperPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WallTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { LibraryScreen() }
            }
        }
    }
}

/** On Android 12+ the app takes its colours from the system palette, which follows the wallpaper. */
@Composable
private fun WallTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** What a pack is, as far as the library is concerned. */
internal enum class PackKind(val label: String) {
    IMAGE("圖片"),
    VIDEO("影片"),
    SPATIAL("空間"),
    SHADER("Shader"),
}

internal fun kindOf(pack: WallpaperPack): PackKind {
    val layers = pack.layers
    return when {
        layers.isNotEmpty() && layers.all { it.type == WallpaperPack.TYPE_SHADER } -> PackKind.SHADER
        layers.size > 1 || layers.any { it.options.has("depthMap") } -> PackKind.SPATIAL
        layers.any { it.type == WallpaperPack.TYPE_VIDEO } -> PackKind.VIDEO
        else -> PackKind.IMAGE
    }
}

/** The pack's preview image; re-read when it was regenerated (after editing the pack's shader). */
@Composable
internal fun rememberThumbnail(pack: WallpaperPack, store: PackStore): ImageBitmap? {
    val stamp = remember(pack) { File(pack.dir, WallpaperPack.THUMBNAIL).lastModified() }
    val thumbnail by produceState<ImageBitmap?>(null, pack.id, stamp) {
        value = withContext(Dispatchers.IO) { store.thumbnail(pack)?.asImageBitmap() }
    }
    return thumbnail
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    val store = remember { PackStore(context) }
    val scope = rememberCoroutineScope()

    var packs by remember { mutableStateOf(emptyList<WallpaperPack>()) }
    var loaded by remember { mutableStateOf(false) }
    var activeHome by remember { mutableStateOf<String?>(null) }
    var activeLock by remember { mutableStateOf<String?>(null) }
    var serviceActive by remember { mutableStateOf(false) }
    /** The pack whose actions sheet is open. */
    var choosingFor by remember { mutableStateOf<WallpaperPack?>(null) }
    var openEditor by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<PackKind?>(null) }
    var importOpen by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val (list, active) = withContext(Dispatchers.IO) {
            // First launch (or a new version with more samples): the built-in wallpapers appear by themselves.
            try {
                ShaderPackImporter.installNewSamples(context.applicationContext, store)
            } catch (e: Exception) {
                android.util.Log.w("LibraryScreen", "built-in samples not installed", e)
            }
            store.list() to (store.activeId(PackStore.Target.HOME) to store.activeId(PackStore.Target.LOCK))
        }
        packs = list
        activeHome = active.first
        // No separate lock choice means the lock screen shows the home wallpaper.
        activeLock = active.second ?: active.first
        loaded = true
    }

    // Also re-checks whether we are the system wallpaper after returning from the system picker.
    LifecycleResumeEffect(Unit) {
        serviceActive = isOurWallpaperActive(context)
        scope.launch { refresh() }
        onPauseOrDispose { }
    }

    fun select(id: String?, vararg targets: PackStore.Target = arrayOf(PackStore.Target.HOME, PackStore.Target.LOCK)) {
        scope.launch {
            withContext(Dispatchers.IO) { store.setActive(id, *targets) }
            refresh()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
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
    val importVideo = rememberVideoImporter(store) { select(it.id) }
    val importShader = rememberShaderImporter(store) { select(it.id) }
    val importLayered = rememberLayeredImporter(store) { select(it.id) }
    val importSpatialPhoto = rememberSpatialPhotoImporter(store) { select(it.id) }

    fun newShader() {
        scope.launch {
            try {
                val name = "Shader " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                val pack = withContext(Dispatchers.IO) { createBlankShaderPack(context.applicationContext, store, name) }
                refresh()
                openEditor = true
                choosingFor = pack
            } catch (e: Exception) {
                Toast.makeText(context, "建立失敗:${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("桌布庫") }, scrollBehavior = scrollBehavior) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { importOpen = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("新增桌布") },
            )
        },
    ) { padding ->
        val kinds = remember(packs) { packs.map(::kindOf) }
        val shown = packs.filterIndexed { i, _ -> filter == null || kinds[i] == filter }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                // Room for the floating button, so the last row is never hidden behind it.
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loaded && !serviceActive) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ApplyBanner(hasPacks = packs.isNotEmpty()) { openSystemWallpaperPicker(context) }
                }
            }
            if (packs.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CurrentSection(
                        home = packs.firstOrNull { it.id == activeHome },
                        lock = packs.firstOrNull { it.id == activeLock },
                        store = store,
                        onOpen = { choosingFor = it },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FilterRow(
                        counts = PackKind.entries.associateWith { kind -> kinds.count { it == kind } },
                        total = packs.size,
                        selected = filter,
                        onSelect = { filter = it },
                    )
                }
            }
            if (loaded && packs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState { importOpen = true } }
            }
            items(shown, key = { it.id }) { pack ->
                PackCard(
                    pack = pack,
                    store = store,
                    kind = kindOf(pack),
                    onHome = pack.id == activeHome,
                    onLock = pack.id == activeLock,
                    onClick = { choosingFor = pack },
                )
            }
        }
    }

    if (importOpen) {
        ImportSheet(
            onDismiss = { importOpen = false },
            options = listOf(
                ImportOption(Icons.Filled.AccountBox, "圖片", "把一張照片設成桌布") {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                ImportOption(Icons.Filled.PlayArrow, "影片", "循環播放的動態桌布") { importVideo() },
                ImportOption(Icons.Filled.Star, "照片轉空間桌布", "自動分析景深,傾斜手機時有立體感") { importSpatialPhoto() },
                ImportOption(Icons.AutoMirrored.Filled.List, "分層空間桌布", "多張圖片依遠近疊放") { importLayered() },
                ImportOption(Icons.Filled.Build, "Shader / 插件包", "匯入 .frag、桌布包 .zip 或內建範例") { importShader() },
                ImportOption(Icons.Filled.Create, "新建 Shader", "從範本開始,用內建編輯器撰寫") { newShader() },
            ),
        )
    }

    // After the Scaffold so that the full-screen shader editor is drawn on top of it.
    choosingFor?.let { pack ->
        PackActionsDialog(
            pack = pack,
            store = store,
            kindLabel = kindOf(pack).label,
            startInEditor = openEditor,
            onApply = { targets -> select(pack.id, *targets) },
            onChanged = { scope.launch { refresh() } },
            onDismiss = {
                choosingFor = null
                openEditor = false
            },
        )
    }
}

/** Shown while the system wallpaper is something else: nothing in the library is visible yet. */
@Composable
private fun ApplyBanner(hasPacks: Boolean, onApply: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("還沒有設為系統桌布", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasPacks) "設定一次之後,在這裡選的桌布就會直接出現在主畫面與鎖定畫面。"
                else "先新增一個桌布,再把 Planetarium 設為系統桌布。",
                Modifier.padding(top = 4.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onApply) { Text("設為系統桌布") }
        }
    }
}

/** The two wallpapers currently in use, side by side. */
@Composable
private fun CurrentSection(home: WallpaperPack?, lock: WallpaperPack?, store: PackStore, onOpen: (WallpaperPack) -> Unit) {
    Column {
        SectionTitle("目前使用中")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CurrentTile(Icons.Filled.Home, "主畫面", home, store, onOpen, Modifier.weight(1f))
            CurrentTile(Icons.Filled.Lock, "鎖定畫面", lock, store, onOpen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CurrentTile(
    icon: ImageVector,
    label: String,
    pack: WallpaperPack?,
    store: PackStore,
    onOpen: (WallpaperPack) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clip(CardDefaults.shape).clickable(enabled = pack != null) { pack?.let(onOpen) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(46.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val thumbnail = pack?.let { rememberThumbnail(it, store) }
                if (thumbnail != null) {
                    Image(thumbnail, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(Modifier.padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        label,
                        Modifier.padding(start = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    pack?.name ?: "尚未選擇",
                    Modifier.padding(top = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(top = 4.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleSmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(counts: Map<PackKind, Int>, total: Int, selected: PackKind?, onSelect: (PackKind?) -> Unit) {
    Column {
        SectionTitle("全部桌布")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部 $total") })
            PackKind.entries.filter { (counts[it] ?: 0) > 0 }.forEach { kind ->
                FilterChip(
                    selected = selected == kind,
                    onClick = { onSelect(if (selected == kind) null else kind) },
                    label = { Text("${kind.label} ${counts[kind]}") },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("這裡還沒有桌布", style = MaterialTheme.typography.titleLarge)
        Text(
            "可以用照片、影片、分層圖片或 Shader 做成動態桌布。",
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onAdd) { Text("新增第一個桌布") }
    }
}

/** A phone-shaped preview with the name over a scrim, and markers for where the pack is in use. */
@Composable
private fun PackCard(
    pack: WallpaperPack,
    store: PackStore,
    kind: PackKind,
    onHome: Boolean,
    onLock: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val inUse = onHome || onLock
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (inUse) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        rememberThumbnail(pack, store)?.let {
            Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        // The scrim keeps the white label readable over any picture.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(110.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                kind.label,
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                pack.name,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (inUse) {
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onHome) UseBadge(Icons.Filled.Home, "用於主畫面")
                if (onLock) UseBadge(Icons.Filled.Lock, "用於鎖定畫面")
            }
        }
    }
}

@Composable
private fun UseBadge(icon: ImageVector, description: String) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
    }
}

private class ImportOption(val icon: ImageVector, val title: String, val description: String, val action: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(options: List<ImportOption>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Text(
                "新增桌布",
                Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            options.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.title) },
                    supportingContent = { Text(option.description) },
                    leadingContent = {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(option.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        onDismiss()
                        option.action()
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
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
