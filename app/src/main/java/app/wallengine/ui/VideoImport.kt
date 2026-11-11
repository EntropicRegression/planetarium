package app.wallengine.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.wallengine.pack.LayerSpec
import app.wallengine.pack.PackStore
import app.wallengine.pack.WallpaperPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val TAG = "VideoImport"
private const val VIDEO_FILE = "video.mp4"
private const val THUMB_MAX = 512

/**
 * Returns a function that starts the "import a video" flow: system Photo Picker, then the video
 * is copied (or transcoded, see [importVideo]) into a new pack while a modal progress dialog is
 * shown. [onImported] is called on the main thread with the new pack once it exists.
 */
@Composable
fun rememberVideoImporter(store: PackStore, onImported: (WallpaperPack) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImported by rememberUpdatedState(onImported)

    // null = idle; negative = working, amount unknown; 0..1 = transcoding progress.
    var progress by remember { mutableStateOf<Float?>(null) }

    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri == null || progress != null) return@rememberLauncherForActivityResult
        progress = -1f
        scope.launch {
            try {
                val pack = importVideo(context, store, uri) { progress = it }
                currentOnImported(pack)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "video import failed", e)
                Toast.makeText(context, "匯入失敗:${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress = null
            }
        }
    }

    progress?.let { ImportProgressDialog(it) }

    return remember(picker) {
        { picker.launch(PickVisualMediaRequest(PickVisualMedia.VideoOnly)) }
    }
}

@Composable
private fun ImportProgressDialog(progress: Float) {
    // Not dismissable: the import keeps running and finishes by itself.
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("正在匯入影片", style = MaterialTheme.typography.titleMedium)
                if (progress < 0f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("處理中…", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("轉檔中 ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private class VideoInfo(
    /** Size as displayed, i.e. with the rotation metadata applied. 0 if unknown. */
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val hdr: Boolean,
)

/**
 * Imports [uri] as a new single-layer video pack. Callable from any dispatcher.
 *
 * The video is run through Transformer only when that buys something: it has an audio track
 * (dropped; the video stream is then passed through untouched), it is larger than the screen
 * (re-encoded to H.264 at screen size) or it is HDR (tone-mapped to SDR, since the wallpaper
 * renders into an SDR surface). Otherwise, or if Transformer fails, the original is copied as is.
 *
 * [onProgress] is called on the main thread: 0..1 while transcoding, negative when unknown.
 */
private suspend fun importVideo(
    context: Context,
    store: PackStore,
    uri: Uri,
    onProgress: (Float) -> Unit,
): WallpaperPack {
    val appContext = context.applicationContext
    // Same volume as the pack library (device-protected storage), so the result can be renamed into place.
    val tmp = File(appContext.createDeviceProtectedStorageContext().cacheDir, "video-import-${UUID.randomUUID()}.mp4")
    try {
        val info = withContext(Dispatchers.IO) { probe(appContext, uri) }
        val targetHeight = downscaledHeight(info, screenSize(context))
        val transcoded = if (info.hasAudio || info.hdr || targetHeight != null) {
            try {
                transcode(appContext, uri, tmp, targetHeight, onProgress)
                check(tmp.length() > 0) { "transformer produced an empty file" }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "transcoding failed, keeping the original", e)
                false
            }
        } else {
            false
        }
        withContext(Dispatchers.Main) { onProgress(-1f) }

        return withContext(Dispatchers.IO) {
            val name = "影片 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            store.createPack(name) { dir ->
                val video = File(dir, VIDEO_FILE)
                if (!transcoded) {
                    store.copyUri(uri, video)
                } else if (!tmp.renameTo(video)) {
                    tmp.copyTo(video, overwrite = true)
                }
                check(video.length() > 0) { "影片檔案是空的" }
                writeThumbnail(video, File(dir, WallpaperPack.THUMBNAIL))
                listOf(LayerSpec(WallpaperPack.TYPE_VIDEO, VIDEO_FILE))
            }
        }
    } finally {
        tmp.delete()
    }
}

private fun probe(context: Context, uri: Uri): VideoInfo {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, uri)
        fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull()
        val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0
        val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0
        val rotated = (int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0) % 180 != 0
        val hdr = Build.VERSION.SDK_INT >= 30 &&
            int(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER).let {
                it == MediaFormat.COLOR_TRANSFER_HLG || it == MediaFormat.COLOR_TRANSFER_ST2084
            }
        return VideoInfo(
            width = if (rotated) h else w,
            height = if (rotated) w else h,
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null,
            hdr = hdr,
        )
    } catch (e: Exception) {
        // Unknown container or broken metadata: let the copy path (and later the player) decide.
        Log.w(TAG, "could not read video metadata", e)
        return VideoInfo(0, 0, hasAudio = false, hdr = false)
    } finally {
        retriever.release()
    }
}

