package app.streambird.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Big brand-image uploads: streamed from the source in small chunks (not copied into memory), announced with a Content-Length, slow-uplink timeouts. */
class StreamingUploadTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val mb = 1024 * 1024

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private class Probe(private val inner: InputStream) : InputStream() {
        var maxRequest = 0; var total = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) total++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) total += it; maxRequest = maxOf(maxRequest, len) }
    }

    private fun payload(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private val assetJson = """{"id":"a1","kind":"logo","name":"x","contentType":"image/png","sizeBytes":1,"width":10,"height":10,"createdAt":"2026-11-07T00:00:00.000Z"}"""

    @Test fun `an 8 MB file goes out intact with a Content-Length, read in small steps`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(assetJson))
        val data = payload(8 * mb)
        lateinit var probe: Probe
        val body = StreamingFileBody("image/png", data.size.toLong()) { Probe(ByteArrayInputStream(data)).also { probe = it } }
        api.uploadAssetStreaming(body, "logo.png", kind = "logo", name = "logo")
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/api/assets", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertEquals("known length, not chunked", null, r.getHeader("Transfer-Encoding"))
        assertTrue((r.getHeader("Content-Length")!!.toLong()) > data.size)
        val text = r.body.readByteArray()
        assertTrue(indexOf(text, data) >= 0)
        assertTrue("the file was read in steps of at most 64 KB, asked ${probe.maxRequest}", probe.maxRequest <= StreamingFileBody.CHUNK.toInt())
        assertEquals(data.size.toLong(), probe.total)
        assertTrue(String(text, 0, 600, Charsets.ISO_8859_1).contains("name=\"kind\""))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        // compare through okio for speed: the needle appears once in the multipart body
        val buf = Buffer().write(hay)
        return buf.indexOf(needle.toByteString()).toInt()
    }

    @Test fun `a file that is shorter or longer than announced fails the upload instead of sending garbage`() {
        val short = StreamingFileBody("image/png", 1000) { ByteArrayInputStream(ByteArray(900)) }
        try { short.writeTo(Buffer()); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("changed")) }
        val long = StreamingFileBody("image/png", 1000) { ByteArrayInputStream(ByteArray(1100)) }
        try { long.writeTo(Buffer()); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("changed")) }
        val ok = Buffer(); StreamingFileBody("image/png", 1000) { ByteArrayInputStream(ByteArray(1000)) }.writeTo(ok); assertEquals(1000L, ok.size)
    }

    @Test fun `the body can be written again (connection retry) and is not one-shot`() {
        val b = StreamingFileBody("image/png", 10) { ByteArrayInputStream(ByteArray(10)) }
        assertFalse(b.isOneShot()); assertEquals(10L, b.contentLength())
        repeat(2) { val sink = Buffer(); b.writeTo(sink); assertEquals(10L, sink.size) }
    }

    @Test fun `uploads use a client for slow uplinks, API calls keep theirs`() {
        assertEquals(60_000, api.uploadHttp.writeTimeoutMillis)
        assertEquals(120_000, api.uploadHttp.readTimeoutMillis)
        assertEquals(0, api.uploadHttp.callTimeoutMillis)
        assertEquals(api.http.connectTimeoutMillis, api.uploadHttp.connectTimeoutMillis)
        assertEquals("Origin interceptor and cookie jar are shared", api.http.interceptors.size, api.uploadHttp.interceptors.size)
        assertTrue(api.http.cookieJar === api.uploadHttp.cookieJar)
        assertTrue(api.uploadHttp.writeTimeoutMillis > api.http.writeTimeoutMillis)
    }

    @Test fun `the small upload path (byte array) also goes through the upload client and carries the Origin`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(assetJson))
        api.uploadAsset(payload(2000), "image/png", "a.png", kind = "ad", name = "n")
        val r = server.takeRequest()
        assertEquals(server.url("/").toString().trimEnd('/'), r.getHeader("Origin"))
        assertTrue(r.body.readUtf8().contains("name=\"kind\""))
    }

    @Test fun `a server refusal of a big upload is shown in its own words`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json").setBody("""{"statusCode":400,"message":{"message":"The image is too large -- the limit is 8 MB per file.","error":"Bad Request","statusCode":400},"timestamp":"x"}"""))
        val body = StreamingFileBody("image/png", 100) { ByteArrayInputStream(ByteArray(100)) }
        try { api.uploadAssetStreaming(body, "x.png"); fail() } catch (e: ApiException) {
            assertEquals(400, e.status); assertEquals("The image is too large -- the limit is 8 MB per file.", e.message)
        }
    }
}
