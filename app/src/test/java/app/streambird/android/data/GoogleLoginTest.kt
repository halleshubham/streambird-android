package app.streambird.android.data

import app.streambird.android.auth.GoogleSignInText
import app.streambird.android.auth.GoogleTokenProvider
import app.streambird.android.auth.GoogleTokenResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** POST /auth/google/mobile and the AuthRepository state machine behind "Sign in with Google", with a fake Credential Manager. */
class GoogleLoginTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var repo: AuthRepository

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
        repo = AuthRepository(api, PersistentCookieJar(Storage()), Prefs(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun user(approved: Boolean = true): String {
        val approvedAt = if (approved) "\"2026-01-01T00:00:00.000Z\"" else "null"
        return """{"user":{"id":"u1","email":"g@test.dev","role":"user","approvedAt":$approvedAt,"phone":null,"needsPhone":false},"accountId":"a1"}"""
    }

    @Test fun `request body carries only the idToken and the answer parses like verify-code`() = runBlocking {
        server.enqueue(json(user()))
        val r = api.googleMobile("tok.en.value")
        assertEquals("g@test.dev", r.user.email); assertEquals("a1", r.accountId)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/api/auth/google/mobile", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("idToken"), body.keys)
    }

    @Test fun `success goes to READY like the email login and hands the nonce to the provider`() = runBlocking {
        server.enqueue(json(user()))
        var seen: String? = null
        repo.signInWithGoogle({ n -> seen = n; GoogleTokenResult.Token("t") }, nonce = { "nonce-1" })
        assertEquals("nonce-1", seen)
        val ui = repo.ui.value
        assertEquals(Stage.READY, ui.stage); assertEquals("g@test.dev", ui.user?.email)
        assertEquals(GoogleStage.IDLE, ui.google); assertFalse(ui.busy); assertNull(ui.error)
    }

    @Test fun `an unapproved account lands on the pending screen`() = runBlocking {
        server.enqueue(json(user(approved = false)))
        repo.signInWithGoogle({ GoogleTokenResult.Token("t") })
        assertEquals(Stage.PENDING, repo.ui.value.stage)
    }

    @Test fun `the provider runs in the LAUNCHING state with the form locked`() = runBlocking {
        server.enqueue(json(user()))
        val seen = mutableListOf<GoogleStage>()
        repo.signInWithGoogle({ seen += repo.ui.value.google; assertTrue(repo.ui.value.busy); GoogleTokenResult.Token("t") })
        assertEquals(listOf(GoogleStage.LAUNCHING), seen)
    }

    @Test fun `cancelled is silent`() = runBlocking {
        repo.signInWithGoogle({ GoogleTokenResult.Cancelled })
        val ui = repo.ui.value
        assertEquals(GoogleStage.IDLE, ui.google); assertNull(ui.error); assertFalse(ui.busy); assertEquals(0, server.requestCount)
    }

    @Test fun `no account, unavailable and failed show their messages and never call the server`() = runBlocking {
        repo.signInWithGoogle({ GoogleTokenResult.NoAccount })
        assertEquals(GoogleSignInText.NO_ACCOUNT, repo.ui.value.error); assertEquals(GoogleStage.ERROR, repo.ui.value.google)
        repo.signInWithGoogle({ GoogleTokenResult.Unavailable })
        assertEquals(GoogleSignInText.UNAVAILABLE, repo.ui.value.error)
        repo.signInWithGoogle({ GoogleTokenResult.Failed("x") })
        assertEquals(GoogleSignInText.FAILED, repo.ui.value.error)
        repo.signInWithGoogle(GoogleTokenProvider { error("boom") })
        assertEquals(GoogleSignInText.FAILED, repo.ui.value.error); assertFalse(repo.ui.value.busy)
        assertEquals(0, server.requestCount)
    }

    @Test fun `server 404 401 429 400 map to their messages`() = runBlocking {
        val cases = listOf(404 to GoogleSignInText.NOT_ON_SERVER, 401 to GoogleSignInText.REJECTED, 429 to GoogleSignInText.RATE_LIMITED, 400 to GoogleSignInText.BAD_REQUEST)
        for ((code, msg) in cases) {
            server.enqueue(json("""{"message":"x"}""", code))
            repo.signInWithGoogle({ GoogleTokenResult.Token("t") })
            val ui = repo.ui.value
            assertEquals(msg, ui.error); assertEquals(GoogleStage.ERROR, ui.google); assertFalse(ui.busy)
        }
    }

    @Test fun `network failure reports it`() = runBlocking {
        server.shutdown()
        repo.signInWithGoogle({ GoogleTokenResult.Token("t") })
        assertTrue(repo.ui.value.error!!.startsWith("Cannot reach the server"))
        assertFalse(repo.ui.value.busy)
    }
}
