package app.streambird.android.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** The pure parts of the animated GIF logo: delays, frame selection (subsampling with merged delays), the wall-clock frame index, and the LZW/GIF decoder. */
class GifFramesTest {
    // ---- a tiny GIF writer with a real LZW encoder (so the decoder is tested against something that is not itself) ----

    private class F(val x: Int, val y: Int, val w: Int, val h: Int, val idx: IntArray, val delayCs: Int = 10, val disposal: Int = 0, val transparent: Int = -1, val interlaced: Boolean = false, val local: IntArray? = null)

    private fun lzw(indices: IntArray, minCode: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        var acc = 0; var nbits = 0
        val out = ByteArrayOutputStream()
        fun emit(code: Int, width: Int) { acc = acc or (code shl nbits); nbits += width; while (nbits >= 8) { out.write(acc and 0xff); acc = acc ushr 8; nbits -= 8 } }
        var size = minCode + 1; var next = eoi + 1
        val dict = HashMap<Long, Int>()
        emit(clear, size)
        var cur = indices[0]
        for (k in 1 until indices.size) {
            val p = indices[k]
            val key = (cur.toLong() shl 8) or p.toLong()
            val hit = dict[key]
            if (hit != null) { cur = hit; continue }
            emit(cur, size)
            dict[key] = next++
            if (next - 1 == (1 shl size) && size < 12) size++ // the encoder is one entry ahead of the decoder: widen after adding code 2^size
            if (next == 4096) { emit(clear, size); size = minCode + 1; next = eoi + 1; dict.clear() }
            cur = p
        }
        emit(cur, size)
        emit(eoi, size)
        if (nbits > 0) out.write(acc and 0xff)
        return out.toByteArray()
    }

    private fun interlace(idx: IntArray, w: Int, h: Int): IntArray {
        val order = (0 until h step 8).toList() + (4 until h step 8).toList() + (2 until h step 4).toList() + (1 until h step 2).toList()
        val out = IntArray(idx.size)
        order.forEachIndexed { i, row -> System.arraycopy(idx, row * w, out, i * w, w) }
        return out
    }

