package app.streambird.android.brand

import java.io.IOException
import java.io.InputStream

/** Outcome of looking at a picked file BEFORE uploading it. */
sealed interface PreparedUpload {
    /** The file may be uploaded: its format/size [info] and its exact byte [size] (for Content-Length). */
    data class Ready(val info: ImageInfo, val size: Long) : PreparedUpload
    data class Refused(val message: String) : PreparedUpload
}

/**
 * Judges a picked file without loading it: the declared [declaredSize] (-1 = unknown, then counted by reading through the file once without keeping
 * anything) is checked against the kind's cap first (8 MB for brand images, 2 MB for a thumbnail), then only the first [SNIFF_HEAD_BYTES] are read to find
 * the format and the pixel size. A JPEG whose size marker hides behind more metadata than that is re-read in full (still capped by the file limit).
 * [open] must return a fresh stream each call (null = cannot open). Pure JVM; the Android side only supplies [open] and the declared size.
 */
fun prepareUpload(open: () -> InputStream?, declaredSize: Long, kind: String): PreparedUpload {
    checkUploadSize(declaredSize, kind)?.let { return PreparedUpload.Refused(it.message) }
    val max = maxUploadBytes(kind)
    val size = if (declaredSize >= 0) declaredSize else {
        val counted = try { (open() ?: return cannotOpen()).use { countBytes(it, max + 1) } } catch (e: IOException) { return cannotOpen() }
        checkUploadSize(counted, kind)?.let { return PreparedUpload.Refused(it.message) }
        counted
    }
    fun judge(head: ByteArray): UploadCheck = if (kind == "thumbnail") checkThumbnailHead(head, size) else checkAssetHead(head, size, allowGif = kind != "wallpaper")
    var head = try { readHead(open, minOf(size, SNIFF_HEAD_BYTES.toLong()).toInt()) ?: return cannotOpen() } catch (e: IOException) { return cannotOpen() }
    var check = judge(head)
    if (check is UploadCheck.Refused && sniffImage(head) == null && size > head.size) {
        head = try { readHead(open, size.toInt()) ?: return cannotOpen() } catch (e: IOException) { return cannotOpen() }
        check = judge(head)
    }
    return when (check) {
        is UploadCheck.Refused -> PreparedUpload.Refused(check.message)
        is UploadCheck.Ok -> PreparedUpload.Ready(check.info, size)
    }
}

private fun cannotOpen() = PreparedUpload.Refused("Cannot open the chosen image.")

/** Reads at most [limit] bytes and returns how many there were, keeping none of them (64 KB buffer). */
internal fun countBytes(input: InputStream, limit: Long): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (total < limit) {
        val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
        if (n < 0) break
        total += n
    }
    return total
}

/** The first [n] bytes of a fresh stream (fewer when the file is shorter); null when it cannot be opened. */
private fun readHead(open: () -> InputStream?, n: Int): ByteArray? {
    val input = open() ?: return null
    return input.use {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = it.read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        if (read == n) out else out.copyOf(read)
    }
}
