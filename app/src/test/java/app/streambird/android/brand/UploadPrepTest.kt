package app.streambird.android.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** The 8 MB per-file cap of brand images (was 4 MB), the unchanged 2 MB thumbnail cap, and checking a file without loading it. */
class UploadPrepTest {
    private val mb = 1024 * 1024

    private fun png(w: Int, h: Int, size: Int): ByteArray {
        val b = ByteArray(size)
        val head = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte())
        head.copyInto(b)
        fun be32(at: Int, v: Int) { b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte(); b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte() }
        be32(16, w); be32(20, h)
        return b
    }

    /** A JPEG whose size marker (SOF0) comes after [metaKb] KB of APPn metadata. */
    private fun jpeg(w: Int, h: Int, metaKb: Int, total: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0xff); out.write(0xd8)
        var left = metaKb * 1024
        while (left > 0) {
            val seg = minOf(65533, left)
            out.write(0xff); out.write(0xe1); out.write((seg + 2) ushr 8); out.write((seg + 2) and 0xff); out.write(ByteArray(seg)); left -= seg
        }
        out.write(0xff); out.write(0xc0); out.write(0); out.write(17); out.write(8)
        out.write(h ushr 8); out.write(h and 0xff); out.write(w ushr 8); out.write(w and 0xff)
        out.write(ByteArray(10))
        val b = out.toByteArray()
        return if (b.size >= total) b else b.copyOf(total)
    }

    private class Counting(private val inner: InputStream) : InputStream() {
        var total = 0L; var maxChunk = 0
        override fun read(): Int = inner.read().also { if (it >= 0) total++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) total += it; maxChunk = maxOf(maxChunk, len) }
    }

    // ---- boundaries ----

    @Test fun `limits are 8 MB per brand image and 2 MB per thumbnail`() {
        assertEquals(8 * mb, MAX_ASSET_BYTES); assertEquals(8L * mb, maxUploadBytes("logo")); assertEquals(8L * mb, maxUploadBytes("ad")); assertEquals(8L * mb, maxUploadBytes("wallpaper"))
        assertEquals(2 * mb, ThumbnailLimits.MAX_BYTES); assertEquals(2L * mb, maxUploadBytes("thumbnail"))
        assertEquals(8 * mb, BrandFileLimits.MAX_BYTES)
    }

    @Test fun `exactly 8 MB is accepted, one byte more is refused (logo, wallpaper, ad)`() {
        for (kind in listOf("logo", "wallpaper", "ad")) {
            val ok = prepareUpload({ ByteArrayInputStream(png(1920, 1080, 8 * mb)) }, 8L * mb, kind)
            assertTrue("$kind 8 MB: $ok", ok is PreparedUpload.Ready)
            assertEquals(8L * mb, (ok as PreparedUpload.Ready).size)
            val bad = prepareUpload({ ByteArrayInputStream(png(1920, 1080, 8 * mb + 1)) }, 8L * mb + 1, kind)
            assertEquals("The image is too large -- the limit is 8 MB per file.", (bad as PreparedUpload.Refused).message)
        }
    }

    @Test fun `4 MB and 6 MB files, refused before, now work`() {
        assertTrue(prepareUpload({ ByteArrayInputStream(png(2000, 2000, 4 * mb + 1)) }, 4L * mb + 1, "logo") is PreparedUpload.Ready)
        assertTrue(prepareUpload({ ByteArrayInputStream(png(2000, 2000, 6 * mb)) }, 6L * mb, "ad") is PreparedUpload.Ready)
    }

    @Test fun `thumbnail stays at 2 MB`() {
        assertTrue(prepareUpload({ ByteArrayInputStream(png(1280, 720, 2 * mb)) }, 2L * mb, "thumbnail") is PreparedUpload.Ready)
        val bad = prepareUpload({ ByteArrayInputStream(png(1280, 720, 2 * mb + 1)) }, 2L * mb + 1, "thumbnail")
        assertEquals("The thumbnail is too large -- the limit is 2 MB.", (bad as PreparedUpload.Refused).message)
        // a 3 MB picture is fine as a logo but not as a thumbnail
        assertTrue(prepareUpload({ ByteArrayInputStream(png(1280, 720, 3 * mb)) }, 3L * mb, "logo") is PreparedUpload.Ready)
        assertTrue(prepareUpload({ ByteArrayInputStream(png(1280, 720, 3 * mb)) }, 3L * mb, "thumbnail") is PreparedUpload.Refused)
        assertTrue(prepareUpload({ ByteArrayInputStream(png(600, 400, 1000)) }, 1000, "thumbnail") is PreparedUpload.Refused) // < 640 px wide
    }

    @Test fun `the same words as before for the byte-array checks`() {
        assertTrue((checkAssetUpload(ByteArray(MAX_ASSET_BYTES + 1)) as UploadCheck.Refused).message.contains("limit is 8 MB per file"))
        assertTrue(checkAssetUpload(png(512, 512, MAX_ASSET_BYTES)) is UploadCheck.Ok)
        assertTrue(checkThumbnailUpload(png(1280, 720, ThumbnailLimits.MAX_BYTES)) is UploadCheck.Ok)
        assertTrue((checkThumbnailUpload(png(1280, 720, ThumbnailLimits.MAX_BYTES + 1)) as UploadCheck.Refused).message.contains("limit is 2 MB"))
        assertEquals("Choose an image file to upload.", (checkAssetHead(ByteArray(0), 0, true) as UploadCheck.Refused).message)
    }

    // ---- not loading the file ----

    @Test fun `an oversize file is refused from its size alone, without opening it`() {
        var opened = 0
        val r = prepareUpload({ opened++; ByteArrayInputStream(ByteArray(10)) }, 9L * mb, "logo")
        assertTrue(r is PreparedUpload.Refused); assertEquals(0, opened)
    }

    @Test fun `an accepted 8 MB file is read only as far as its header, in small steps`() {
        lateinit var c: Counting
        val data = png(4096, 4096, 8 * mb)
        val r = prepareUpload({ Counting(ByteArrayInputStream(data)).also { c = it } }, data.size.toLong(), "logo")
        assertTrue(r is PreparedUpload.Ready)
        assertTrue("read ${c.total}", c.total <= SNIFF_HEAD_BYTES)
        assertTrue(c.maxChunk <= SNIFF_HEAD_BYTES)
    }

    @Test fun `unknown size is counted by streaming through, keeping nothing`() {
        val data = png(800, 600, 3 * mb)
        val r = prepareUpload({ ByteArrayInputStream(data) }, -1, "logo")
        assertEquals(3L * mb, (r as PreparedUpload.Ready).size)
        val big = prepareUpload({ ByteArrayInputStream(png(800, 600, 9 * mb)) }, -1, "logo")
        assertTrue(big is PreparedUpload.Refused)
        assertTrue(prepareUpload({ ByteArrayInputStream(ByteArray(0)) }, -1, "logo") is PreparedUpload.Refused)
        val thumb = prepareUpload({ ByteArrayInputStream(png(1280, 720, 3 * mb)) }, -1, "thumbnail")
        assertTrue((thumb as PreparedUpload.Refused).message.contains("2 MB"))
    }

    @Test fun `a JPEG whose size marker sits behind large metadata is still understood`() {
        val j = jpeg(3000, 2000, metaKb = 700, total = 2 * mb)
        val r = prepareUpload({ ByteArrayInputStream(j) }, j.size.toLong(), "logo")
        assertTrue(r.toString(), r is PreparedUpload.Ready)
        assertEquals(3000, (r as PreparedUpload.Ready).info.width)
        // a plain JPEG
        val small = jpeg(1280, 720, metaKb = 0, total = 5000)
        assertTrue(prepareUpload({ ByteArrayInputStream(small) }, small.size.toLong(), "thumbnail") is PreparedUpload.Ready)
    }

    @Test fun `not an image, GIF as wallpaper, too many pixels, cannot open`() {
        val junk = ByteArray(5000) { 7 }
        assertTrue((prepareUpload({ ByteArrayInputStream(junk) }, 5000, "logo") as PreparedUpload.Refused).message.contains("PNG, JPG or WebP"))
        val gif = ByteArray(100).also { "GIF89a".toByteArray().copyInto(it); it[6] = 10; it[8] = 10 }
        assertTrue(prepareUpload({ ByteArrayInputStream(gif) }, 100, "logo") is PreparedUpload.Ready)
        assertTrue(prepareUpload({ ByteArrayInputStream(gif) }, 100, "ad") is PreparedUpload.Ready)
        assertTrue(prepareUpload({ ByteArrayInputStream(gif) }, 100, "wallpaper") is PreparedUpload.Refused)
        assertTrue((prepareUpload({ ByteArrayInputStream(png(9000, 100, 5000)) }, 5000, "logo") as PreparedUpload.Refused).message.contains("8192"))
        assertEquals("Cannot open the chosen image.", (prepareUpload({ null }, 5000, "logo") as PreparedUpload.Refused).message)
        assertEquals("Cannot open the chosen image.", (prepareUpload({ null }, -1, "logo") as PreparedUpload.Refused).message)
        assertEquals("Cannot open the chosen image.", (prepareUpload({ object : InputStream() { override fun read(): Int = throw java.io.IOException("gone") } }, 5000, "logo") as PreparedUpload.Refused).message)
        assertNull(checkUploadSize(-1, "logo")); assertNull(checkUploadSize(1, "logo")); assertFalse(checkUploadSize(0, "logo") == null)
    }
}
