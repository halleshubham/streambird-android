package app.streambird.android.media

import app.streambird.android.ads.MAX_GIF_BYTES
import app.streambird.android.ads.MAX_GIF_FRAMES
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Animated GIF logo on the broadcast: the pure parts (JVM-tested). The Android glue (bitmaps, textures) is in [LogoAnimation].
 *
 * Why not AnimatedImageDrawable like the ads: it plays at its own pace and cannot drop frames, so a long GIF would have to be given up.
 * Here every frame is decoded once ([decodeGif]), only an evenly spaced selection is kept ([planFrames]; the delays of the skipped frames are merged
 * into the kept ones, so the loop keeps its length) and the frame shown comes from the wall clock ([frameIndexAt]), exactly like the web studio's
 * animatedImage.ts. Same safety caps as the animated ads: at most [MAX_GIF_FRAMES] frames and [MAX_GIF_BYTES] of decoded pixels in total.
 * Only when even the reduced set cannot fit (fewer than two frames would be left) the logo stays a still picture.
 */

/** GIF delays of 0/1 cs are really "as fast as you can": browsers (and the web studio) show 100 ms; anything else is held to >= 20 ms. */
const val GIF_DEFAULT_DELAY_MS = 100
const val GIF_MIN_DELAY_MS = 20

/** A GIF frame delay in hundredths of a second -> playable milliseconds (web animatedImage.ts frameDelayMs). */
fun gifDelayMs(centiseconds: Int): Int = if (centiseconds <= 1) GIF_DEFAULT_DELAY_MS else max(GIF_MIN_DELAY_MS, centiseconds * 10)

/** Size and per-frame delays (ms, already normalised) of a GIF, read without decoding any pixels. */
class GifTiming(val width: Int, val height: Int, val delaysMs: IntArray) {
    val frameCount get() = delaysMs.size
    val durationMs get() = delaysMs.sumOf { it.toLong() }
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xff
private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)

/** Walks the GIF blocks and returns size + delays, or null when it is not a readable GIF with at least one frame. */
fun parseGifTiming(b: ByteArray): GifTiming? {
    if (b.size < 13 || String(b, 0, 3, Charsets.ISO_8859_1) != "GIF") return null
    val w = b.le16(6); val h = b.le16(8)
    if (w <= 0 || h <= 0) return null
    val packed = b.u8(10)
    var i = 13 + if (packed and 0x80 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
    val delays = ArrayList<Int>()
    var pending = 0
    while (i < b.size) {
        when (b.u8(i)) {
            0x3B -> break
            0x21 -> {
                if (i + 1 >= b.size) break
                val label = b.u8(i + 1)
                if (label == 0xF9 && i + 5 < b.size) pending = b.le16(i + 4)   // graphic control: block size 4, flags, delay (cs), transparent index
                i += 2
                while (i < b.size && b.u8(i) != 0) i += b.u8(i) + 1
                i++
            }
            0x2C -> {
                if (i + 10 > b.size) break
                val lp = b.u8(i + 9)
                i += 10 + if (lp and 0x80 != 0) 3 * (1 shl ((lp and 7) + 1)) else 0
                i++ // LZW minimum code size
                while (i < b.size && b.u8(i) != 0) i += b.u8(i) + 1
                i++
                delays.add(gifDelayMs(pending)); pending = 0
            }
            else -> break
        }
    }
    return if (delays.isEmpty()) null else GifTiming(w, h, delays.toIntArray())
}

/** The frames kept for playback: [sourceIndices] (ascending, evenly spaced) and the [delaysMs] each is shown for (merged, summing to the full loop). */
class FrameSet(val sourceIndices: IntArray, val delaysMs: IntArray, val sourceCount: Int) {
    val size get() = sourceIndices.size
    val subsampled get() = sourceIndices.size < sourceCount
    val durationMs get() = delaysMs.sumOf { it.toLong() }
}

/** How many frames of [w] x [h] fit the caps: min(frame cap, memory cap). */
fun maxFramesFor(w: Int, h: Int, maxFrames: Int = MAX_GIF_FRAMES, maxBytes: Long = MAX_GIF_BYTES): Int {
    val per = max(1L, w.toLong() * h * 4)
    return min(maxFrames.toLong(), maxBytes / per).toInt()
}

/**
 * Chooses which frames to keep. All of them when they fit [allowed]; otherwise [allowed] evenly spaced ones (index j*n/allowed) where each kept
 * frame is shown for the sum of the delays from itself up to the next kept one, so the total loop time is unchanged. null = fewer than two
 * frames fit (or the GIF has fewer than two): keep the still picture.
 */
fun planFrames(delaysMs: IntArray, allowed: Int): FrameSet? {
    val n = delaysMs.size
    if (n < 2 || allowed < 2) return null
    if (n <= allowed) return FrameSet(IntArray(n) { it }, delaysMs.copyOf(), n)
    val idx = IntArray(allowed) { j -> ((j.toLong() * n) / allowed).toInt() }
    val merged = IntArray(allowed) { j ->
        val from = idx[j]
        val to = if (j + 1 < allowed) idx[j + 1] else n
        var sum = 0L
        for (k in from until to) sum += delaysMs[k]
        min(sum, Int.MAX_VALUE.toLong()).toInt()
    }
    return FrameSet(idx, merged, n)
}

/** Index of the frame showing [elapsedMs] after the start (looping), given each kept frame's cumulative end time. */
fun frameIndexAt(ends: LongArray, elapsedMs: Long): Int {
    if (ends.isEmpty()) return 0
    val total = ends[ends.size - 1]
    if (total <= 0) return 0
    val t = ((elapsedMs % total) + total) % total
    var lo = 0; var hi = ends.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (ends[mid] > t) hi = mid else lo = mid + 1
    }
    return lo
}

