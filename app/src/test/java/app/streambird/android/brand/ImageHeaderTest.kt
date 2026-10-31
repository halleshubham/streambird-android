package app.streambird.android.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHeaderTest {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun png(w: Int, h: Int) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13) + "IHDR".toByteArray() + be32(w) + be32(h) + ByteArray(10)
    private fun gif(w: Int, h: Int) = "GIF89a".toByteArray() + le16(w) + le16(h) + ByteArray(10)
    private fun jpeg(w: Int, h: Int) = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 4, 0, 0, 0xff.toByte(), 0xc0.toByte(), 0, 11, 8, (h ushr 8).toByte(), h.toByte(), (w ushr 8).toByte(), w.toByte(), 1, 0, 0, 0)
    private fun webpX(w: Int, h: Int): ByteArray {
        fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())
        return "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + "VP8X".toByteArray() + ByteArray(4) + ByteArray(4) + le24(w - 1) + le24(h - 1) + ByteArray(4)
    }
    private fun webpLossy(w: Int, h: Int) = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + "VP8 ".toByteArray() + ByteArray(4) + ByteArray(6) + le16(w) + le16(h) + ByteArray(4)

    @Test fun `sniffs the four formats and their size`() {
        assertEquals(ImageInfo(ImageFormat.PNG, 640, 480), sniffImage(png(640, 480)))
        assertEquals(ImageInfo(ImageFormat.GIF, 200, 100), sniffImage(gif(200, 100)))
        assertEquals(ImageInfo(ImageFormat.JPEG, 1920, 1080), sniffImage(jpeg(1920, 1080)))
        assertEquals(ImageInfo(ImageFormat.WEBP, 300, 120), sniffImage(webpX(300, 120)))
        assertEquals(ImageInfo(ImageFormat.WEBP, 512, 256), sniffImage(webpLossy(512, 256)))
        assertNull(sniffImage("%PDF-1.4 not an image at all, long enough".toByteArray() + ByteArray(20)))
    }

    @Test fun `accepts a normal logo`() {
        val c = checkAssetUpload(png(512, 512))
        assertTrue(c is UploadCheck.Ok)
    }

    @Test fun `refuses like the backend`() {
        assertEquals("Choose an image file to upload.", (checkAssetUpload(ByteArray(0)) as UploadCheck.Refused).message)
        assertTrue((checkAssetUpload(ByteArray(MAX_ASSET_BYTES + 1)) as UploadCheck.Refused).message.contains("limit is 4 MB"))
        assertTrue((checkAssetUpload(ByteArray(100) { 7 }) as UploadCheck.Refused).message.contains("PNG, JPG or WebP"))
        assertTrue((checkAssetUpload(png(9000, 100)) as UploadCheck.Refused).message.contains("8192"))
        assertTrue((checkAssetUpload(png(100, 8193)) as UploadCheck.Refused).message.contains("8192"))
        assertTrue(checkAssetUpload(png(8192, 8192)) is UploadCheck.Ok)
        assertTrue((checkAssetUpload(gif(10, 10), allowGif = false) as UploadCheck.Refused).message.contains("only be used as a logo"))
        assertTrue(checkAssetUpload(gif(10, 10)) is UploadCheck.Ok)
    }

    @Test fun `format bytes`() {
        assertEquals("512 B", formatBytes(512)); assertEquals("20 KB", formatBytes(20 * 1024)); assertEquals("1.5 MB", formatBytes(1_572_864))
    }
}
