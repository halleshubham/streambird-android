package app.streambird.android.whip

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The HTTP side of a re-publish: new POST to the same WHIP URL first, then DELETE of the old resource; retryable failures surface as exceptions. */
class WhipRepublishTest {
    private lateinit var server: MockWebServer
    private val offer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\n"
    private val answer = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\n"

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `republish posts a new offer to the same URL, then deletes the old resource`() = runBlocking {
        val client = WhipClient(OkHttpClient())
        val url = server.url("/stream-id/whip").toString()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/stream-id/whip/res1").setBody(answer))
        val first = client.publish(url, offer)

        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/stream-id/whip/res2").setBody(answer))
        server.enqueue(MockResponse().setResponseCode(204))                      // DELETE of the old resource
        val second = client.publish(url, offer)
        client.hangUp(first.resourceUrl)

        val r1 = server.takeRequest(); val r2 = server.takeRequest(); val r3 = server.takeRequest()
        assertEquals("POST", r1.method)
        assertEquals("POST", r2.method); assertEquals("/stream-id/whip", r2.path)  // same stream URL
        assertEquals("DELETE", r3.method); assertEquals("/stream-id/whip/res1", r3.path) // old one, after the new POST
        assertTrue(second.resourceUrl!!.endsWith("/res2"))
    }

    @Test fun `5xx from the ingest is an exception the caller retries`() = runBlocking {
        val client = WhipClient(OkHttpClient())
        val url = server.url("/p/whip").toString()
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/p/whip/r").setBody(answer))
        try { client.publish(url, offer); fail() } catch (e: WhipException) { assertEquals(503, e.status) }
        assertEquals("/p/whip/r".length > 0, client.publish(url, offer).resourceUrl!!.endsWith("/p/whip/r"))
    }

    @Test fun `a server that never answers times out as an IOException`() = runBlocking {
        val client = WhipClient(OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build())
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        try { client.publish(server.url("/p/whip").toString(), offer); fail() } catch (e: IOException) { /* retried by the Reconnector */ }
    }

    @Test fun `hangUp of an already gone resource (404) or a dead server never throws`() = runBlocking {
        val client = WhipClient(OkHttpClient())
        server.enqueue(MockResponse().setResponseCode(404))
        client.hangUp(server.url("/p/whip/old").toString())
        server.shutdown()
        client.hangUp("http://127.0.0.1:1/never")
        client.hangUp(null)
    }
}
