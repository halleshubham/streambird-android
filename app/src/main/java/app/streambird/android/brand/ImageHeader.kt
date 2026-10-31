package app.streambird.android.brand

/** Pure-Kotlin image sniffing for uploads. Mirrors the backend's image.util.ts (PNG/JPEG/WebP/GIF, 4 MB, 8192 px) so refusals show before the upload. */
enum class ImageFormat(val mime: String, val ext: String) {
    PNG("image/png", "png"), JPEG("image/jpeg", "jpg"), WEBP("image/webp", "webp"), GIF("image/gif", "gif")
}

data class ImageInfo(val format: ImageFormat, val width: Int, val height: Int)

const val MAX_ASSET_BYTES = 4 * 1024 * 1024
const val MAX_ASSET_SIDE = 8192

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xff
private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
private fun ByteArray.be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
private fun ByteArray.be32(i: Int) = (u8(i).toLong() shl 24 or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()).toInt()
private fun ByteArray.ascii(from: Int, len: Int) = String(this, from, len, Charsets.ISO_8859_1)

/** Detects the format by its bytes (never by file name) and reads the pixel size; null when it is none of the four or damaged. */
fun sniffImage(b: ByteArray): ImageInfo? {
    if (b.size >= 24 && b.u8(0) == 0x89 && b.ascii(1, 3) == "PNG" && b.ascii(12, 4) == "IHDR") return ImageInfo(ImageFormat.PNG, b.be32(16), b.be32(20))
    if (b.size >= 10 && b.ascii(0, 3) == "GIF" && (b.ascii(0, 6) == "GIF87a" || b.ascii(0, 6) == "GIF89a")) return ImageInfo(ImageFormat.GIF, b.le16(6), b.le16(8))
    if (b.size >= 4 && b.u8(0) == 0xff && b.u8(1) == 0xd8) return jpeg(b)
    if (b.size >= 30 && b.ascii(0, 4) == "RIFF" && b.ascii(8, 4) == "WEBP") return webp(b)
    return null
}

private fun jpeg(b: ByteArray): ImageInfo? {
    var i = 2
    while (i + 9 < b.size) {
        if (b.u8(i) != 0xff) { i += 1; continue }
        val m = b.u8(i + 1)
        if (m == 0xd8 || m == 0x01 || m in 0xd0..0xd7 || m == 0xff) { i += if (m == 0xff) 1 else 2; continue }
        val len = b.be16(i + 2)
        if (m in 0xc0..0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc) return ImageInfo(ImageFormat.JPEG, b.be16(i + 7), b.be16(i + 5))
        i += 2 + len
    }
    return null
}

private fun webp(b: ByteArray): ImageInfo? = when (b.ascii(12, 4)) {
    "VP8 " -> ImageInfo(ImageFormat.WEBP, b.le16(26) and 0x3fff, b.le16(28) and 0x3fff)
    "VP8L" -> {
        val w = 1 + (((b.u8(22) and 0x3f) shl 8) or b.u8(21))
        val h = 1 + (((b.u8(24) and 0x0f) shl 10) or (b.u8(23) shl 2) or ((b.u8(22) and 0xc0) shr 6))
        ImageInfo(ImageFormat.WEBP, w, h)
    }
    "VP8X" -> ImageInfo(ImageFormat.WEBP, 1 + (b.u8(24) or (b.u8(25) shl 8) or (b.u8(26) shl 16)), 1 + (b.u8(27) or (b.u8(28) shl 8) or (b.u8(29) shl 16)))
    else -> null
}

sealed interface UploadCheck {
    data class Ok(val info: ImageInfo) : UploadCheck
    data class Refused(val message: String) : UploadCheck
}

/** Same refusals (and nearly the same words) as the backend's parseImage, so the user sees them before spending the upload. */
fun checkAssetUpload(bytes: ByteArray, allowGif: Boolean = true): UploadCheck {
    if (bytes.isEmpty()) return UploadCheck.Refused("Choose an image file to upload.")
    if (bytes.size > MAX_ASSET_BYTES) return UploadCheck.Refused("The image is too large -- the limit is ${MAX_ASSET_BYTES / 1024 / 1024} MB per file.")
    val info = sniffImage(bytes) ?: return UploadCheck.Refused("The file must be a PNG, JPG or WebP image (a GIF also works for logos).")
    if (info.format == ImageFormat.GIF && !allowGif) return UploadCheck.Refused("GIF images can only be used as a logo.")
    if (info.width <= 0 || info.height <= 0) return UploadCheck.Refused("That image file looks damaged -- couldn't read its size.")
    if (info.width > MAX_ASSET_SIDE || info.height > MAX_ASSET_SIDE) return UploadCheck.Refused("The image is too big -- its longest side must be at most ${MAX_ASSET_SIDE}px.")
    return UploadCheck.Ok(info)
}

fun formatBytes(n: Long): String = when {
    n >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
    n >= 1024 -> "${Math.round(n / 1024.0)} KB"
    else -> "$n B"
}
