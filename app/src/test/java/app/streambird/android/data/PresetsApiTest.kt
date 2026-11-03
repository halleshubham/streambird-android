package app.streambird.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
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

/** The presets endpoints (GET/POST /presets, PATCH/DELETE /presets/:id, POST /presets/:id/use) and their tolerant models. */
class PresetsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val full = """{"id":"p1","name":"Friday show","accountId":"a","createdAt":"2026-10-01T00:00:00.000Z","lastUsedAt":"2026-10-05T10:00:00.000Z","config":{"orientation":"landscape","destinationIds":["c1","c2"],"brandKitId":"k1","quality":"hd","tickerEnabled":true,"tickerText":"Hello","somethingNew":1}}"""

    @Test fun `list parses presets and ignores unknown fields`() = runBlocking {
        server.enqueue(json("[$full]"))
        val p = api.listPresets().single()
        assertEquals("p1", p.id); assertEquals("Friday show", p.name); assertEquals("2026-10-05T10:00:00.000Z", p.lastUsedAt)
        assertEquals(PresetConfig("landscape", listOf("c1", "c2"), "k1", "hd", true, "Hello"), p.config)
        val r = server.takeRequest()
        assertEquals("GET", r.method); assertEquals("/api/presets", r.path)
        assertEquals(server.url("/").toString().trimEnd('/'), r.getHeader("Origin"))
    }

    @Test fun `missing and null fields become null or defaults`() = runBlocking {
        server.enqueue(json("""[{"id":"p2","name":"Bare"},{"id":"p3","name":null,"config":null,"lastUsedAt":null},{"id":"p4","name":"X","config":{"brandKitId":null,"destinationIds":null}}]"""))
        val l = api.listPresets()
        assertEquals(3, l.size)
        assertEquals(PresetConfig(), l[0].config); assertNull(l[0].lastUsedAt)
        assertEquals("", l[1].name); assertEquals(PresetConfig(), l[1].config)
        assertNull(l[2].config.destinationIds); assertNull(l[2].config.brandKitId)
    }

    @Test fun `rows without an id and non-object rows are skipped`() = runBlocking {
        server.enqueue(json("""[{"name":"no id"},"text",7,{"id":"ok","name":"Fine"},{"id":"bad","config":{"destinationIds":"not-a-list"}}]"""))
        assertEquals(listOf("ok"), api.listPresets().map { it.id })
    }

    @Test fun `an empty list, an items wrapper and a non-array answer all work`() = runBlocking {
        server.enqueue(json("[]")); assertTrue(api.listPresets().isEmpty())
        server.enqueue(json("""{"items":[{"id":"w","name":"W"}]}""")); assertEquals(listOf("w"), api.listPresets().map { it.id })
        server.enqueue(json("""{"message":"hi"}""")); assertTrue(api.listPresets().isEmpty())
    }

    @Test fun `404 is an ApiException with status 404`() = runBlocking {
        server.enqueue(json("""{"statusCode":404,"message":"Cannot GET /api/presets","error":"Not Found"}""", 404))
        try { api.listPresets(); fail() } catch (e: ApiException) { assertEquals(404, e.status); assertTrue(e.message.contains("Cannot GET")) }
    }

    @Test fun `html answer from a captive portal becomes the unexpected-answer error`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        try { api.listPresets(); fail() } catch (e: ApiException) { assertEquals(-1, e.status) }
    }

    @Test fun `create sends only the known config keys, nulls left out`() = runBlocking {
        server.enqueue(json(full, 201))
        val saved = api.createPreset("  Friday show  ", PresetConfig(orientation = "portrait", destinationIds = listOf("c1"), quality = "fhd", tickerEnabled = false))
        assertEquals("p1", saved.id)
        val r = server.takeRequest()
        assertEquals("POST", r.method); assertEquals("/api/presets", r.path)
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals(setOf("name", "config"), body.keys)
        assertEquals("Friday show", body["name"]!!.jsonPrimitive.content)
        val cfg = body["config"]!!.jsonObject
        assertEquals(setOf("orientation", "destinationIds", "quality", "tickerEnabled"), cfg.keys)
        assertEquals("portrait", cfg["orientation"]!!.jsonPrimitive.content)
        assertEquals(listOf("c1"), cfg["destinationIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(cfg["tickerEnabled"]!!.jsonPrimitive.boolean)
    }

    @Test fun `the full config uses exactly the allowed keys and caps the ticker at 200`() {
        val cfg = presetConfigJson(PresetConfig("landscape", listOf("a"), "k", "hd", true, "x".repeat(300)))
        assertEquals(setOf("orientation", "destinationIds", "brandKitId", "quality", "tickerEnabled", "tickerText"), cfg.keys)
        assertEquals(200, cfg["tickerText"]!!.jsonPrimitive.content.length)
        assertEquals(JsonObject(emptyMap()), presetConfigJson(PresetConfig()))
        assertEquals(JsonArray(emptyList()), presetConfigJson(PresetConfig(destinationIds = emptyList()))["destinationIds"])
    }

    @Test fun `name is trimmed and capped at 80`() {
        assertEquals(80, presetCreateJson("n".repeat(120), PresetConfig())["name"]!!.jsonPrimitive.content.length)
    }

    @Test fun `patch sends only what is given`() = runBlocking {
        server.enqueue(json(full)); api.updatePreset("p1", "New name", null)
        var r = server.takeRequest()
        assertEquals("PATCH", r.method); assertEquals("/api/presets/p1", r.path)
        assertEquals(setOf("name"), Json.parseToJsonElement(r.body.readUtf8()).jsonObject.keys)
        server.enqueue(json(full)); api.updatePreset("p1", null, PresetConfig(quality = "hd"))
        r = server.takeRequest()
        assertEquals(setOf("config"), Json.parseToJsonElement(r.body.readUtf8()).jsonObject.keys)
    }

    @Test fun `delete and use`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204)); api.deletePreset("p1")
        var r = server.takeRequest(); assertEquals("DELETE", r.method); assertEquals("/api/presets/p1", r.path)
        server.enqueue(json(full)); val used = api.usePreset("p1")
        r = server.takeRequest(); assertEquals("POST", r.method); assertEquals("/api/presets/p1/use", r.path)
        assertEquals("p1", used.id)
    }

    @Test fun `validation error message is shown as the server sent it`() = runBlocking {
        server.enqueue(json("""{"statusCode":400,"message":["config.property foo should not exist"],"error":"Bad Request"}""", 400))
        try { api.createPreset("x", PresetConfig()); fail() } catch (e: ApiException) { assertEquals(400, e.status); assertTrue(e.message.contains("should not exist")) }
    }
}
