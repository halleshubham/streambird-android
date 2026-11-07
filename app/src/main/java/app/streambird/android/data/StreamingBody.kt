package app.streambird.android.data

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream

/**
 * A request body that STREAMS a file (a gallery Uri, a file) to the socket in small chunks instead of holding it in memory: an 8 MB brand image is
 * never copied into a ByteArray, a ByteArrayOutputStream and a second array before it is sent. [open] is called once per attempt (OkHttp may write the
 * body again after a connection failure), [length] is the exact size announced as Content-Length (so the server sees a normal upload, not chunked).
 * If the file turns out shorter or longer than [length] (it changed while uploading) the upload fails with an IOException instead of sending garbage.
 */
class StreamingFileBody(
    private val mime: String,
    private val length: Long,
    private val open: () -> InputStream,
) : RequestBody() {
    override fun contentType(): MediaType? = mime.toMediaType()
    override fun contentLength(): Long = length
    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        var left = length
        open().use { input ->
            val source = input.source()
            while (left > 0) {
                val n = source.read(sink.buffer, minOf(CHUNK, left))
                if (n <= 0) throw IOException("The picture changed or could not be read while uploading.")
                left -= n
                sink.emitCompleteSegments()
            }
            if (input.read() != -1) throw IOException("The picture changed while uploading.")
        }
    }

    companion object {
        /** Bytes moved per step (also the largest single read from the file). */
        const val CHUNK = 64L * 1024
    }
}
