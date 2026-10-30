package app.streambird.android.whip

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class WhipClientTest {
    private lateinit var server: MockWebServer
    private val client = WhipClient(OkHttpClient())

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private val offer = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\n"
    private val answer = "v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\ns=-\r\nm=video 9 UDP/TLS/RTP/SAVPF 102\r\n"

    @Test fun `posts the offer as application sdp and returns answer plus resolved relative Location`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/live-abc/whip/6f1c").setHeader("Content-Type", "application/sdp").setBody(answer))
        val s = client.publish(server.url("/live-abc/whip").toString(), offer)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/live-abc/whip", req.path)
        assertEquals("application/sdp", req.getHeader("Content-Type"))
        assertEquals(offer, req.body.readUtf8())
        assertEquals(answer, s.answerSdp)
        assertEquals(server.url("/live-abc/whip/6f1c").toString(), s.resourceUrl)
    }

    @Test fun `absolute Location is kept as is`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "https://ingest.example.com/p/res").setBody(answer))
        assertEquals("https://ingest.example.com/p/res", client.publish(server.url("/x/whip").toString(), offer).resourceUrl)
    }

    @Test fun `missing Location gives null resource`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(answer))
        assertNull(client.publish(server.url("/x/whip").toString(), offer).resourceUrl)
    }

    @Test fun `non 2xx becomes WhipException with status`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("path not found"))
        try {
            client.publish(server.url("/gone/whip").toString(), offer)
            fail("expected exception")
        } catch (e: WhipException) {
            assertEquals(404, e.status)
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test fun `empty answer is an error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/r"))
        try { client.publish(server.url("/x/whip").toString(), offer); fail() } catch (e: WhipException) { assertTrue(e.message!!.contains("no SDP")) }
    }

    @Test fun `hangUp sends DELETE to the resource and never throws`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        client.hangUp(server.url("/live/whip/res1").toString())
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/live/whip/res1", req.path)
        client.hangUp(null)
        server.shutdown()
        client.hangUp("http://127.0.0.1:1/never") // connection refused: swallowed
    }
}
