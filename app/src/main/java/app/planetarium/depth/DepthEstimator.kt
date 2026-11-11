package app.planetarium.depth

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Monocular depth estimation with Depth Anything V2 Small (ONNX), run on the CPU.
 * Blocking and memory-hungry: call from a background thread, one at a time.
 */
object DepthEstimator {
    private const val ASSET = "models/depth_anything_v2_small.onnx"
    /** Longest side fed to the model. The network works on 14 px patches. */
    private const val MODEL_SIZE = 518
    private const val PATCH = 14
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    class ModelMissingException : Exception("depth model is not bundled in this build")

    /**
     * Returns a greyscale depth map of [photo] (white = near, black = far), at the model's
     * resolution and with the photo's aspect ratio.
     */
    fun estimate(context: Context, photo: Bitmap): Bitmap {
        val (width, height) = modelSize(photo.width, photo.height)
        val scaled = Bitmap.createScaledBitmap(photo, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== photo) scaled.recycle()

        // NCHW, RGB, ImageNet-normalised.
        val plane = width * height
        val input = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in 0 until plane) {
                input.put(c * plane + i, (((pixels[i] shr shift) and 0xFF) / 255f - MEAN[c]) / STD[c])
            }
        }

        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, max(1, Runtime.getRuntime().availableProcessors())))
        }
        return options.use {
            env.createSession(modelFile(context).path, options).use { session ->
                val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
                OnnxTensor.createTensor(env, input, shape).use { tensor ->
                    session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        val output = result[0] as OnnxTensor
                        val outShape = output.info.shape
                        val outHeight = outShape[outShape.size - 2].toInt()
                        val outWidth = outShape[outShape.size - 1].toInt()
                        val depth = FloatArray(outWidth * outHeight)
                        output.floatBuffer.get(depth)
                        toBitmap(smooth(depth, outWidth, outHeight), outWidth, outHeight)
                    }
                }
            }
        }
    }

    /** Scales so the longest side is [MODEL_SIZE], both sides multiples of the patch size. */
    private fun modelSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = MODEL_SIZE.toFloat() / max(width, height)
        fun snap(v: Int) = max(PATCH, (v * scale / PATCH).roundToInt() * PATCH)
        return snap(width) to snap(height)
    }

    /**
     * A small box blur. Parallax displaces pixels by depth, so a razor-sharp depth edge tears the
     * picture along object outlines; softening it turns the tear into a short stretch.
     */
    private fun smooth(src: FloatArray, width: Int, height: Int): FloatArray {
        val radius = 2
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (d in -radius..radius) sum += src[y * width + (x + d).coerceIn(0, width - 1)]
                tmp[y * width + x] = sum / (2 * radius + 1)
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (d in -radius..radius) sum += tmp[(y + d).coerceIn(0, height - 1) * width + x]
                out[y * width + x] = sum / (2 * radius + 1)
            }
        }
        return out
    }

    /** The model outputs relative inverse depth (larger = nearer); stretch it to the full 0..255 range. */
    private fun toBitmap(depth: FloatArray, width: Int, height: Int): Bitmap {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in depth) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        val range = if (hi - lo > 1e-6f) hi - lo else 1f
        val pixels = IntArray(depth.size) { i ->
            val g = ((depth[i] - lo) / range * 255f).roundToInt().coerceIn(0, 255)
            Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** ONNX Runtime loads from a file path, so the model is copied out of the APK once. */
    private fun modelFile(context: Context): File {
        val file = File(context.noBackupFilesDir, "depth_anything_v2_small.onnx")
        val assets = context.assets
        val length = try {
            assets.openFd(ASSET).use { it.length }
        } catch (e: FileNotFoundException) {
            throw ModelMissingException()
        }
        if (file.length() != length) {
            val tmp = File(file.path + ".tmp")
            assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(file)) { "could not install the depth model" }
        }
        return file
    }
}