    private fun gif(w: Int, h: Int, palette: IntArray, frames: List<F>, minCode: Int = 2): ByteArray {
        val o = ByteArrayOutputStream()
        fun le16(v: Int) { o.write(v and 0xff); o.write(v shr 8) }
        o.write("GIF89a".toByteArray()); le16(w); le16(h)
        val bitsPal = 32 - Integer.numberOfLeadingZeros(maxOf(palette.size, 2) - 1)          // table size 2^bitsPal
        o.write(0x80 or ((bitsPal - 1) and 7)); o.write(0); o.write(0)
        for (i in 0 until (1 shl bitsPal)) { val c = palette.getOrElse(i) { 0 }; o.write((c shr 16) and 0xff); o.write((c shr 8) and 0xff); o.write(c and 0xff) }
        for (f in frames) {
            o.write(0x21); o.write(0xF9); o.write(4); o.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0)); le16(f.delayCs); o.write(maxOf(f.transparent, 0)); o.write(0)
            o.write(0x2C); le16(f.x); le16(f.y); le16(f.w); le16(f.h)
            o.write(if (f.interlaced) 0x40 else 0)
            o.write(minCode)
            val data = lzw(if (f.interlaced) interlace(f.idx, f.w, f.h) else f.idx, minCode)
            var p = 0
            while (p < data.size) { val n = minOf(255, data.size - p); o.write(n); o.write(data, p, n); p += n }
            o.write(0)
        }
        o.write(0x3B)
        return o.toByteArray()
    }

    private val RED = 0xFFFF0000.toInt(); private val GREEN = 0xFF00FF00.toInt(); private val BLUE = 0xFF0000FF.toInt(); private val BLACK = 0xFF000000.toInt()
    private val pal = intArrayOf(RED, GREEN, BLUE, BLACK)

    private fun decode(b: ByteArray, wanted: IntArray): Map<Int, IntArray> {
        val out = LinkedHashMap<Int, IntArray>()
        decodeGif(b, wanted) { i, argb -> out[i] = argb.copyOf() }
        return out
    }

    // ---- delays ----

    @Test fun `delays - 0 and 1 cs mean 100 ms, others are held to at least 20 ms`() {
        assertEquals(100, gifDelayMs(0)); assertEquals(100, gifDelayMs(1))
        assertEquals(20, gifDelayMs(2)); assertEquals(30, gifDelayMs(3)); assertEquals(1000, gifDelayMs(100))
    }

    @Test fun `parse reads size and per-frame delays without decoding`() {
        val g = gif(4, 3, pal, listOf(F(0, 0, 4, 3, IntArray(12), delayCs = 5), F(0, 0, 4, 3, IntArray(12), delayCs = 0), F(0, 0, 4, 3, IntArray(12), delayCs = 200)))
        val t = parseGifTiming(g)!!
        assertEquals(4, t.width); assertEquals(3, t.height); assertEquals(3, t.frameCount)
        assertArrayEquals(intArrayOf(50, 100, 2000), t.delaysMs); assertEquals(2150L, t.durationMs)
        assertNull(parseGifTiming("PNGxxxxxxxxxxxxxxx".toByteArray())); assertNull(parseGifTiming(ByteArray(3)))
        assertNull(parseGifTiming(gif(4, 3, pal, emptyList())))                                        // no frames
    }

    // ---- frame selection ----

    @Test fun `all frames are kept when they fit`() {
        val set = planFrames(intArrayOf(100, 50, 70), 150)!!
        assertArrayEquals(intArrayOf(0, 1, 2), set.sourceIndices); assertArrayEquals(intArrayOf(100, 50, 70), set.delaysMs)
        assertFalse(set.subsampled); assertEquals(3, set.sourceCount)
    }

    @Test fun `a long GIF is subsampled to evenly spaced frames and the delays are merged so the loop keeps its length`() {
        val delays = IntArray(300) { 20 + it % 7 * 10 }
        val set = planFrames(delays, 150)!!
        assertEquals(150, set.size); assertTrue(set.subsampled)
        // evenly spaced: every second frame
        assertEquals(0, set.sourceIndices[0]); assertEquals(2, set.sourceIndices[1]); assertEquals(298, set.sourceIndices[149])
        // merged: each kept frame shows for its own delay plus the skipped one
        assertEquals(delays[0] + delays[1], set.delaysMs[0]); assertEquals(delays[298] + delays[299], set.delaysMs[149])
        assertEquals(delays.sumOf { it.toLong() }, set.durationMs)                                      // loop length preserved
    }

    @Test fun `subsampling keeps total time exactly for uneven ratios and strictly ascending indices`() {
        for (n in listOf(151, 233, 500, 1000, 4097)) for (allowed in listOf(2, 3, 7, 50, 150)) {
            if (allowed >= n) continue
            val d = IntArray(n) { 20 + (it * 37) % 400 }
            val s = planFrames(d, allowed)!!
            assertEquals("$n -> $allowed", allowed, s.size)
            assertEquals(d.sumOf { it.toLong() }, s.durationMs)
            for (i in 1 until s.size) assertTrue(s.sourceIndices[i] > s.sourceIndices[i - 1])
            assertEquals(0, s.sourceIndices[0]); assertTrue(s.sourceIndices.last() < n)
            // spacing differs by at most one source frame
            val gaps = (1 until s.size).map { s.sourceIndices[it] - s.sourceIndices[it - 1] }
            assertTrue(gaps.max() - gaps.min() <= 1)
        }
    }

    @Test fun `fewer than two usable frames means a still`() {
        assertNull(planFrames(intArrayOf(100), 150)); assertNull(planFrames(IntArray(0), 150))
        assertNull(planFrames(IntArray(10) { 100 }, 1)); assertNull(planFrames(IntArray(10) { 100 }, 0))
        assertEquals(2, planFrames(IntArray(10) { 100 }, 2)!!.size)
    }

    @Test fun `caps - frames and memory - match the animated ads`() {
        assertEquals(150, maxFramesFor(10, 10))
        assertEquals(64, maxFramesFor(1024, 384))                      // 96 MB / (1024 * 384 * 4)
        assertEquals(24, maxFramesFor(1024, 1024))                     // 96 MB / 4 MB
        assertEquals(0, maxFramesFor(8192, 8192, maxBytes = 96L * 1024 * 1024 / 4))   // not even one frame fits
        assertEquals(10, maxFramesFor(10, 10, maxFrames = 10))
    }

    // ---- the whole plan for a logo ----

    private fun timing(w: Int, h: Int, frames: Int, delay: Int = 100) = GifTiming(w, h, IntArray(frames) { delay })

    @Test fun `logo frame height follows the logo size like the web, never above the GIF's own`() {
        assertEquals(256, logoFrameHeight(90, 1000)); assertEquals(256, logoFrameHeight(16, 1000))
        assertEquals(400, logoFrameHeight(200, 1000)); assertEquals(1200, logoFrameHeight(600, 5000))
        assertEquals(100, logoFrameHeight(90, 100))                                                  // small GIF: not enlarged
        assertEquals(256, logoFrameHeight(5, 1000))                                                  // the size is clamped to 16..600
    }

    @Test fun `a short GIF logo animates with all its frames at the web decode size`() {
        val p = planGifLogo(timing(600, 600, 30), 90) as GifLogoPlan.Animate
        assertEquals(256, p.targetH); assertEquals(256, p.targetW); assertEquals(30, p.frames.size); assertFalse(p.frames.subsampled)
    }

    @Test fun `a long GIF logo is subsampled instead of dropped`() {
        val p = planGifLogo(timing(600, 600, 400), 90) as GifLogoPlan.Animate           // 256x256, frame cap 150 binds
        assertEquals(150, p.frames.size); assertTrue(p.frames.subsampled); assertEquals(400, p.frames.sourceCount)
        assertEquals(40_000L, p.frames.durationMs)
        // a big logo size makes frames big: the memory cap binds first (600 px logo -> 1200 px frames, 1200x1200x4 = 5.76 MB -> 17 frames)
        val big = planGifLogo(timing(2000, 2000, 400), 600) as GifLogoPlan.Animate
        assertEquals(1200, big.targetH); assertEquals(17, big.frames.size); assertEquals(40_000L, big.frames.durationMs)
    }

    @Test fun `only when even the reduced set does not fit does it stay a still, with a reason`() {
        val tight = planGifLogo(timing(2000, 2000, 400), 600, maxBytes = 1200L * 1200 * 4)   // room for a single frame
        assertTrue(tight is GifLogoPlan.Still); assertTrue((tight as GifLogoPlan.Still).reason!!.contains("too large"))
        assertEquals(GifLogoPlan.Still(null), planGifLogo(timing(100, 100, 1), 90))          // one frame: nothing to report
        assertEquals(GifLogoPlan.Still("the GIF could not be read"), planGifLogo(null, 90))
    }

    @Test fun `editor and live sentences`() {
        assertEquals("Animated GIF: it animates on the stream.", describeGifLogo(planGifLogo(timing(100, 100, 5), 90)))
        assertEquals(
            "Animated GIF: it animates on the stream. It is long, so 150 of its 400 frames are used (same speed).",
            describeGifLogo(planGifLogo(timing(600, 600, 400), 90)),
        )
        assertEquals("This GIF cannot animate on the stream (the GIF could not be read). The stream shows its first frame.", describeGifLogo(GifLogoPlan.Still("the GIF could not be read")))
        assertNull(describeGifLogo(GifLogoPlan.Still(null)))
    }

    // ---- the wall clock ----

    @Test fun `frame index follows the clock and loops`() {
        val ends = cumulativeEnds(intArrayOf(100, 200, 100))                                          // 100, 300, 400
        assertArrayEquals(longArrayOf(100, 300, 400), ends)
        assertEquals(0, frameIndexAt(ends, 0)); assertEquals(0, frameIndexAt(ends, 99)); assertEquals(1, frameIndexAt(ends, 100))
        assertEquals(1, frameIndexAt(ends, 299)); assertEquals(2, frameIndexAt(ends, 300)); assertEquals(2, frameIndexAt(ends, 399))
        assertEquals(0, frameIndexAt(ends, 400)); assertEquals(1, frameIndexAt(ends, 400 * 7 + 150))   // loops forever
        assertEquals(2, frameIndexAt(ends, -50))                                                      // a clock before the start wraps instead of throwing
        assertEquals(0, frameIndexAt(LongArray(0), 5)); assertEquals(0, frameIndexAt(longArrayOf(0), 5))
    }

    // ---- the decoder ----

    @Test fun `the well-known 1x1 transparent GIF decodes to one transparent pixel`() {
        val b = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val t = parseGifTiming(b)!!
        assertEquals(1, t.width); assertEquals(1, t.frameCount)
        val f = decode(b, intArrayOf(0))
        assertEquals(1, f.size); assertEquals(0, f[0]!![0])                                            // transparent
    }

    @Test fun `plain frames decode to their colours`() {
        val idx = IntArray(12) { it % 4 }                                                             // 4 x 3: 0 1 2 3 / 0 1 2 3 / ...
        val frames = decode(gif(4, 3, pal, listOf(F(0, 0, 4, 3, idx))), intArrayOf(0))
        assertArrayEquals(IntArray(12) { pal[it % 4] }, frames[0])
    }

    @Test fun `LZW with long noisy data - code width growth and table resets - round-trips`() {
        val rnd = java.util.Random(7)
        for (minCode in listOf(2, 4, 8)) {
            val colours = 1 shl minCode
            val w = 160; val h = 130
            val idx = IntArray(w * h) { if (it % 50 < 25) rnd.nextInt(colours) else (it / 3) % colours }   // noise + runs
            val palette = IntArray(colours) { 0xFF000000.toInt() or (it * 7 shl 16) or (it * 3 shl 8) or it }
            val f = decode(gif(w, h, palette, listOf(F(0, 0, w, h, idx)), minCode), intArrayOf(0))[0]!!
            assertArrayEquals("min code $minCode", IntArray(w * h) { palette[idx[it]] }, f)
        }
    }

    @Test fun `interlaced frames are put back in row order`() {
        val w = 3; val h = 10
        val idx = IntArray(w * h) { (it / w) % 4 }                                                    // row r has colour r % 4
        val f = decode(gif(w, h, pal, listOf(F(0, 0, w, h, idx, interlaced = true))), intArrayOf(0))[0]!!
        assertArrayEquals(IntArray(w * h) { pal[(it / w) % 4] }, f)
    }

    @Test fun `a sub-rectangle frame with a transparent colour leaves the picture underneath, and only wanted frames are emitted`() {
        val full = F(0, 0, 4, 4, IntArray(16) { 0 }, delayCs = 5)                                     // all red
        val patch = F(1, 1, 2, 2, intArrayOf(1, 3, 2, 1), delayCs = 5, transparent = 3)              // green, (transparent), blue, green
        val frames = decode(gif(4, 4, pal, listOf(full, patch, full)), intArrayOf(1))
        assertEquals(setOf(1), frames.keys)
        val c = frames[1]!!
        assertEquals(GREEN, c[1 * 4 + 1]); assertEquals(RED, c[1 * 4 + 2])                            // transparent patch pixel keeps the red below
        assertEquals(BLUE, c[2 * 4 + 1]); assertEquals(GREEN, c[2 * 4 + 2]); assertEquals(RED, c[0])
    }

    @Test fun `disposal - restore to background clears the frame's rectangle, restore previous brings the old picture back`() {
        val base = F(0, 0, 4, 4, IntArray(16) { 0 }, disposal = 0)                                    // red, kept
        val a = F(0, 0, 2, 2, IntArray(4) { 1 }, disposal = 2)                                        // green block, then cleared to transparent
        val b = F(2, 2, 2, 2, IntArray(4) { 2 }, disposal = 3)                                        // blue block, then the canvas is restored
        val after = F(0, 0, 1, 1, intArrayOf(3), disposal = 0)                                        // black pixel
        val fr = decode(gif(4, 4, pal, listOf(base, a, b, after)), intArrayOf(0, 1, 2, 3))
        assertEquals(GREEN, fr[1]!![0]); assertEquals(RED, fr[1]!![2 * 4 + 2])
        // frame 2: the green block was disposed to background (transparent), red elsewhere, blue block drawn
        assertEquals(0, fr[2]!![0]); assertEquals(BLUE, fr[2]!![2 * 4 + 2]); assertEquals(RED, fr[2]!![3])
        // frame 3: the blue block was "restore previous" -> back to the state before it (transparent hole, red elsewhere)
        assertEquals(BLACK, fr[3]!![0]); assertEquals(RED, fr[3]!![2 * 4 + 2]); assertEquals(0, fr[3]!![1])
    }

    @Test fun `frames of the loop in order and the walk stops after the last wanted frame`() {
        val frames = (0 until 5).map { F(0, 0, 2, 2, IntArray(4) { _ -> it % 4 }, delayCs = 10) }
        val b = gif(2, 2, pal, frames)
        var walked = decodeGif(b, intArrayOf(0, 2)) { _, _ -> }
        assertEquals(3, walked)
        walked = decodeGif(b, intArrayOf(0, 1, 2, 3, 4)) { _, _ -> }
        assertEquals(5, walked)
    }

    @Test fun `a damaged GIF fails cleanly`() {
        val ok = gif(4, 3, pal, listOf(F(0, 0, 4, 3, IntArray(12))))
        assertNotNull(runCatching { decode(ok, intArrayOf(0)) }.getOrNull())
        assertTrue(runCatching { decodeGif("GIF89a".toByteArray(), intArrayOf(0)) { _, _ -> } }.isFailure)
        assertTrue(runCatching { decodeGif(ByteArray(40), intArrayOf(0)) { _, _ -> } }.isFailure)
        // truncated in the middle of the colour table / image data: either an exception or fewer frames, never a hang or a wrong frame count
        val cut = ok.copyOf(ok.size - 12)
        assertTrue(runCatching { decodeGif(cut, intArrayOf(0)) { _, _ -> } }.let { it.isFailure || it.getOrNull()!! <= 1 })
    }
}
