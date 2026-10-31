package app.streambird.android.brand

/**
 * Sizing and compression decisions for the optional stream thumbnail (pure Kotlin, unit-tested).
 * Backend limits (src/streams/thumbnail.util.ts parseThumbnail): JPEG or PNG, at most 2 MB, at least 640 px wide.
 */
object ThumbnailPlan {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MIN_WIDTH = 640
    /** Keep a little under the cap so multipart framing or a rounding difference never tips it over. */
    const val BYTE_BUDGET = MAX_BYTES - 16 * 1024
    private const val MAX_SIDE = 4096
    val QUALITIES = listOf(90, 82, 74, 66, 58, 50, 42)

    data class Size(val width: Int, val height: Int)

    /** The box the picture has to fit: 1280x720 for a landscape stream, 720x1280 for a vertical one. */
    fun box(portrait: Boolean) = if (portrait) Size(720, 1280) else Size(1280, 720)

    /**
     * Fit inside [box] keeping the aspect (never enlarged), but never narrower than [MIN_WIDTH]: a tall photo for a landscape
     * stream would otherwise be refused by the backend, so it is kept at 640 wide instead. null = unusable shape
     * (taller than 1:6.4 after that rule, or empty).
     */
    fun targetSize(srcW: Int, srcH: Int, portrait: Boolean): Size? {
        if (srcW <= 0 || srcH <= 0) return null
        val b = box(portrait)
        val scale = minOf(b.width.toDouble() / srcW, b.height.toDouble() / srcH, 1.0)
        var w = Math.round(srcW * scale).toInt().coerceAtLeast(1)
        var h = Math.round(srcH * scale).toInt().coerceAtLeast(1)
        if (w < MIN_WIDTH) {
            val up = MIN_WIDTH.toDouble() / srcW
            w = MIN_WIDTH
            h = Math.round(srcH * up).toInt()
        }
        return if (h > MAX_SIDE) null else Size(w, h)
    }

    /** Largest power-of-two subsampling that still decodes at least [target] pixels (BitmapFactory.inSampleSize). */
    fun sampleSize(srcW: Int, srcH: Int, target: Size): Int {
        var s = 1
        while (srcW / (s * 2) >= target.width && srcH / (s * 2) >= target.height) s *= 2
        return s
    }

    data class Encoded(val bytes: ByteArray, val width: Int, val height: Int, val quality: Int)

    /**
     * Finds a JPEG under [BYTE_BUDGET]: first lowers quality at [start] size, then shrinks the picture by 20 % per round
     * (never below [MIN_WIDTH] wide). [encode] does the actual bitmap work (width, height, quality -> JPEG bytes).
     * Returns null when even the smallest allowed version is too big.
     */
    fun encodeWithinLimit(start: Size, encode: (width: Int, height: Int, quality: Int) -> ByteArray): Encoded? {
        var w = start.width
        var h = start.height
        while (true) {
            for (q in QUALITIES) {
                val out = encode(w, h, q)
                if (out.size <= BYTE_BUDGET) return Encoded(out, w, h, q)
            }
            val nw = (w * 0.8).toInt()
            if (nw < MIN_WIDTH) return null
            h = Math.round(h * (nw.toDouble() / w)).toInt()
            w = nw
        }
    }
}
