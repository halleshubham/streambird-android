package app.streambird.android.brand

/** Pure-Kotlin image sniffing for uploads. Mirrors the backend's image.util.ts (PNG/JPEG/WebP/GIF, 8 MB, 8192 px) so refusals show before the upload. */
enum class ImageFormat(val mime: String, val ext: String) {
    PNG("image/png", "png"), JPEG("image/jpeg", "jpg"), WEBP("image/webp", "webp"), GIF("image/gif", "gif")
}

data class ImageInfo(val format: ImageFormat, val width: Int, val height: Int)

/** Per-file cap of every brand image (logo, wallpaper, ads incl. portrait and the L-shape frame): 8 MB since the server raised it from 4 MB. The stream/kit THUMBNAIL stays at [ThumbnailLimits.MAX_BYTES] (2 MB). */
const val MAX_ASSET_BYTES = 8 * 1024 * 1024
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

/**
 * Same refusals (and nearly the same words) as the backend's parseImage, so the user sees them before spending the upload.
 * [head] is the start of the file (enough to read the format and the pixel size) and [totalSize] the size of the WHOLE file in bytes, so an 8 MB
 * picture is judged by its declared size without being held in memory. A file of exactly [MAX_ASSET_BYTES] is fine, one byte more is refused.
 */
fun checkAssetHead(head: ByteArray, totalSize: Long, allowGif: Boolean = true): UploadCheck {
    if (totalSize <= 0L) return UploadCheck.Refused("Choose an image file to upload.")
    if (totalSize > MAX_ASSET_BYTES) return UploadCheck.Refused("The image is too large -- the limit is ${MAX_ASSET_BYTES / 1024 / 1024} MB per file.")
    val info = sniffImage(head) ?: return UploadCheck.Refused("The file must be a PNG, JPG or WebP image (a GIF also works for logos).")
    if (info.format == ImageFormat.GIF && !allowGif) return UploadCheck.Refused("GIF images can only be used as a logo.")
    if (info.width <= 0 || info.height <= 0) return UploadCheck.Refused("That image file looks damaged -- couldn't read its size.")
    if (info.width > MAX_ASSET_SIDE || info.height > MAX_ASSET_SIDE) return UploadCheck.Refused("The image is too big -- its longest side must be at most ${MAX_ASSET_SIDE}px.")
    return UploadCheck.Ok(info)
}

fun checkAssetUpload(bytes: ByteArray, allowGif: Boolean = true): UploadCheck = checkAssetHead(bytes, bytes.size.toLong(), allowGif)

/**
 * Pre-check for a picture uploaded as the kit's stream thumbnail (asset kind `thumbnail`): the library rules plus the platform ones
 * (JPG or PNG, at most 2 MB, at least 640 px wide), in the same words as the server's thumbnail.util.ts. The thumbnail cap stays 2 MB.
 */
fun checkThumbnailHead(head: ByteArray, totalSize: Long): UploadCheck {
    if (totalSize <= 0L) return UploadCheck.Refused("Choose an image file to upload.")
    if (totalSize > ThumbnailLimits.MAX_BYTES) return UploadCheck.Refused("The thumbnail is too large -- the limit is ${ThumbnailLimits.MAX_BYTES / 1024 / 1024} MB.")
    val info = sniffImage(head)
    if (info == null || (info.format != ImageFormat.JPEG && info.format != ImageFormat.PNG)) return UploadCheck.Refused("The thumbnail must be a JPG or PNG image.")
    if (info.width <= 0 || info.height <= 0) return UploadCheck.Refused("That image file looks damaged -- couldn't read its size.")
    if (info.width < ThumbnailLimits.MIN_WIDTH) return UploadCheck.Refused("The thumbnail is too small -- it must be at least ${ThumbnailLimits.MIN_WIDTH}px wide (1280x720 is recommended).")
    if (info.width > MAX_ASSET_SIDE || info.height > MAX_ASSET_SIDE) return UploadCheck.Refused("The image is too big -- its longest side must be at most ${MAX_ASSET_SIDE}px.")
    return UploadCheck.Ok(info)
}

fun checkThumbnailUpload(bytes: ByteArray): UploadCheck = checkThumbnailHead(bytes, bytes.size.toLong())

/** The most bytes a file of this upload kind may have: 2 MB for a thumbnail, [MAX_ASSET_BYTES] for everything else. */
fun maxUploadBytes(kind: String): Long = if (kind == "thumbnail") ThumbnailLimits.MAX_BYTES.toLong() else MAX_ASSET_BYTES.toLong()

/** How much of the start of a file is read to find its format and size (a JPEG can hide its size marker behind large metadata). */
const val SNIFF_HEAD_BYTES = 512 * 1024

/** Size-only refusal for a file whose size the phone already knows, before a single byte is read; null = go on. */
fun checkUploadSize(sizeBytes: Long, kind: String): UploadCheck.Refused? = when {
    sizeBytes < 0L -> null // unknown size: counted while reading
    sizeBytes == 0L -> UploadCheck.Refused("Choose an image file to upload.")
    kind == "thumbnail" && sizeBytes > ThumbnailLimits.MAX_BYTES -> UploadCheck.Refused("The thumbnail is too large -- the limit is ${ThumbnailLimits.MAX_BYTES / 1024 / 1024} MB.")
    kind != "thumbnail" && sizeBytes > MAX_ASSET_BYTES -> UploadCheck.Refused("The image is too large -- the limit is ${MAX_ASSET_BYTES / 1024 / 1024} MB per file.")
    else -> null
}


fun formatBytes(n: Long): String = when {
    n >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
    n >= 1024 -> "${Math.round(n / 1024.0)} KB"
    else -> "$n B"
}