fun cumulativeEnds(delaysMs: IntArray): LongArray {
    var acc = 0L
    return LongArray(delaysMs.size) { acc += delaysMs[it]; acc }
}

/** What the broadcast does with a GIF logo. */
sealed interface GifLogoPlan {
    /** Plays [frames] (all or a subsample) at [targetW] x [targetH]. */
    data class Animate(val frames: FrameSet, val targetW: Int, val targetH: Int) : GifLogoPlan
    /** Stays a still picture (the first frame). [reason] null = nothing to say (a one-frame GIF). */
    data class Still(val reason: String?) : GifLogoPlan
}

/** Frame height used for a logo drawn [logoSizePx] tall: like the web (<= max(256, 2 x logo size)), never larger than the GIF itself. */
fun logoFrameHeight(logoSizePx: Int, gifHeight: Int): Int = max(1, min(gifHeight, max(256, logoSizePx.coerceIn(16, 600) * 2)))

/** Plan for a GIF logo drawn [logoSizePx] tall: decode size, then the largest evenly spaced subset that fits the caps. */
fun planGifLogo(t: GifTiming?, logoSizePx: Int, maxFrames: Int = MAX_GIF_FRAMES, maxBytes: Long = MAX_GIF_BYTES): GifLogoPlan {
    if (t == null) return GifLogoPlan.Still("the GIF could not be read")
    if (t.frameCount < 2) return GifLogoPlan.Still(null)
    val th = logoFrameHeight(logoSizePx, t.height)
    val tw = max(1, (t.width.toDouble() * th / t.height).roundToInt())
    val set = planFrames(t.delaysMs, maxFramesFor(tw, th, maxFrames, maxBytes))
        ?: return GifLogoPlan.Still("it is too large to animate on the stream even with fewer frames")
    return GifLogoPlan.Animate(set, tw, th)
}

/** The editor / Live-screen sentence for a plan; null = nothing to say (a still, one-frame GIF). */
fun describeGifLogo(p: GifLogoPlan): String? = when (p) {
    is GifLogoPlan.Animate ->
        if (p.frames.subsampled) "Animated GIF: it animates on the stream. It is long, so ${p.frames.size} of its ${p.frames.sourceCount} frames are used (same speed)."
        else "Animated GIF: it animates on the stream."
    is GifLogoPlan.Still -> p.reason?.let { "This GIF cannot animate on the stream ($it). The stream shows its first frame." }
}

// ---- decoding ----

/**
 * Decodes the GIF in [b] frame by frame (full logical-screen size, disposal methods honoured) and calls [emit] with the composed ARGB pixels of
 * each frame whose index is in [wanted] (ascending). The array is reused between calls: copy it inside [emit]. Frames that are not wanted are still
 * composed (later frames build on them) but cost no memory. Returns the number of frames walked; throws [IllegalArgumentException] on a damaged file.
 */
