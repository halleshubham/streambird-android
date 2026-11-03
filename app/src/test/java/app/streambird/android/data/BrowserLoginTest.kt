package app.streambird.android.data

import app.streambird.android.auth.BrowserSignIn
import app.streambird.android.auth.GoogleSignInText
import app.streambird.android.auth.InMemoryVerifierStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The "Sign in with Google in your browser" handoff: POST /auth/google/app/redeem and the AuthRepository state machine behind the deep link. */
class BrowserLoginTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var repo: AuthRepository
    private val store = InMemoryVerifierStore()
    private var now = 1_000_000L

    private class Prefs : StreamPrefs {
        override var baseUrl = "https://streambird.app"
        override var lastEmail = ""
        override var tickerText = ""
        override var tickerSize = "S"
    }

    private class Storage : CookieStorage {
        override fun load(): String? = null
        override fun save(json: String) {}
        override suspend fun flush() {}
    }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
        repo = AuthRepository(api, PersistentCookieJar(Storage()), Prefs(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), store)
        repo.clock = { now }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun user(approved: Boolean = true, needsPhone: Boolean = false): String {
        val approvedAt = if (approved) "\"2026-01-01T00:00:00.000Z\"" else "null"
        return """{"user":{"id":"u1","email":"g@test.dev","role":"user","approvedAt":$approvedAt,"phone":null,"needsPhone":$needsPhone},"accountId":"a1"}"""
    }

    @Test fun `begin stores a verifier and returns the start url with its challenge`() {
        val url = repo.beginBrowserSignIn()!!
        val saved = store.load()!!
        assertEquals(now, saved.savedAtMs); assertEquals(43, saved.verifier.length)
        assertEquals("https://streambird.app/api/auth/google/app?challenge=" + BrowserSignIn.challengeFor(saved.verifier), url)
        assertFalse(url.contains(saved.verifier)) // the verifier itself never goes into the URL
    }

    @Test fun `redeem body has only code and verifier and the answer parses like verify-code`() = runBlocking {
        repo.beginBrowserSignIn()
        val verifier = store.load()!!.verifier
        server.enqueue(json(user()))
        repo.handleAuthLink("streambird://auth?code=the-code")
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/api/auth/google/app/redeem", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("code", "verifier"), body.keys)
        assertEquals("the-code", body["code"]!!.jsonPrimitive.content); assertEquals(verifier, body["verifier"]!!.jsonPrimitive.content)
        assertEquals(Stage.READY, repo.ui.value.stage); assertEquals("g@test.dev", repo.ui.value.user?.email)
        assertNull(store.load()); assertFalse(repo.ui.value.busy); assertEquals(GoogleStage.IDLE, repo.ui.value.google)
    }

    @Test fun `stage transitions after redeem`() = runBlocking {
        for ((needsPhone, approved, expected) in listOf(Triple(true, false, Stage.NEEDS_PHONE), Triple(false, false, Stage.PENDING), Triple(false, true, Stage.READY))) {
            repo.expire(); repo.beginBrowserSignIn()
            server.enqueue(json(user(approved = approved, needsPhone = needsPhone)))
            repo.handleAuthLink("streambird://auth?code=c")
            assertEquals(expected, repo.ui.value.stage)
        }
    }

    @Test fun `no pending verifier shows a friendly message and does not call the server`() = runBlocking {
        repo.handleAuthLink("streambird://auth?code=c")
        assertEquals(BrowserSignIn.Text.NO_PENDING, repo.ui.value.error)
        assertEquals(0, server.requestCount); assertEquals(Stage.LOGGED_OUT, repo.ui.value.stage)
    }

    @Test fun `a stale verifier is refused and cleared`() = runBlocking {
        repo.beginBrowserSignIn()
        now += BrowserSignIn.VERIFIER_TTL_MS + 1
        repo.handleAuthLink("streambird://auth?code=c")
        assertEquals(BrowserSignIn.Text.STALE, repo.ui.value.error)
        assertEquals(0, server.requestCount); assertNull(store.load())
    }

    @Test fun `error link shows the failure note and clears the verifier`() = runBlocking {
        repo.beginBrowserSignIn()
        repo.handleAuthLink("streambird://auth?error=google_failed")
        assertEquals(GoogleSignInText.DID_NOT_FINISH, repo.ui.value.googleNote)
        assertNull(store.load()); assertEquals(0, server.requestCount); assertFalse(repo.ui.value.busy)
    }

    @Test fun `foreign or malformed links`() = runBlocking {
        repo.beginBrowserSignIn()
        repo.handleAuthLink("https://evil.example/?code=c"); repo.handleAuthLink(null); repo.handleAuthLink("streambird://other?code=c")
        assertNotNull(store.load()); assertEquals(0, server.requestCount); assertNull(repo.ui.value.error); assertNull(repo.ui.value.googleNote)
        repo.handleAuthLink("streambird://auth?nothing=1")
        assertEquals(GoogleSignInText.DID_NOT_FINISH, repo.ui.value.googleNote); assertEquals(0, server.requestCount)
    }

    @Test fun `server errors map and the verifier is cleared after every attempt`() = runBlocking {
        val bad = "Google sign-in could not be completed. Try again or use the email code."
        val cases = listOf(400 to bad, 401 to bad, 404 to GoogleSignInText.NOT_ON_SERVER, 429 to GoogleSignInText.RATE_LIMITED)
        for ((code, msg) in cases) {
            repo.beginBrowserSignIn()
            server.enqueue(json("""{"message":"x"}""", code))
            repo.handleAuthLink("streambird://auth?code=c")
            val ui = repo.ui.value
            assertEquals(msg, ui.error); assertEquals(GoogleStage.ERROR, ui.google); assertFalse(ui.busy); assertNull(store.load())
            assertEquals(Stage.LOGGED_OUT, ui.stage)
        }
    }

    @Test fun `network failure reports it`() = runBlocking {
        repo.beginBrowserSignIn()
        server.shutdown()
        repo.handleAuthLink("streambird://auth?code=c")
        assertEquals(true, repo.ui.value.error!!.startsWith("Cannot reach the server")); assertFalse(repo.ui.value.busy); assertNull(store.load())
    }

    @Test fun `a link while already signed in is ignored`() = runBlocking {
        server.enqueue(json(user()))
        repo.beginBrowserSignIn(); repo.handleAuthLink("streambird://auth?code=c")
        assertEquals(Stage.READY, repo.ui.value.stage)
        repo.beginBrowserSignIn(); repo.handleAuthLink("streambird://auth?code=c2")
        assertEquals(1, server.requestCount); assertNull(store.load())
    }
}
