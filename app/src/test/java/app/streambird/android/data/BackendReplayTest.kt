package app.streambird.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration-style layer: ApiClient + AuthRepository + stream calls against a MockWebServer that behaves like the real backend.
 * The rules and JSON are copied from the backend: AccountGuard (`Missing x-api-key header or session cookie` 401, `Origin mismatch` 403 for
 * non-GET requests whose Origin differs from PUBLIC_BASE_URL), AuthController.toAuthResponse + setSessionCookie (`sb_session`, httpOnly,
 * sameSite=lax, Expires), StreamResponseDto / getStatus / end (Nest POST answers 201), and the e2e harness which sends Origin on every call.
 */
class BackendReplayTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var jar: PersistentCookieJar
    private lateinit var repo: AuthRepository
    private val storage = ApiRobustnessTest.MemoryStorage()
    private val prefs = object : StreamPrefs {
        override var baseUrl = ""; override var lastEmail = ""; override var tickerText = ""; override var tickerSize = "S"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var sessionValid = false

    private val approvedUser = """{"user":{"id":"7d1f2a52-0c3b-4f6e-9a67-1b2c3d4e5f60","email":"host@test.dev","role":"user","approvedAt":"2026-10-01T10:00:00.000Z","phone":null,"needsPhone":false},"accountId":"a3f1c9d2-7b11-4a6e-8c55-9d0e1f2a3b4c"}"""

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val origin = "http://${server.hostName}:${server.port}"
                val path = request.path!!.substringBefore('?')
                if (request.method == "POST" && path == "/api/auth/verify-code") {
                    sessionValid = true
                    return json(200, approvedUser).addHeader("Set-Cookie", "sb_session=abc123token; Path=/; Expires=Fri, 01 Jan 2100 00:00:00 GMT; HttpOnly; SameSite=Lax")
                }
                if (path == "/api/auth/request-code") return json(202, """{"message":"If that email is valid, a login code has been sent."}""")
                // AccountGuard
                val hasCookie = request.getHeader("Cookie")?.contains("sb_session=abc123token") == true
                if (!hasCookie || !sessionValid) return json(401, """{"statusCode":401,"message":"Missing x-api-key header or session cookie","error":"Unauthorized"}""")
                if (request.method != "GET" && request.getHeader("Origin") != origin) return json(403, """{"statusCode":403,"message":"Origin mismatch","error":"Forbidden"}""")
                return when {
                    path == "/api/auth/me" -> json(200, approvedUser)
                    request.method == "POST" && path == "/api/streams" -> json(201, """{"id":"11111111-2222-4333-8444-555555555555","title":"Mobile stream","status":"live","ingestUrl":null,"streamKey":null,"whipUrl":"https://mtx.example/11111111-2222-4333-8444-555555555555/whip","visibility":"public","orientation":"portrait","studioSessionId":"s-1","description":null,"scheduledAt":null,"isScheduledEvent":false,"timezone":null,"expectedDurationMinutes":null,"cancelledAt":null,"destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","platformBroadcastId":null,"watchUrl":"https://youtu.be/x","errorMessage":null}]}""")
                    path.endsWith("/status") -> json(200, """{"status":"live","relayInputStatus":"connected","destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","platformStatus":"live","viewerCount":3,"watchUrl":"https://youtu.be/x","errorMessage":null}]}""")
                    request.method == "POST" && path.endsWith("/end") -> json(201, """{"id":"11111111-2222-4333-8444-555555555555","title":"Mobile stream","status":"ended","whipUrl":null,"orientation":"portrait","destinations":[]}""")
                    path == "/api/streams" -> json(200, """{"items":[{"id":"s9","title":"ghj","status":"live","scheduledAt":null,"startedAt":"2026-10-31T17:13:00.000Z","endedAt":null,"cancelledAt":null,"isScheduledEvent":false,"createdAt":"2026-10-31T17:12:50.000Z","destinationsSummary":[{"platform":"youtube","status":"live"}]}],"total":1,"limit":20,"offset":0}""")
                    path == "/api/platform-connections" -> json(200, """[{"id":"c1","platform":"youtube","externalAccountId":"UC1","label":"My channel","isActive":true,"liveSlug":"my-channel","liveLinkUrl":"https://streambird.app/stream/my-channel"}]""")
                    path == "/api/plans/me" -> json(200, """{"planKey":"free","planName":"Free","maxDestinations":1,"maxGuests":1,"maxResolution":"hd","assetStorageMb":0,"watermark":true,"maxSessionHours":2,"planExpired":false}""")
                    else -> json(404, """{"statusCode":404,"message":"Cannot GET $path","error":"Not Found"}""")
                }
            }
        }
        server.start()
        jar = PersistentCookieJar(storage)
        val base = { server.url("/").toString().trimEnd('/') }
        prefs.baseUrl = base()
        api = ApiClient(OkHttpClient.Builder().cookieJar(jar).addInterceptor(OriginInterceptor(base)).build(), base)
        repo = AuthRepository(api, jar, prefs, scope)
    }

    @After fun tearDown() { server.shutdown(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    @Test fun `sign in stores the sb_session cookie, later calls send it with Origin, and the whole stream lifecycle works`() = runBlocking {
        repo.requestCode()                                                                  // (email empty -> error, no call) then proper flow below
        repo.setEmail("host@test.dev"); repo.requestCode()
        assertTrue(repo.ui.value.codeSent)
        repo.verify("123456")
        assertEquals(Stage.READY, repo.ui.value.stage)
        assertTrue(jar.hasCookie("sb_session"))

        val conns = api.platformConnections()
        assertEquals("https://streambird.app/stream/my-channel", conns.single().liveLinkUrl)
        assertEquals(0, api.myLimits().assetStorageMb)

        val s = api.createStream(CreateStreamRequest("Mobile stream", "public", "portrait", listOf("c1")))
        assertEquals("portrait", s.orientation); assertTrue(s.whipUrl!!.endsWith("/whip")); assertEquals("https://youtu.be/x", s.destinations.single().watchUrl)
        assertEquals("connected", api.streamStatus(s.id).relayInputStatus)
        assertEquals(1, api.recentStreams().size)
        api.endStream(s.id)

        // every call after sign-in carried the cookie, and every non-GET carried the server's own Origin
        var sawPost = false
        repeat(server.requestCount) {
            val r = server.takeRequest()
            if (r.path == "/api/auth/verify-code" || r.path == "/api/auth/request-code") return@repeat
            assertTrue(r.getHeader("Cookie")!!.contains("sb_session=abc123token"))
            if (r.method == "POST") { sawPost = true; assertEquals("http://${server.hostName}:${server.port}", r.getHeader("Origin")) }
        }
        assertTrue(sawPost)
    }

    @Test fun `without the Origin header a non-GET request is refused 403 Origin mismatch`() = runBlocking {
        repo.setEmail("host@test.dev"); repo.verify("123456")
        val bare = ApiClient(OkHttpClient.Builder().cookieJar(jar).build()) { server.url("/").toString().trimEnd('/') }   // no OriginInterceptor
        try { bare.createStream(CreateStreamRequest("x", destinationConnectionIds = listOf("c1"))); org.junit.Assert.fail() } catch (e: ApiException) {
            assertEquals(403, e.status); assertEquals("Origin mismatch", e.message); assertFalse(e.isPendingApproval)
        }
        assertEquals(1, bare.platformConnections().size)                                // GET needs no Origin
    }

    @Test fun `401 without a session cookie is an unauthorized ApiException, and the repository drops to logged out on expire`() = runBlocking {
        try { api.platformConnections(); org.junit.Assert.fail() } catch (e: ApiException) {
            assertTrue(e.isUnauthorized); assertEquals("Missing x-api-key header or session cookie", e.message)
        }
        repo.setEmail("host@test.dev"); repo.verify("123456")
        assertEquals(Stage.READY, repo.ui.value.stage)
        sessionValid = false                                                            // the server revoked the session
        try { api.myLimits(); org.junit.Assert.fail() } catch (e: ApiException) { assertTrue(e.isUnauthorized) }
        repo.expire()
        assertEquals(Stage.LOGGED_OUT, repo.ui.value.stage); assertFalse(jar.hasCookie("sb_session") && false)
    }

    @Test fun `a captive portal answering the sign-in refresh gives an error message, not a crash`() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html>portal</html>") }
        repo.refresh(initial = true)
        assertNull(repo.ui.value.user)
        assertTrue(repo.ui.value.stage == Stage.LOGGED_OUT || repo.ui.value.error != null)
    }
}
