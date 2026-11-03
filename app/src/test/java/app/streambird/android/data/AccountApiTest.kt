package app.streambird.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** GET /account/delete/preview, POST /account/delete/request, DELETE /account and the tolerant preview model. */
class AccountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `preview parses every contract field and ignores unknown ones`() = runBlocking {
        server.enqueue(json("""{"isOnlyAdmin":true,"memberCount":3,"activeSubscription":{"plan":"Pro","nextRenewal":"2026-12-01T00:00:00.000Z","extra":1},"liveStream":true,"connectionsCount":2,"assetsCount":7,"somethingNew":"x"}"""))
        val p = api.deletionPreview()
        assertEquals(DeletionPreview(true, 3, ActiveSubscription("Pro", "2026-12-01T00:00:00.000Z"), true, 2, 7), p)
        assertTrue(p.companyDeletionNeedsConfirm)
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertEquals("/api/account/delete/preview", r.path)
        assertEquals(server.url("/").toString().trimEnd('/'), r.getHeader("Origin"))
    }

    @Test fun `missing, null and wrongly typed fields fall back to harmless defaults`() = runBlocking {
        server.enqueue(json("{}"))
        assertEquals(DeletionPreview(), api.deletionPreview())
        server.enqueue(json("""{"isOnlyAdmin":null,"memberCount":"many","activeSubscription":null,"liveStream":"yes","connectionsCount":-4,"assetsCount":null}"""))
        val p = api.deletionPreview()
        assertEquals(DeletionPreview(isOnlyAdmin = false, memberCount = 1, activeSubscription = null, liveStream = false, connectionsCount = 0, assetsCount = 0), p)
        assertFalse(p.companyDeletionNeedsConfirm)
        // a subscription without a plan or renewal date is still a subscription (autopay is cancelled)
        server.enqueue(json("""{"activeSubscription":{"nextRenewal":null}}"""))
        assertEquals(ActiveSubscription("", null), api.deletionPreview().activeSubscription)
        server.enqueue(json("""{"activeSubscription":"Pro"}"""))
        assertNull(api.deletionPreview().activeSubscription)
    }

    @Test fun `the company confirmation is only needed for the only admin of a company with other members`() {
        assertFalse(DeletionPreview(isOnlyAdmin = true, memberCount = 1).companyDeletionNeedsConfirm)
        assertFalse(DeletionPreview(isOnlyAdmin = false, memberCount = 5).companyDeletionNeedsConfirm)
        assertTrue(DeletionPreview(isOnlyAdmin = true, memberCount = 2).companyDeletionNeedsConfirm)
    }

    @Test fun `a non-object or html answer is the unexpected-answer error, 404 keeps its status`() = runBlocking {
        server.enqueue(json("[1,2]"))
        try { api.deletionPreview(); fail() } catch (e: ApiException) { assertEquals(-1, e.status) }
        server.enqueue(MockResponse().setBody("<html>portal</html>"))
        try { api.deletionPreview(); fail() } catch (e: ApiException) { assertEquals(-1, e.status) }
        server.enqueue(json("""{"statusCode":404,"message":"Cannot GET /api/account/delete/preview","error":"Not Found"}""", 404))
        try { api.deletionPreview(); fail() } catch (e: ApiException) { assertEquals(404, e.status) }
    }

    @Test fun `request code is a POST with no body fields and accepts 204`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.requestDeletionCode()
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/api/account/delete/request", r.path)
        assertEquals("", r.body.readUtf8())
    }

    @Test fun `delete sends DELETE with exactly the code, and the confirmation only when ticked`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204)); server.enqueue(MockResponse().setResponseCode(204))
        api.deleteAccount(" 123456 ", false)
        api.deleteAccount("654321", true)
        val a = server.takeRequest(); val b = server.takeRequest()
        assertEquals("DELETE", a.method); assertEquals("/api/account", a.path)
        assertTrue(a.getHeader("Content-Type")!!.startsWith("application/json"))
        val ja = ApiJson.parseToJsonElement(a.body.readUtf8()).jsonObject
        assertEquals(setOf("code"), ja.keys); assertEquals("123456", ja["code"]!!.jsonPrimitive.content)
        val jb: JsonObject = ApiJson.parseToJsonElement(b.body.readUtf8()).jsonObject
        assertEquals(setOf("code", "confirmCompanyDeletion"), jb.keys); assertTrue(jb["confirmCompanyDeletion"]!!.jsonPrimitive.boolean)
    }

    @Test fun `409 400 401 and 403 keep their status and the server's message`() = runBlocking {
        for ((code, msg) in listOf(409 to "Confirm company deletion", 400 to "Incorrect code.", 401 to "This code is invalid or has expired.", 403 to "Superadmin accounts cannot be deleted")) {
            server.enqueue(json("""{"statusCode":$code,"message":"$msg","error":"x"}""", code))
            try { api.deleteAccount("123456", false); fail("expected $code") } catch (e: ApiException) { assertEquals(code, e.status); assertEquals(msg, e.message) }
        }
    }

    @Test fun `the web path is the delete-account page of the server`() {
        assertEquals("https://streambird.app/delete-account", deleteAccountWebUrl("https://streambird.app"))
        assertEquals("https://streambird.app/delete-account", deleteAccountWebUrl("https://streambird.app/"))
    }

    @Test fun `server texts that point at plans, prices or upgrades are replaced by a neutral sentence`() {
        val neutral = "This is not included in this account's plan."
        listOf("Brand kits are on the paid plans. Your logo stays saved on the Free plan.", "Upgrade to run them.", "See pricing", "Open billing", "Please subscribe").forEach {
            assertEquals(it, neutral, ApiClient.neutralizePlanSteering(it))
        }
        assertEquals("Incorrect code.", ApiClient.neutralizePlanSteering("Incorrect code."))
        assertEquals("This file is used by the brand kit \"Main\".", ApiClient.neutralizePlanSteering("This file is used by the brand kit \"Main\"."))
        assertEquals(neutral, ApiClient.errorMessage(403, """{"statusCode":403,"message":"Your own ads are on the paid plans. Upgrade to run them."}"""))
    }
}
