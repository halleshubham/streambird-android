package app.streambird.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import app.streambird.android.media.StreamOrientation

/** Sample JSON is copied from the backend DTOs / controllers (see docs/API-CONTRACT.md for the source of each). */
class ApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var jar: PersistentCookieJar
    private lateinit var api: ApiClient
    private val storage = MemoryStorage()

    class MemoryStorage : CookieStorage {
        var value: String? = null
        override fun load() = value
        override fun save(json: String) { value = json }
    }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        jar = PersistentCookieJar(storage)
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().cookieJar(jar).addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val authApproved = """{"user":{"id":"u1","email":"host@test.dev","role":"user","approvedAt":"2026-10-01T10:00:00.000Z","phone":null,"needsPhone":false},"accountId":"a1"}"""
    private val authPending = """{"user":{"id":"u2","email":"new@test.dev","role":"company_admin","approvedAt":null,"phone":null,"needsPhone":false},"accountId":"a2"}"""

    @Test fun `auth me parsing, approval and stages`() = runBlocking {
        server.enqueue(json(authApproved)); server.enqueue(json(authPending))
        val ok = api.me().user
        assertTrue(ok.isApproved); assertEquals(Stage.READY, stageFor(ok))
        val pending = api.me().user
        assertFalse(pending.isApproved); assertEquals(Stage.PENDING, stageFor(pending)); assertNull(pending.approvedAt)
        assertEquals(Stage.READY, stageFor(AuthUser("s", "root@x.dev", "superadmin", approvedAt = null)))
        assertEquals(Stage.NEEDS_PHONE, stageFor(ok.copy(needsPhone = true)))
    }

    @Test fun `verify-code sends email and code, stores the session cookie, later calls send it with Origin`() = runBlocking {
        server.enqueue(json(authApproved).addHeader("Set-Cookie", "sb_session=tok123; Path=/; Expires=Fri, 01 Jan 2100 00:00:00 GMT; HttpOnly; SameSite=Lax"))
        server.enqueue(json("[]"))
        api.verifyCode("host@test.dev", "123456")
        val verify = server.takeRequest()
        assertEquals("POST", verify.method); assertEquals("/api/auth/verify-code", verify.path)
        assertEquals("""{"email":"host@test.dev","code":"123456"}""", verify.body.readUtf8())
        assertEquals("application/json; charset=utf-8".lowercase(), verify.getHeader("Content-Type")!!.lowercase())
        assertTrue(jar.hasCookie("sb_session"))

        api.platformConnections()
        val next = server.takeRequest()
        assertEquals("/api/platform-connections", next.path)
        assertEquals("sb_session=tok123", next.getHeader("Cookie"))
        assertEquals(originOf(server.url("/")), next.getHeader("Origin"))
        assertTrue("origin has no trailing slash or path", !next.getHeader("Origin")!!.endsWith("/"))
    }

    @Test fun `cookie survives a restart through storage and expired cookies are dropped`() = runBlocking {
        server.enqueue(json(authApproved).addHeader("Set-Cookie", "sb_session=tokABC; Path=/; Expires=Fri, 01 Jan 2100 00:00:00 GMT; HttpOnly"))
        api.verifyCode("a@b.dev", "000000")
        val reborn = PersistentCookieJar(storage) // new process, same storage
        assertTrue(reborn.hasCookie("sb_session"))
        val expiredStore = MemoryStorage()
        val j = PersistentCookieJar(expiredStore)
        j.saveFromResponse(server.url("/"), listOf(okhttp3.Cookie.Builder().name("sb_session").value("old").hostOnlyDomain(server.hostName).path("/").expiresAt(1000L).build()))
        assertFalse(j.hasCookie("sb_session"))
    }

    @Test fun `logout clears via server cookie reset`() = runBlocking {
        server.enqueue(json(authApproved).addHeader("Set-Cookie", "sb_session=tok; Path=/; Expires=Fri, 01 Jan 2100 00:00:00 GMT; HttpOnly"))
        api.verifyCode("a@b.dev", "000000")
        server.enqueue(MockResponse().setResponseCode(204).addHeader("Set-Cookie", "sb_session=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        api.logout()
        assertFalse(jar.hasCookie("sb_session"))
    }

    @Test fun `wrong code surfaces the backend message`() = runBlocking {
        server.enqueue(json("""{"statusCode":400,"message":"Incorrect code.","error":"Bad Request"}""", 400))
        try { api.verifyCode("a@b.dev", "111111"); fail() } catch (e: ApiException) {
            assertEquals(400, e.status); assertEquals("Incorrect code.", e.message)
        }
    }

    @Test fun `validation error arrays and pending approval 403`() = runBlocking {
        assertEquals("email must be an email, code must be 6", ApiClient.errorMessage(400, """{"statusCode":400,"message":["email must be an email","code must be 6"]}"""))
        assertEquals("Request failed (502)", ApiClient.errorMessage(502, "<html>bad gateway</html>"))
        server.enqueue(json("""{"statusCode":403,"message":"Your account is pending Superadmin approval.","error":"Forbidden"}""", 403))
        try { api.platformConnections(); fail() } catch (e: ApiException) { assertTrue(e.isPendingApproval); assertFalse(e.isUnauthorized) }
        server.enqueue(json("""{"statusCode":401,"message":"Session expired or revoked","error":"Unauthorized"}""", 401))
        try { api.me(); fail() } catch (e: ApiException) { assertTrue(e.isUnauthorized) }
    }

    @Test fun `platform connections`() = runBlocking {
        server.enqueue(json("""[
          {"id":"3f2c1b7e-1a2b-4c3d-8e9f-0a1b2c3d4e5f","platform":"youtube","externalAccountId":"UC123","label":"My Channel","isActive":true,"liveSlug":"my-channel","liveLinkUrl":"https://streambird.app/live/my-channel"},
          {"id":"4f2c1b7e-1a2b-4c3d-8e9f-0a1b2c3d4e5f","platform":"twitch","externalAccountId":"manual-1","label":"Twitch main","isActive":true,"liveSlug":null,"liveLinkUrl":null}]"""))
        val c = api.platformConnections()
        assertEquals(listOf("youtube", "twitch"), c.map { it.platform })
        assertNull(c[1].liveLinkUrl); assertEquals("My Channel", c[0].label)
    }

    @Test fun `plans me limits`() = runBlocking {
        server.enqueue(json("""{"planKey":"free","planName":"Free","includedHours":10,"graceMultiplier":1.2,"maxDestinations":1,"maxGuests":1,"maxResolution":"hd","canRecord":false,"canChromaKey":false,"canLowerThird":false,"assetStorageMb":0,"watermark":true,"maxSessionHours":2,"dayPass":null,"planExpiresAt":null,"planExpired":false}"""))
        val l = api.myLimits()
        assertEquals(1, l.maxDestinations); assertEquals("hd", l.maxResolution); assertEquals(0, l.assetStorageMb); assertTrue(l.watermark)
        assertEquals(2.0, l.maxSessionHours!!, 0.0)
    }

    @Test fun `brand kits and 403 for plans without a library`() = runBlocking {
        server.enqueue(json("""[{"id":"k1","accountId":"a1","name":"Main","logoAssetId":"5a5a5a5a-0000-4000-8000-000000000001","logoPosition":"right","logoSize":120,"wallpaperAssetId":null,"wallpaperDim":0,"lowerThirdStyle":"accent","isDefault":true,"createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z","connectionIds":["c1"]}]"""))
        val k = api.brandKits().single()
        assertEquals("right", k.logoPosition); assertEquals(120, k.logoSize); assertTrue(k.isDefault); assertEquals(listOf("c1"), k.connectionIds)
        server.enqueue(json("""{"statusCode":403,"message":"Brand kits are on the paid plans. Your logo and wallpaper stay saved in this browser on the Free plan.","error":"Forbidden"}""", 403))
        try { api.brandKits(); fail() } catch (e: ApiException) { assertEquals(403, e.status); assertTrue(e.message.startsWith("Brand kits are on the paid plans")) }
    }

    @Test fun `asset file returns raw bytes`() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))).setHeader("Content-Type", "image/png"))
        assertEquals(listOf<Byte>(1, 2, 3), api.assetFile("abc").toList())
        assertEquals("/api/assets/abc/file", server.takeRequest().path)
    }

    @Test fun `create stream request and response`() = runBlocking {
        server.enqueue(json("""{"id":"s1","title":"Mobile test","status":"live","ingestUrl":null,"streamKey":null,"whipUrl":"https://mtx.streambird.app/live-s1/whip","visibility":"public","orientation":"portrait","studioSessionId":"ss1","description":null,"scheduledAt":null,"isScheduledEvent":false,"timezone":null,"expectedDurationMinutes":null,"cancelledAt":null,
          "destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","viewerCount":null,"watchUrl":"https://youtu.be/x","errorMessage":null}]}"""))
        val s = api.createStream(CreateStreamRequest("Mobile test", "public", "portrait", listOf("c1")))
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/api/streams", req.path)
        assertEquals("""{"title":"Mobile test","visibility":"public","orientation":"portrait","destinationConnectionIds":["c1"]}""", req.body.readUtf8())
        assertEquals("https://mtx.streambird.app/live-s1/whip", s.whipUrl); assertEquals("live", s.status)
        assertEquals("portrait", s.orientation); assertEquals("https://youtu.be/x", s.destinations.single().watchUrl)
    }

    @Test fun `stream status and end`() = runBlocking {
        server.enqueue(json("""{"status":"live","relayInputStatus":"connected","destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","platformStatus":"testing","viewerCount":3,"watchUrl":null,"errorMessage":null}]}"""))
        val st = api.streamStatus("s1")
        assertEquals("connected", st.relayInputStatus); assertEquals("testing", st.destinations.single().platformStatus)
        server.takeRequest()
        server.enqueue(json("""{"id":"s1","title":"t","status":"ended","whipUrl":null,"orientation":"portrait","destinations":[]}"""))
        assertEquals("ended", api.endStream("s1").status)
        val end = server.takeRequest()
        assertEquals("POST", end.method); assertEquals("/api/streams/s1/end", end.path)
        assertEquals(originOf(server.url("/")), end.getHeader("Origin"))
    }

    @Test fun `turn credentials accept a single url or an array`() = runBlocking {
        server.enqueue(json("""{"iceServers":[{"urls":"stun:stun.l.google.com:19302"},{"urls":["turn:t.example.com:3478?transport=udp","turns:t.example.com:5349"],"username":"u","credential":"c"}]}"""))
        val ice = api.turnCredentials().iceServers
        assertEquals(listOf("stun:stun.l.google.com:19302"), ice[0].urlList())
        assertEquals(2, ice[1].urlList().size); assertEquals("u", ice[1].username)
    }

    @Test fun `base url normalisation and Origin form`() {
        assertEquals("https://streambird.app", normalizeBaseUrl("streambird.app/"))
        assertEquals("https://streambird.app", normalizeBaseUrl("https://streambird.app/api"))
        assertEquals("http://10.0.2.2:3000", normalizeBaseUrl("http://10.0.2.2:3000/"))
        assertEquals("https://stg.example.com:8443", normalizeBaseUrl(" https://stg.example.com:8443 "))
    }

    @Test fun `origin is only sent to the configured server`() = runBlocking {
        val other = MockWebServer().also { it.start() }
        try {
            other.enqueue(MockResponse().setResponseCode(200))
            val http = OkHttpClient.Builder().addInterceptor(OriginInterceptor { server.url("/").toString().trimEnd('/') }).build()
            http.newCall(okhttp3.Request.Builder().url(other.url("/x")).build()).execute().close()
            assertNull(other.takeRequest().getHeader("Origin"))
        } finally { other.shutdown() }
    }

    @Test fun `landscape stream request carries orientation landscape`() = runBlocking {
        server.enqueue(json("""{"id":"s2","title":"L","status":"live","whipUrl":"https://mtx/x/whip","orientation":"landscape","destinations":[]}"""))
        val s = api.createStream(CreateStreamRequest("L", "unlisted", StreamOrientation.LANDSCAPE.wire, listOf("c1", "c2")))
        assertEquals("""{"title":"L","visibility":"unlisted","orientation":"landscape","destinationConnectionIds":["c1","c2"]}""", server.takeRequest().body.readUtf8())
        assertEquals("landscape", s.orientation)
    }

    @Test fun `default valued fields are still serialised (encodeDefaults regression)`() {
        val body = ApiJson.encodeToString(CreateStreamRequest(title = "t", destinationConnectionIds = listOf("c1")))
        assertTrue(body, body.contains(""""visibility":"public"""") && body.contains(""""orientation":"portrait""""))
        assertTrue(ApiJson.encodeToString(CreateStreamRequest("t", orientation = "landscape", destinationConnectionIds = listOf("c"))).contains(""""orientation":"landscape""""))
    }
}
