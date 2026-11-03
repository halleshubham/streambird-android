package app.streambird.android.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** PATCH /auth/me/phone and the in-app phone step (NEEDS_PHONE -> PENDING / READY) behind it. */
class PhoneStepTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private lateinit var repo: AuthRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

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
        repo = AuthRepository(api, PersistentCookieJar(Storage()), Prefs(), scope)
    }

    @After fun tearDown() { scope.cancel(); runCatching { server.shutdown() } }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun user(approved: Boolean, phone: String? = "+919876543210", needsPhone: Boolean = false): String {
        val approvedAt = if (approved) "\"2026-01-01T00:00:00.000Z\"" else "null"
        val ph = phone?.let { "\"$it\"" } ?: "null"
        return """{"user":{"id":"u1","email":"n@test.dev","role":"user","approvedAt":$approvedAt,"phone":$ph,"needsPhone":$needsPhone},"accountId":"a1"}"""
    }

    /** Puts the repository into NEEDS_PHONE the way a brand-new sign-up gets there: via /auth/me. */
    private fun startAtNeedsPhone() = runBlocking {
        server.enqueue(json(user(approved = false, phone = null, needsPhone = true)))
        repo.refresh()
        assertEquals(Stage.NEEDS_PHONE, repo.ui.value.stage)
        server.takeRequest()
    }

    @Test fun `request is a PATCH with only the phone field`() = runBlocking {
        server.enqueue(json(user(approved = true)))
        val r = api.setPhone("98765 43210")
        assertEquals("a1", r.accountId)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method); assertEquals("/api/auth/me/phone", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("phone"), body.keys); assertEquals("98765 43210", body["phone"]!!.jsonPrimitive.content)
    }

    @Test fun `a new unapproved account that needs a number is sent to the phone step before pending`() {
        assertEquals(Stage.NEEDS_PHONE, stageFor(AuthUser("u", "a@b.dev", "user", approvedAt = null, needsPhone = true)))
        assertEquals(Stage.PENDING, stageFor(AuthUser("u", "a@b.dev", "user", approvedAt = null, phone = "+919876543210")))
        assertEquals(Stage.READY, stageFor(AuthUser("s", "a@b.dev", "superadmin", approvedAt = null, needsPhone = false)))
    }

    @Test fun `saving moves NEEDS_PHONE to PENDING when the account is not approved yet`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json(user(approved = false)))
        repo.savePhone("  +91 98765 43210 ")
        val ui = repo.ui.value
        assertEquals(Stage.PENDING, ui.stage); assertEquals("+919876543210", ui.user?.phone)
        assertFalse(ui.busy); assertNull(ui.error)
        assertEquals("+91 98765 43210", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["phone"]!!.jsonPrimitive.content)
    }

    @Test fun `saving moves NEEDS_PHONE to READY when the account is approved`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json(user(approved = true)))
        repo.savePhone("9876543210")
        assertEquals(Stage.READY, repo.ui.value.stage); assertFalse(repo.ui.value.busy)
    }

    @Test fun `busy while the request runs then cleared on success`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json(user(approved = true)))
        val seen = mutableListOf<Boolean>()
        val job = scope.launch { repo.ui.collect { seen += it.busy } }
        repo.savePhone("9876543210")
        job.cancel()
        assertTrue("busy was set during the call: $seen", seen.contains(true))
        assertFalse(repo.ui.value.busy)
    }

    @Test fun `400 shows the server text inline and stays on the step`() = runBlocking {
        startAtNeedsPhone()
        val msg = "Enter a valid mobile number, with the country code if it is not an Indian number."
        server.enqueue(json("""{"statusCode":400,"message":"$msg","error":"Bad Request"}""", 400))
        repo.savePhone("123")
        val ui = repo.ui.value
        assertEquals(Stage.NEEDS_PHONE, ui.stage); assertEquals(msg, ui.error); assertFalse(ui.busy)
    }

    @Test fun `a new attempt clears the old error`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json("""{"message":"Enter a valid mobile number"}""", 400))
        repo.savePhone("1")
        assertEquals("Enter a valid mobile number", repo.ui.value.error)
        server.enqueue(json(user(approved = true)))
        repo.savePhone("9876543210")
        assertNull(repo.ui.value.error); assertEquals(Stage.READY, repo.ui.value.stage)
    }

    @Test fun `blank input is rejected without calling the server`() = runBlocking {
        startAtNeedsPhone()
        val before = server.requestCount
        repo.savePhone("   ")
        assertEquals(PhoneText.EMPTY, repo.ui.value.error); assertEquals(before, server.requestCount)
    }

    @Test fun `401 ends the session`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json("""{"message":"Unauthorized"}""", 401))
        repo.savePhone("9876543210")
        val ui = repo.ui.value
        assertEquals(Stage.LOGGED_OUT, ui.stage); assertNull(ui.user); assertFalse(ui.busy)
    }

    @Test fun `429 maps to the wait message`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json("""{"message":"ThrottlerException: Too Many Requests"}""", 429))
        repo.savePhone("9876543210")
        assertEquals(PhoneText.RATE_LIMITED, repo.ui.value.error); assertEquals(Stage.NEEDS_PHONE, repo.ui.value.stage); assertFalse(repo.ui.value.busy)
    }

    @Test fun `network failure reports it and unlocks the form`() = runBlocking {
        startAtNeedsPhone()
        server.shutdown()
        repo.savePhone("9876543210")
        assertTrue(repo.ui.value.error!!.startsWith("Cannot reach the server"))
        assertFalse(repo.ui.value.busy); assertEquals(Stage.NEEDS_PHONE, repo.ui.value.stage)
    }

    @Test fun `plan or price words in a server error are neutralised`() = runBlocking {
        startAtNeedsPhone()
        server.enqueue(json("""{"message":"Upgrade your plan"}""", 400))
        repo.savePhone("9876543210")
        assertEquals(ApiClient.NOT_IN_PLAN, repo.ui.value.error)
    }
}