/** Physical screen size in pixels, whatever the current orientation or window size. */
private fun screenSize(context: Context): Point {
    val wm = context.getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= 30) {
        wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
    } else {
        @Suppress("DEPRECATION")
        Point().also { wm.defaultDisplay.getRealSize(it) }
    }
}

/**
 * The (displayed) height to re-encode at, or null if the video already fits the screen. "Fits"
 * ignores orientation: short side within the screen's short side, long side within its long side.
 */
private fun downscaledHeight(info: VideoInfo, screen: Point): Int? {
    if (info.width <= 0 || info.height <= 0 || screen.x <= 0 || screen.y <= 0) return null
    val factor = minOf(
        minOf(screen.x, screen.y).toFloat() / minOf(info.width, info.height),
        maxOf(screen.x, screen.y).toFloat() / maxOf(info.width, info.height),
    )
    // Not worth a lossy re-encode for a few percent.
    if (factor > 0.9f) return null
    // Even dimensions keep H.264 encoders happy.
    return ((info.height * factor / 2f).roundToInt() * 2).coerceAtLeast(2)
}

/**
 * Runs Transformer on the main looper (it must be created and used on one Looper thread) and
 * suspends until its listener reports the outcome. Cancelling the coroutine cancels the export.
 */
@OptIn(UnstableApi::class)
private suspend fun transcode(
    context: Context,
    uri: Uri,
    out: File,
    targetHeight: Int?,
    onProgress: (Float) -> Unit,
) = withContext(Dispatchers.Main) {
    coroutineScope {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setRemoveAudio(true)
            .apply {
                if (targetHeight != null) {
                    setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                }
            }
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
            // Only affects HDR input; SDR video without effects is still passed through unencoded.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val builder = Transformer.Builder(context)
        // Without a forced MIME type, a video that needs no re-encode keeps its original stream.
        if (targetHeight != null) builder.setVideoMimeType(MimeTypes.VIDEO_H264)
        val transformer = builder.build()

        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                delay(200)
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress / 100f)
                }
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                cont.invokeOnCancellation {
                    // May run on any thread; Transformer insists on its own.
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        transformer.cancel()
                    } else {
                        Handler(Looper.getMainLooper()).post { transformer.cancel() }
                    }
                }
                transformer.start(composition, out.absolutePath)
            }
        } finally {
            poller.cancel()
        }
    }
}

/** Best effort: a pack without a thumbnail is still a valid pack. */
private fun writeThumbnail(video: File, dest: File) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(video.absolutePath)
        val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
        // A little way in: first frames are often black or a fade-in.
        val timeUs = minOf(durationUs / 2, 1_000_000L)
        val frame = retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_MAX, THUMB_MAX)
            ?: retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_MAX, THUMB_MAX)
            ?: return
        dest.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        frame.recycle()
    } catch (e: Exception) {
        Log.w(TAG, "thumbnail failed", e)
        dest.delete()
    } finally {
        retriever.release()
    }
}
