package app.planetarium.engine.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

object BitmapLoader {
    /**
     * Decodes an image so its longest side is at most [maxSize], upright per EXIF. Normally it is
     * only halved until it fits, which is cheap and never blurs more than needed. With [exact] it
     * is then scaled to [maxSize] itself, for a picture whose size on screen is known: halving
     * alone would leave it up to twice as large as that, or needlessly soft.
     */
    fun load(file: File, maxSize: Int, exact: Boolean = false): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image: ${file.name}" }

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        // Exact: stop halving while the result is still at least the wanted size, then scale down.
        while (if (exact) longest / (sample * 2) >= maxSize else longest / sample > maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, opts)) { "decode failed: ${file.name}" }
        val decoded = maxOf(bitmap.width, bitmap.height)
        if (exact && decoded > maxSize) {
            val k = maxSize.toFloat() / decoded
            val scaled = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * k).toInt().coerceAtLeast(1), (bitmap.height * k).toInt().coerceAtLeast(1), true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }

        val degrees = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
