package app.streambird.android.data

import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.preset.ApiPresets
import app.streambird.android.preset.PresetController
import app.streambird.android.preset.SetupChoices
import app.streambird.android.preset.choicesToConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The presets feature against a MockWebServer that behaves like the REAL server: the same routes, the same ValidationPipe rules (whitelist +
 * forbidNonWhitelisted, v4 UUIDs, 80 / 200 character caps, the 20-preset limit, destinations that must belong to the account), the same JSON the Nest app
 * produced when it was run against Postgres (bare array, `lastUsedAt: null`, ISO dates, AllExceptionsFilter error envelope with the message one level down).
 * Source: src/presets (controller, service, dto), src/app.setup.ts, src/common/filters/http-exception.filter.ts, test/e2e/presets.e2e-spec.ts of the server repo.
 */
class PresetsServerReplayTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient
    private val logs = mutableListOf<String>()
    private val rows = mutableListOf<JsonObject>()

    // The account: three active connections and one kit (all v4 UUIDs, like Postgres gen_random_uuid()).
    private val conns = List(3) { UUID.randomUUID().toString() }
    private val kit = UUID.randomUUID().toString()

    // Failure injection.
    @Volatile private var listFailure: (() -> MockResponse)? = null
    @Volatile private var routeMissing = false
    private val seen = mutableListOf<RecordedRequest>()

    private fun envelope(status: Int, error: String, message: Any) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(
        buildJsonObject {
            put("statusCode", status)
            put("message", buildJsonObject {
                put("message", if (message is List<*>) buildJsonArray { message.forEach { add(JsonPrimitive(it.toString())) } } else JsonPrimitive(message.toString()))
                put("error", error); put("statusCode", status)
            })
            put("timestamp", "2026-11-07T18:31:35.366Z")
        }.toString(),
    )

    private val v4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

    /** class-validator messages for POST/PATCH bodies, in the server's wording; empty = valid. */
    private fun validate(body: JsonObject, requireAll: Boolean): List<String> {
        val errs = mutableListOf<String>()
        (body.keys - setOf("name", "config")).forEach { errs += "property $it should not exist" }
        val name = body["name"]
        if (name == null) { if (requireAll) errs += "name must be shorter than or equal to 80 characters" }
        else if (name !is JsonPrimitive || !name.isString) errs += "name must be a string"
        else if (name.content.isEmpty()) errs += "name must be longer than or equal to 1 characters"
        else if (name.content.length > 80) errs += "name must be shorter than or equal to 80 characters"
        val cfg = body["config"]
        if (cfg == null) { if (requireAll) errs += "config must be an object" }
        else if (cfg !is JsonObject) errs += "config must be an object"
        else {
            (cfg.keys - setOf("orientation", "destinationIds", "brandKitId", "quality", "tickerEnabled", "tickerText")).forEach { errs += "config.property $it should not exist" }
            cfg["orientation"]?.let { if (it !is JsonPrimitive || it.content !in setOf("landscape", "portrait")) errs += "config.orientation must be one of the following values: landscape, portrait" }
            cfg["quality"]?.let { if (it !is JsonPrimitive || it.content !in setOf("sd", "hd", "fhd", "qhd")) errs += "config.quality must be one of the following values: sd, hd, fhd, qhd" }
            cfg["tickerEnabled"]?.let { if (it !is JsonPrimitive || it.isString) errs += "config.tickerEnabled must be a boolean value" }
            cfg["tickerText"]?.let { if (it !is JsonPrimitive || !it.isString) errs += "config.tickerText must be a string" else if (it.content.length > 200) errs += "config.tickerText must be shorter than or equal to 200 characters" }
            cfg["brandKitId"]?.let { if (it !is JsonNull && (it !is JsonPrimitive || !v4.matches(it.content))) errs += "config.brandKitId must be a UUID" }
            cfg["destinationIds"]?.let {
                if (it !is JsonArray) errs += "config.destinationIds must be an array"
                else if (it.any { d -> d !is JsonPrimitive || !v4.matches(d.content) }) errs += "config.each value in destinationIds must be a UUID"
            }
        }
        return errs
    }

    private fun ownership(cfg: JsonObject?): MockResponse? {
        cfg?.get("destinationIds")?.jsonArray?.let { ids -> if (ids.any { it.jsonPrimitive.content !in conns }) return envelope(422, "Unprocessable Entity", "One or more destinationIds do not belong to this account") }
        (cfg?.get("brandKitId") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { if (it.content != kit) return envelope(422, "Unprocessable Entity", "brandKitId does not belong to this account") }
        return null
    }

    private fun view(id: String, name: String, cfg: JsonObject, lastUsed: String?) = buildJsonObject {
        put("id", id); put("name", name); put("config", cfg); put("lastUsedAt", lastUsed?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val origin = "http://${server.hostName}:${server.port}"
                val path = request.path!!.substringBefore('?')
                if (request.getHeader("Cookie")?.contains("sb_session=abc") != true) return envelope(401, "Unauthorized", "Missing x-api-key header or session cookie")
                if (request.method != "GET" && request.getHeader("Origin") != origin) return envelope(403, "Forbidden", "Origin mismatch")
                if (routeMissing && path.startsWith("/api/presets")) return envelope(404, "Not Found", "Cannot ${request.method} $path")
                val id = path.removePrefix("/api/presets/").takeIf { path.startsWith("/api/presets/") }?.substringBefore('/')
                return when {
                    request.method == "GET" && path == "/api/presets" -> listFailure?.invoke() ?: ok(200, JsonArray(rows.sortedWith(compareByDescending<JsonObject> { (it["lastUsedAt"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content ?: "" })))
                    request.method == "POST" && path == "/api/presets" -> create(request)
                    request.method == "PATCH" && id != null -> update(id, request)
                    request.method == "DELETE" && id != null -> if (rows.removeAll { it["id"]!!.jsonPrimitive.content == id }) MockResponse().setResponseCode(204) else envelope(404, "Not Found", "Preset not found")
                    request.method == "POST" && id != null && path.endsWith("/use") -> use(path.removeSuffix("/use").substringAfterLast('/'))
                    else -> envelope(404, "Not Found", "Cannot ${request.method} $path")
                }
            }
        }
        server.start()
        val base = { server.url("/").toString().trimEnd('/') }
        val http = OkHttpClient.Builder()
            .addInterceptor(OriginInterceptor(base))
            .addInterceptor { c -> c.proceed(c.request().newBuilder().header("Cookie", "sb_session=abc123").build()) }
            .build()
        api = ApiClient(http, base)
    }

    @After fun tearDown() { server.shutdown() }

    private fun ok(code: Int, body: kotlinx.serialization.json.JsonElement) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json; charset=utf-8").setBody(body.toString())

    private fun create(r: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        val errs = validate(body, requireAll = true)
        if (errs.isNotEmpty()) return envelope(400, "Bad Request", errs)
        if (rows.size >= 20) return envelope(409, "Conflict", "You can keep up to 20 presets. Delete one to save another.")
        ownership(body["config"]!!.jsonObject)?.let { return it }
        val v = view(UUID.randomUUID().toString(), body["name"]!!.jsonPrimitive.content.trim(), body["config"]!!.jsonObject, null)
        rows += v
        return ok(201, v)
    }

    private fun update(id: String, r: RecordedRequest): MockResponse {
        if (!v4.matches(id)) return envelope(400, "Bad Request", "Validation failed (uuid is expected)")
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        val errs = validate(body, requireAll = false)
        if (errs.isNotEmpty()) return envelope(400, "Bad Request", errs)
        val i = rows.indexOfFirst { it["id"]!!.jsonPrimitive.content == id }
        if (i < 0) return envelope(404, "Not Found", "Preset not found")
        body["config"]?.jsonObject?.let { ownership(it)?.let { e -> return e } }
        val old = rows[i]
        val v = view(id, body["name"]?.jsonPrimitive?.content?.trim() ?: old["name"]!!.jsonPrimitive.content, body["config"]?.jsonObject ?: old["config"]!!.jsonObject, (old["lastUsedAt"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)
        rows[i] = v
        return ok(200, v)
    }

    private fun use(id: String): MockResponse {
        val i = rows.indexOfFirst { it["id"]!!.jsonPrimitive.content == id }
        if (i < 0) return envelope(404, "Not Found", "Preset not found")
        val old = rows[i]
        val v = view(id, old["name"]!!.jsonPrimitive.content, old["config"]!!.jsonObject, "2026-11-07T18:40:00.000Z")
        rows[i] = v
        val withExtras = JsonObject(v + mapOf("effectiveConfig" to old["config"]!!, "warnings" to JsonArray(emptyList())))
        return ok(200, withExtras)
    }

    private fun controller() = PresetController(ApiPresets(api)) { logs += it }

    private fun choices(vararg ids: String, tier: StreamTier = StreamTier.P720, kitId: String? = null, ticker: String = "") =
        SetupChoices(ids.toCollection(LinkedHashSet()), tier, StreamOrientation.PORTRAIT, kitId, ticker)

    // ---------------------------------------------------------------------------------------------------------------------------------

    @Test fun `save a new preset, it is on the server, in the list, confirmed, and survives a restart`() = runBlocking {
        val c = controller(); c.refresh()
        assertTrue(c.state.value.available); assertTrue(c.state.value.items.isEmpty())

        val saved = c.save(null, "  Friday show ", choicesToConfig(choices(conns[0], conns[1], kitId = kit, ticker = "Welcome to the show")))
        assertNotNull(c.state.value.error, saved) // a failure would carry the reason in state.error
        assertEquals("Friday show", saved!!.name)
        assertEquals(listOf(conns[0], conns[1]), saved.config.destinationIds)
        assertEquals("hd", saved.config.quality); assertEquals("portrait", saved.config.orientation); assertEquals(kit, saved.config.brandKitId)
        assertEquals(true, saved.config.tickerEnabled); assertEquals("Welcome to the show", saved.config.tickerText)
        assertEquals("Preset saved", c.state.value.notice); assertNull(c.state.value.error); assertFalse(c.state.value.busy)
        assertEquals(listOf(saved.id), c.state.value.items.map { it.id })
        assertEquals(1, rows.size)

        val restarted = controller(); restarted.refresh()
        assertEquals(listOf(saved.id), restarted.state.value.items.map { it.id })
        assertEquals("Friday show", restarted.state.value.items.single().name)

        // What was sent: exactly the contract, with the cookie and the server's own Origin.
        val post = synchronized(seen) { seen.first { it.method == "POST" } }
        assertEquals("/api/presets", post.path)
        assertEquals("http://${server.hostName}:${server.port}", post.getHeader("Origin"))
        assertTrue(post.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun `update replaces the config, rename works, use moves it first, delete removes it`() = runBlocking {
        val c = controller(); c.refresh()
        val a = c.save(null, "A", choicesToConfig(choices(conns[0])))!!
        val b = c.save(null, "B", choicesToConfig(choices(conns[1])))!!
        val up = c.save(a.id, "A2", choicesToConfig(choices(conns[0], conns[2], tier = StreamTier.P1080)))
        assertNotNull(c.state.value.error, up)
        assertEquals("Preset updated", c.state.value.notice)
        assertEquals("fhd", up!!.config.quality); assertEquals(listOf(conns[0], conns[2]), up.config.destinationIds)

        c.markUsed(b.id)
        assertEquals(b.id, c.state.value.items.first().id)
        assertNotNull(c.state.value.items.first().lastUsedAt)

        assertTrue(c.delete(a.id))
        assertEquals(listOf(b.id), c.state.value.items.map { it.id })
        assertEquals(listOf(b.id), rows.map { it["id"]!!.jsonPrimitive.content })
    }

    @Test fun `a 400 from the server reaches the user in the server's words`() = runBlocking {
        val c = controller(); c.refresh()
        assertNull(c.save(null, "Bad", PresetConfig(destinationIds = listOf("not-a-uuid"))))
        val msg = c.state.value.error.orEmpty()
        assertTrue(msg, msg.contains("each value in destinationIds must be a UUID"))
        assertTrue(logs.any { it.contains("HTTP 400") && it.contains("destinationIds") })
    }

    @Test fun `409 at twenty presets and 422 for a foreign channel or kit are explained`() = runBlocking {
        val c = controller(); c.refresh()
        assertNull(c.save(null, "Foreign", PresetConfig(destinationIds = listOf(UUID.randomUUID().toString()))))
        assertTrue(c.state.value.error!!.contains("do not belong to this account"))
        assertNull(c.save(null, "Kit", PresetConfig(brandKitId = UUID.randomUUID().toString())))
        assertTrue(c.state.value.error!!.contains("brandKitId does not belong"))
        repeat(20) { assertNotNull(c.save(null, "p$it", PresetConfig())) }
        assertNull(c.save(null, "one too many", PresetConfig()))
        assertTrue(c.state.value.error!!, c.state.value.error!!.contains("up to 20 presets"))
        assertEquals(20, c.state.value.items.size); assertTrue(c.state.value.available)
    }

    @Test fun `the real server accepts what the app really builds from a full setup`() = runBlocking {
        val c = controller(); c.refresh()
        // Every field set, ticker at its cap, a name at its cap, 1080p, landscape, automatic kit (omitted).
        val cfg = choicesToConfig(SetupChoices(linkedSetOf(conns[0], conns[1], conns[2]), StreamTier.P1080, StreamOrientation.LANDSCAPE, null, "x".repeat(500)))
        val saved = c.save(null, "n".repeat(120), cfg)
        assertNotNull(c.state.value.error, saved)
        assertEquals(80, saved!!.name.length); assertEquals(200, saved.config.tickerText!!.length)
        assertNull(saved.config.brandKitId)
    }

    @Test fun `a failed first load does not hide Save as preset or New preset, and saving still works`() = runBlocking {
        listFailure = { MockResponse().setResponseCode(500).setBody("""{"statusCode":500,"message":"Internal server error","timestamp":"x"}""") }
        val c = controller(); c.refresh()
        assertTrue("section stays", c.state.value.available)
        assertTrue(c.state.value.loadError!!.contains("Could not load your presets"))
        assertTrue(logs.any { it.startsWith("presets: list failed HTTP 500") })

        val saved = c.save(null, "Still saves", PresetConfig(quality = "hd"))
        assertNotNull(c.state.value.error, saved)
        assertNull(c.state.value.loadError)

        listFailure = null; c.refresh()
        assertEquals(listOf("Still saves"), c.state.value.items.map { it.name })
    }

    @Test fun `only a missing endpoint hides the section, a page that is not JSON does not`() = runBlocking {
        routeMissing = true
        val old = controller(); old.refresh()
        assertFalse(old.state.value.available); assertTrue(old.state.value.loaded)

        routeMissing = false
        listFailure = { MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>captive portal</html>") }
        val portal = controller(); portal.refresh()
        assertTrue(portal.state.value.available); assertNotNull(portal.state.value.loadError)
    }

    @Test fun `an empty list is an available section with no cards, not a hidden one`() = runBlocking {
        val c = controller(); c.refresh()
        assertTrue(c.state.value.available); assertTrue(c.state.value.loaded); assertNull(c.state.value.loadError)
    }

    @Test fun `a preset made elsewhere appears on the next refresh and after the next save`() = runBlocking {
        val c = controller(); c.refresh()
        rows += view(UUID.randomUUID().toString(), "Made on the website", buildJsonObject { put("quality", "hd") }, null)
        c.save(null, "From the phone", PresetConfig(quality = "sd"))
        assertEquals(setOf("Made on the website", "From the phone"), c.state.value.items.map { it.name }.toSet())
    }

    @Test fun `updating a preset that was deleted elsewhere says so and drops it`() = runBlocking {
        val c = controller(); c.refresh()
        val p = c.save(null, "Gone", PresetConfig())!!
        rows.clear()
        assertNull(c.save(p.id, "Gone 2", PresetConfig()))
        assertTrue(c.state.value.error!!.contains("no longer exists"))
        assertTrue(c.state.value.items.none { it.id == p.id })
        assertTrue(c.state.value.available)
    }

    @Test fun `deleting something already deleted is a success`() = runBlocking {
        val c = controller(); c.refresh()
        val p = c.save(null, "Twice", PresetConfig())!!
        rows.clear()
        assertTrue(c.delete(p.id)); assertTrue(c.state.value.items.isEmpty())
    }

    @Test fun `an expired session is explained and not mistaken for a missing feature`() = runBlocking {
        val noCookie = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor { server.url("/").toString().trimEnd('/') }).build()) { server.url("/").toString().trimEnd('/') }
        val c = PresetController(ApiPresets(noCookie)) { logs += it }
        c.refresh()
        assertTrue(c.state.value.available)
        assertTrue(c.state.value.loadError!!.contains("Missing x-api-key"))
        assertNull(c.save(null, "x", PresetConfig()))
        assertTrue(c.state.value.error!!.contains("session expired"))
    }
}
