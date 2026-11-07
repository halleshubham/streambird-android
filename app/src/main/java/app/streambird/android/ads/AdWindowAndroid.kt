package app.streambird.android.ads

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Android glue for [detectWindow]: turns a decoded Bitmap (or raw picture bytes; an animated GIF contributes its first frame, which is what
 * BitmapFactory returns) into pixels, scaled so the long side is at most [WindowRules.MAX_DETECT_SIDE]. Bounded work: at most 1280 x 1280 pixels
 * are copied and looked at. UNVERIFIED on real JPEGs (the algorithm itself is JVM-tested on synthetic pixels).
 */
fun windowOfBitmap(bmp: Bitmap): NormRect? {
    if (bmp.width < 8 || bmp.height < 8) return null
    val long = maxOf(bmp.width, bmp.height)
    val scaled = if (long > WindowRules.MAX_DETECT_SIDE) {
        val k = WindowRules.MAX_DETECT_SIDE.toFloat() / long
        Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
    } else bmp
    return try {
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        detectWindow(PixelImage(scaled.width, scaled.height, px))
    } finally {
        if (scaled !== bmp) scaled.recycle()
    }
}

/** Decodes [bytes] (sub-sampled so the long side stays near the detection size) and finds the window. Null = no usable window; throws when unreadable. */
fun windowOfBytes(bytes: ByteArray): NormRect? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "the picture could not be read" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > WindowRules.MAX_DETECT_SIDE) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("the picture could not be decoded")
    return try { windowOfBitmap(bmp) } finally { bmp.recycle() }
}