fun decodeGif(b: ByteArray, wanted: IntArray, emit: (index: Int, argb: IntArray) -> Unit): Int {
    require(b.size >= 13 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF") { "not a GIF" }
    val sw = b.le16(6); val sh = b.le16(8)
    require(sw > 0 && sh > 0 && sw.toLong() * sh <= 64L * 1024 * 1024) { "bad GIF size" }
    val packed = b.u8(10)
    var i = 13
    var globalTable: IntArray? = null
    if (packed and 0x80 != 0) {
        val n = 1 shl ((packed and 7) + 1)
        require(i + 3 * n <= b.size) { "truncated GIF" }
        globalTable = IntArray(n) { 0xFF000000.toInt() or (b.u8(i + 3 * it) shl 16) or (b.u8(i + 3 * it + 1) shl 8) or b.u8(i + 3 * it + 2) }
        i += 3 * n
    }
    val canvas = IntArray(sw * sh)
    var restore: IntArray? = null
    var frame = 0
    var wi = 0
    var disposal = 0; var transparent = -1
    while (i < b.size) {
        when (b.u8(i)) {
            0x3B -> return frame
            0x21 -> {
                require(i + 1 < b.size) { "truncated GIF" }
                if (b.u8(i + 1) == 0xF9 && i + 7 <= b.size) {
                    val flags = b.u8(i + 3)
                    disposal = (flags shr 2) and 7
                    transparent = if (flags and 1 != 0) b.u8(i + 6) else -1
                }
                i += 2
                while (i < b.size && b.u8(i) != 0) i += b.u8(i) + 1
                i++
            }
            0x2C -> {
                require(i + 10 <= b.size) { "truncated GIF" }
                val fx = b.le16(i + 1); val fy = b.le16(i + 3); val fw = b.le16(i + 5); val fh = b.le16(i + 7)
                val lp = b.u8(i + 9)
                i += 10
                var table = globalTable
                if (lp and 0x80 != 0) {
                    val n = 1 shl ((lp and 7) + 1)
                    require(i + 3 * n <= b.size) { "truncated GIF" }
                    val base = i
                    table = IntArray(n) { 0xFF000000.toInt() or (b.u8(base + 3 * it) shl 16) or (b.u8(base + 3 * it + 1) shl 8) or b.u8(base + 3 * it + 2) }
                    i += 3 * n
                }
                val interlaced = lp and 0x40 != 0
                require(i < b.size) { "truncated GIF" }
                val minCode = b.u8(i++)
                require(minCode in 1..11) { "bad LZW size" }
                // gather the data sub-blocks
                var dataStart = i; var total = 0
                while (i < b.size && b.u8(i) != 0) { total += b.u8(i); i += b.u8(i) + 1 }
                val data = ByteArray(total)
                var o = 0; var q = dataStart
                while (q < b.size && b.u8(q) != 0) { val len = b.u8(q); System.arraycopy(b, q + 1, data, o, min(len, b.size - q - 1)); o += len; q += len + 1 }
                i++ // block terminator

                if (disposal == 3) restore = canvas.copyOf()
                if (fw > 0 && fh > 0) {
                    val idx = lzwDecode(data, minCode, fw * fh)
                    val pal = table ?: IntArray(0)
                    for (row in 0 until fh) {
                        val srcRow = if (interlaced) interlacedRow(row, fh) else row
                        val y = fy + srcRow
                        if (y < 0 || y >= sh) continue
                        for (col in 0 until fw) {
                            val x = fx + col
                            if (x < 0 || x >= sw) continue
                            val c = idx[row * fw + col].toInt() and 0xff
                            if (c == transparent || c >= pal.size) continue
                            canvas[y * sw + x] = pal[c]
                        }
                    }
                }
                if (wi < wanted.size && wanted[wi] == frame) { emit(frame, canvas); wi++ }
                // dispose AFTER the frame was shown
                when (disposal) {
                    2 -> for (row in 0 until fh) {
                        val y = fy + row
                        if (y < 0 || y >= sh) continue
                        for (col in 0 until fw) { val x = fx + col; if (x in 0 until sw) canvas[y * sw + x] = 0 }
                    }
                    3 -> restore?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
                }
                frame++; disposal = 0; transparent = -1
                if (wi >= wanted.size) return frame
            }
            else -> return frame
        }
    }
    return frame
}

/** Row [r] of an interlaced image of height [h] is stored at output row ...: GIF passes 0,4,2,1 with steps 8,8,4,2. */
internal fun interlacedRow(r: Int, h: Int): Int {
    val p1 = (h + 7) / 8; val p2 = (h + 3) / 8; val p3 = (h + 1) / 4
    return when {
        r < p1 -> r * 8
        r < p1 + p2 -> (r - p1) * 8 + 4
        r < p1 + p2 + p3 -> (r - p1 - p2) * 4 + 2
        else -> (r - p1 - p2 - p3) * 2 + 1
    }
}

/** GIF LZW: variable code width starting at minCode + 1, clear / end codes, 12-bit table. Returns [pixels] palette indices (missing data = 0). */
internal fun lzwDecode(data: ByteArray, minCode: Int, pixels: Int): ByteArray {
    val out = ByteArray(pixels)
    val clear = 1 shl minCode; val eoi = clear + 1
    val prefix = ShortArray(4096); val suffix = ByteArray(4096); val stack = ByteArray(4097)
    for (c in 0 until clear) suffix[c] = c.toByte()
    var size = minCode + 1; var next = eoi + 1; var old = -1; var first = 0
    var bits = 0; var acc = 0; var pos = 0; var n = 0
    while (n < pixels) {
        while (bits < size) {
            if (pos >= data.size) return out
            acc = acc or ((data[pos++].toInt() and 0xff) shl bits); bits += 8
        }
        var code = acc and ((1 shl size) - 1)
        acc = acc ushr size; bits -= size
        if (code == clear) { size = minCode + 1; next = eoi + 1; old = -1; continue }
        if (code == eoi) break
        if (old == -1) {
            if (code >= clear) return out
            out[n++] = suffix[code]; old = code; first = code; continue
        }
        val inCode = code
        var sp = 0
        if (code >= next) {
            if (code > next) return out // damaged
            stack[sp++] = first.toByte(); code = old
        }
        while (code >= clear) { stack[sp++] = suffix[code]; code = prefix[code].toInt() }
        first = suffix[code].toInt() and 0xff
        stack[sp++] = first.toByte()
        if (next < 4096) {
            prefix[next] = old.toShort(); suffix[next] = first.toByte(); next++
            if (next == (1 shl size) && size < 12) size++
        }
        old = inCode
        while (sp > 0 && n < pixels) out[n++] = stack[--sp]
    }
    return out
}
