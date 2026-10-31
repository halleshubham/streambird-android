package app.streambird.android.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import app.streambird.android.brand.ThumbnailPlan
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Android half of the thumbnail preparation: decode the picked image (subsampled, EXIF-rotated), then let [ThumbnailPlan]
 * decide the size and quality. Blocking: call from an IO dispatcher.
 */
object ThumbnailPreparer {
    fun prepare(context: Context, uri: Uri, portrait: Boolean): ThumbnailPlan.Encoded {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (cr.openInputStream(uri) ?: throw IOException("Cannot open the chosen image")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("The chosen file is not a readable image")
        val degrees = runCatching {
            cr.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)
        val swap = degrees == 90 || degrees == 270
        val dispW = if (swap) bounds.outHeight else bounds.outWidth
        val dispH = if (swap) bounds.outWidth else bounds.outHeight
        val target = ThumbnailPlan.targetSize(dispW, dispH, portrait) ?: throw IOException("This picture has too narrow a shape for a thumbnail")
        val opts = BitmapFactory.Options().apply { inSampleSize = ThumbnailPlan.sampleSize(dispW, dispH, target) }
        val raw = (cr.openInputStream(uri) ?: throw IOException("Cannot open the chosen image")).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("The chosen file could not be decoded")
        val upright = if (degrees == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        if (upright !== raw) raw.recycle()
        try {
            return ThumbnailPlan.encodeWithinLimit(target) { w, h, q ->
                val scaled = Bitmap.createScaledBitmap(upright, w, h, true)
                // JPEG has no alpha: flatten transparent PNGs onto white.
                val flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
                if (scaled !== upright) scaled.recycle()
                val out = ByteArrayOutputStream()
                flat.compress(Bitmap.CompressFormat.JPEG, q, out)
                flat.recycle()
                out.toByteArray()
            } ?: throw IOException("Could not get the picture under 2 MB")
        } finally {
            upright.recycle()
        }
    }
}
