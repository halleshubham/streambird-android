package app.streambird.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** Progress callbacks of [StreamingFileBody] (what the StreamBird upload loader shows). */
class StreamingProgressTest {
    @Test fun `progress starts at 0, never goes backwards and ends at the total`() {
        val data = ByteArray(300_000) { it.toByte() }
        val seen = mutableListOf<Pair<Long, Long>>()
        val body = StreamingFileBody("image/png", data.size.toLong(), onProgress = { s, t -> seen += s to t }) { ByteArrayInputStream(data) }
        val sink = okio.Buffer()
        body.writeTo(sink)
        assertEquals(data.size.toLong(), sink.size)
        assertEquals(0L to data.size.toLong(), seen.first())
        assertEquals(data.size.toLong() to data.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue(seen.all { it.second == data.size.toLong() })
    }

    @Test fun `a file that changed while uploading fails, so the error path clears the loader`() {
        val body = StreamingFileBody("image/png", 100L, onProgress = { _, _ -> }) { ByteArrayInputStream(ByteArray(10)) }
        try { body.writeTo(okio.Buffer()); fail("expected IOException") } catch (e: IOException) { assertTrue(e.message!!.contains("could not be read")) }
    }
}
