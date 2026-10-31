package app.streambird.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiRobustnessTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val jar = PersistentCookieJar(MemoryStorage())

    class MemoryStorage : CookieStorage {
        @Volatile var value: String? = null
        override fun load() = value
        override fun save(json: String) { value = json }
    }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().cookieJar(jar).addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private suspend fun expectApiException(block: suspend () -> Unit): ApiException = try { block(); fail("expected ApiException"); throw AssertionError() } catch (e: ApiException) { e }

    @Test fun `a 200 HTML page (captive portal, CDN) becomes an ApiException, never an uncaught serialization error`() = runBlocking {
        val html = "<html><body>Please log in to the Wi-Fi</body></html>"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
        listOf<suspend () -> Unit>({ api.me() }, { api.platformConnections() }, { api.myLimits() }).forEach { call ->
            val e = expectApiException(call)
            assertEquals(-1, e.status); assertTrue(e.message.contains("unexpected answer"))
        }
    }

    @Test fun `incomplete JSON is tolerated where the backend may omit fields, and an ApiException where it cannot`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"id":"c1"}]"""))                  // label/platform missing
        assertEquals("c1", api.platformConnections().single().id)
        server.enqueue(MockResponse().setBody("""{"planKey":"free"}"""))              // maxDestinations missing -> default 1
        assertEquals(1, api.myLimits().maxDestinations)
        server.enqueue(MockResponse().setBody("""{"user":{"id":"u"}}"""))             // role/email missing -> defaults
        assertEquals("user", api.me().user.role)
        server.enqueue(MockResponse().setBody("""{"nothing":true}"""))                // no user at all: required
        assertEquals(-1, expectApiException { api.me() }.status)
        server.enqueue(MockResponse().setBody("not json at all"))
        assertEquals(-1, expectApiException { api.createStream(CreateStreamRequest("t", destinationConnectionIds = listOf("c"))) }.status)
    }

    @Test fun `asset downloads are capped`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(1024))))
        assertEquals(1024, api.assetFile("a").size)
        val big = okio.Buffer().write(ByteArray((ApiClient.MAX_ASSET_DOWNLOAD + 5).toInt()))
        server.enqueue(MockResponse().setBody(big))
        assertTrue(expectApiException { api.assetFile("b") }.message.contains("too large"))
    }

    @Test fun `cancelling the coroutine cancels the HTTP call`() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS).setBody("{}"))
        val started = System.currentTimeMillis()
        val job = launch(Dispatchers.Default) { runCatching { api.me() } }
        kotlinx.coroutines.delay(200); job.cancel(); job.join()
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test fun `plain http server addresses are rejected unless local or debug`() {
        try { normalizeBaseUrl("http://streambird.example.com", allowCleartext = false); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.startsWith("Use an https")) }
        assertEquals("http://192.168.1.5:3000", normalizeBaseUrl("http://192.168.1.5:3000", allowCleartext = false))
        assertEquals("https://streambird.app", normalizeBaseUrl("streambird.app/", allowCleartext = false))
        assertEquals("http://staging.example.com", normalizeBaseUrl("http://staging.example.com", allowCleartext = true))
    }

    // ---- cookie persistence ordering ----

    /** Storage whose FIRST write is slow, like a busy disk: without ordered writes it would land after the clear. */
    private class SlowFirstStorage : CookieStorage {
        private val writer = SerialWriter("test-writes")
        @Volatile var disk: String? = null
        private val n = java.util.concurrent.atomic.AtomicInteger()
        override fun load() = disk
        override fun save(json: String) { val i = n.incrementAndGet(); writer.submit { if (i == 1) Thread.sleep(250); disk = json } }
        override suspend fun flush() { writer.flush() }
    }

    @Test fun `clear followed by a slower older save must not resurrect the session cookie`() = runBlocking {
        val storage = SlowFirstStorage()
        val j = PersistentCookieJar(storage)
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("streambird.test").build()
        j.saveFromResponse(url, listOf(okhttp3.Cookie.Builder().name("sb_session").value("tok").domain("streambird.test").path("/").expiresAt(System.currentTimeMillis() + 3_600_000).build()))
        assertTrue(j.hasCookie("sb_session"))
        j.clear(); j.flush()                                              // logout
        assertEquals("[]", storage.disk)
        val restarted = PersistentCookieJar(storage)                      // next app start
        assertFalse(restarted.hasCookie("sb_session"))
    }

    @Test fun `serial writer applies writes in submission order`() = runBlocking {
        val w = SerialWriter("order"); val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        w.submit { Thread.sleep(100); seen.add(1) }; w.submit { seen.add(2) }; w.submit { seen.add(3) }
        w.flush()
        assertEquals(listOf(1, 2, 3), seen)
    }
}
