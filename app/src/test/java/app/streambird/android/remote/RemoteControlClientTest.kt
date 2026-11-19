package app.streambird.android.remote

import app.streambird.android.data.ApiClient
import app.streambird.android.data.OriginInterceptor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** The long-poll loop against a real HTTP server (MockWebServer) with a fake clock: nothing sleeps for real. */
class RemoteControlClientTest {
    /** Routes by path. Poll answers come from [polls]; when it is empty the request is held open (a long poll) until the client cancels. */
    private class Script : Dispatcher() {
        val polls = ConcurrentLinkedQueue<MockResponse>()
        val pollRequests = CopyOnWriteArrayList<RecordedRequest>()
        val acks = CopyOnWriteArrayList<RecordedRequest>()
        val states = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var ackStatus = 200
        @Volatile var stateStatus = 200
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                request.method == "GET" && path.contains("/remote-control?") -> { pollRequests.add(request); polls.poll() ?: MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                request.method == "POST" && path.endsWith("/remote-control/ack") -> { acks.add(request); MockResponse().setResponseCode(ackStatus) }
                request.method == "PUT" && path.endsWith("/remote-control/state") -> { states.add(request); MockResponse().setResponseCode(stateStatus) }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private lateinit var server: MockWebServer
    private lateinit var script: Script
    private lateinit var api: ApiClient
    private lateinit var scope: CoroutineScope
    @Volatile private var now = 5_000_000L
    private val pollSleeps = CopyOnWriteArrayList<Long>()
    private val logs = CopyOnWriteArrayList<String>()
    private val controls = RemoteApplierTest.FakeControls()
    private val host = HostChangeTracker { now }
    private val clients = mutableListOf<RemoteControlClient>()
    @Volatile private var stateCalls = 0
    @Volatile private var advanceAll = false

    @Before fun setUp() {
        script = Script()
        server = MockWebServer().also { it.dispatcher = script; it.start() }
        val base = { server.url("/").toString().trimEnd('/') }
        api = ApiClient(OkHttpClient.Builder().addInterceptor(OriginInterceptor(base)).build(), base)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        clients.forEach { it.stop() }
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
    private fun json(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
    private fun status(code: Int) = MockResponse().setResponseCode(code)
    private fun cmd(seq: Int, type: String = "ads.shrink", payload: String = """{"on":true}""") =
        """{"seq":$seq,"id":"id$seq","type":"$type","payload":$payload,"at":"${iso(System.currentTimeMillis())}"}"""
    private fun commands(vararg c: String) = json("""{"commands":[${c.joinToString(",")}],"now":"${iso(System.currentTimeMillis())}"}""")

    private fun client(random: () -> Double = { 0.5 }, state: () -> JsonObject? = { stateCalls++; buildJsonObject { put("ok", true) } }): RemoteControlClient {
        val dispatcher = RemoteDispatcher(RemoteApplier(controls, host), { controls.isLive }, RateLimiter(20, 10_000) { now }, { System.currentTimeMillis() }, { logs.add(it) })
        val sleep: suspend (Long) -> Unit = { ms ->
            if (currentCoroutineContext()[CoroutineName]?.name == "remote-poll") pollSleeps.add(ms)
            if (advanceAll || currentCoroutineContext()[CoroutineName]?.name == "remote-poll") now += ms   // state / ack sleeps must not disturb the poll's gap arithmetic
            yield()
        }
        return RemoteControlClient(HttpRemoteControlApi(api), dispatcher, scope, state, { now }, sleep, random) { logs.add(it) }.also { clients.add(it) }
    }

    private fun await(what: String, ms: Long = 5000, cond: () -> Boolean) = runBlocking {
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("timed out waiting for $what; logs=$logs sleeps=$pollSleeps") }
    }

    @Test fun `empty answers loop with the same after and a minimum gap, sending the session headers`() {
        repeat(3) { script.polls.add(status(204)) }
        val c = client(); c.start("s1")
        await("4 polls") { script.pollRequests.size >= 4 }
        assertEquals(listOf("/api/streams/s1/remote-control?after=0&wait=25"), script.pollRequests.take(4).map { it.path }.distinct())
        val origin = server.url("/").toString().trimEnd('/')
        assertEquals(origin, script.pollRequests[0].getHeader("Origin"))
        assertEquals(listOf(1000L, 1000L, 1000L), pollSleeps.take(3))
        assertTrue(c.running)
    }

    @Test fun `commands are applied in order, acked, and the next poll asks after the last seq`() {
        script.polls.add(commands(cmd(2, "ticker.set", """{"text":"hi","on":true}"""), cmd(1)))
        script.polls.add(status(204))
        val c = client(); c.start("s1")
        await("acks") { script.acks.size >= 2 && script.pollRequests.size >= 2 }
        assertEquals("/api/streams/s1/remote-control?after=2&wait=25", script.pollRequests[1].path)
        assertEquals(listOf("shrink true", "ticker 'hi'"), controls.calls)
        val bodies = script.acks.map { Json.parseToJsonElement(it.body.readUtf8()) as JsonObject }.sortedBy { it["id"].toString() }
        assertEquals(listOf("id1", "id2"), bodies.map { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(bodies.all { it.keys == setOf("id", "ok", "appliedAt") })
        assertEquals("application/json", script.acks[0].getHeader("Content-Type")?.substringBefore(";"))
    }

    @Test fun `a failed command is acked with ok false and a short error`() {
        script.polls.add(commands(cmd(1, "stream.stop", "{}")))
        val c = client(); c.start("s1")
        await("ack") { script.acks.isNotEmpty() }
        val b = Json.parseToJsonElement(script.acks[0].body.readUtf8()) as JsonObject
        assertEquals("false", b["ok"].toString()); assertEquals("\"command not allowed\"", b["error"].toString())
        assertEquals(setOf("id", "ok", "error", "appliedAt"), b.keys)
        assertTrue(controls.calls.isEmpty())
    }

    @Test fun `ack is retried once and the loop keeps going when it keeps failing`() {
        script.ackStatus = 500
        script.polls.add(commands(cmd(1))); script.polls.add(status(204))
        val c = client(); c.start("s1")
        await("2 ack attempts") { script.acks.size >= 2 }
        await("next poll") { script.pollRequests.size >= 2 }
        runBlocking { delay(100) }
        assertEquals(2, script.acks.size)
        assertTrue(c.running)
    }

    @Test fun `state is sent right after start and after an applied command with a growing rev and only the controllable object`() {
        script.polls.add(commands(cmd(1)))
        val c = client(); c.start("s1")
        await("2 states") { script.states.size >= 2 }
        val b = script.states.map { Json.parseToJsonElement(it.body.readUtf8()) as JsonObject }
        assertEquals(setOf("rev", "state"), b[0].keys)
        assertEquals("1", b[0]["rev"].toString()); assertEquals("2", b[1]["rev"].toString())
        assertEquals("""{"ok":true}""", b[0]["state"].toString())
        assertEquals("/api/streams/s1/remote-control/state", script.states[0].path)
    }

    @Test fun `state uploads are debounced and at most one per second`() {
        script.polls.add(commands(cmd(1)))
        advanceAll = true
        val stamps = CopyOnWriteArrayList<Long>()
        val c = client(state = { stamps.add(now); buildJsonObject { put("n", stamps.size) } })
        c.start("s1")
        repeat(20) { c.stateChanged() }
        await("states") { script.states.size >= 1 }
        runBlocking { delay(200) }
        assertTrue("sent ${script.states.size}", script.states.size <= 4)         // 22 change signals collapse into a few uploads
        stamps.zipWithNext().forEach { (a, b) -> assertTrue("gap ${b - a}", b - a >= 1000) }
    }

    @Test fun `a state failure is ignored and the poll loop is unaffected`() {
        script.stateStatus = 500
        script.polls.add(status(204)); script.polls.add(status(204))
        val c = client(); c.start("s1")
        await("states + polls") { script.states.isNotEmpty() && script.pollRequests.size >= 3 }
        assertTrue(c.running)
    }

    @Test fun `5xx and 429 back off exponentially with jitter, capped at 30 seconds, and keep going`() {
        script.polls.add(status(500)); script.polls.add(status(429)); script.polls.add(status(502))
        repeat(4) { script.polls.add(status(503)) }
        val c = client(random = { 0.5 }); c.start("s1")                                   // 0.75 + 0.5 * 0.5 = 1.0 x the base
        await("8 polls") { script.pollRequests.size >= 8 }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), pollSleeps.take(7))
        assertTrue(c.running)
    }

    @Test fun `network errors are retried with backoff and the loop recovers`() {
        repeat(3) { script.polls.add(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        script.polls.add(status(204)); script.polls.add(status(204))
        val c = client(); c.start("s1")
        await("recovered") { script.pollRequests.size >= 6 && pollSleeps.size >= 3 }
        assertTrue(c.running)
        assertTrue(pollSleeps.toString(), pollSleeps.take(3).all { it in 1000L..30000L })
        assertEquals(1000L, pollSleeps[0])
    }

    @Test fun `jitter stays inside 75 to 125 percent`() {
        script.polls.add(status(500)); script.polls.add(status(500)); script.polls.add(status(500)); script.polls.add(status(500))
        val lo = client(random = { 0.0 }); lo.start("s1")
        await("lo") { script.pollRequests.size >= 4 }
        assertEquals(listOf(750L, 1500L, 3000L), pollSleeps.take(3))
        lo.stop(); pollSleeps.clear(); script.pollRequests.clear()
        repeat(3) { script.polls.add(status(500)) }
        val hi = client(random = { 1.0 }); hi.start("s1")
        await("hi") { script.pollRequests.size >= 3 }
        assertEquals(listOf(1250L, 2500L), pollSleeps.take(2))
    }

    @Test fun `a success resets the backoff`() {
        script.polls.add(status(500)); script.polls.add(status(500)); script.polls.add(status(204)); script.polls.add(status(500))
        val c = client(); c.start("s1")
        await("polls") { script.pollRequests.size >= 5 }
        assertEquals(listOf(1000L, 2000L, 1000L, 1000L), pollSleeps.take(4))
    }

    @Test fun `401 403 404 405 501 409 and 400 stop the loop for good without another request and without throwing`() {
        for (code in listOf(401, 403, 404, 405, 501, 409, 400, 410)) {
            script.polls.clear(); script.pollRequests.clear(); logs.clear()
            script.polls.add(status(code))
            val c = client(); c.start("s$code")
            await("stop on $code") { !c.running }
            runBlocking { delay(60) }
            assertEquals("code $code", 1, script.pollRequests.size)
            assertTrue("code $code", logs.any { it.contains("HTTP $code") })
            c.stop()
        }
    }

    @Test fun `an old server (404) means the feature is off and nothing else is called, then the next go live tries again`() {
        script.polls.add(status(404))
        val c = client(); c.start("s1")
        await("off") { !c.running }
        runBlocking { delay(1500) }                                                      // the state upload may fire once; nothing else
        assertEquals(1, script.pollRequests.size); assertTrue(script.acks.isEmpty())
        script.polls.add(status(204))
        c.start("s2")                                                                    // the next go-live
        await("polls again") { script.pollRequests.size >= 2 }
        assertEquals("/api/streams/s2/remote-control?after=0&wait=25", script.pollRequests[1].path)
        assertTrue(c.running)
    }

    @Test fun `a restart forgets the previous stream and its sequence`() {
        script.polls.add(commands(cmd(5))); script.polls.add(status(204))
        val c = client(); c.start("s1")
        await("seq 5") { script.pollRequests.size >= 2 }
        assertEquals("/api/streams/s1/remote-control?after=5&wait=25", script.pollRequests[1].path)
        c.stop()
        assertFalse(c.running)
    }

    @Test fun `stop cancels the open request and nothing more is sent`() {
        val c = client(); c.start("s1")                                                  // no scripted answer: the poll is held open like a real long poll
        await("poll open") { script.pollRequests.size == 1 }
        c.stop()
        assertFalse(c.running)
        runBlocking { delay(300) }
        assertEquals(1, script.pollRequests.size)
        c.stateChanged()                                                                 // after stop: harmless
        runBlocking { delay(100) }
        assertEquals(1, script.pollRequests.size)
    }

    @Test fun `garbage answers do not crash and do not spin`() {
        script.polls.add(json("<html>captive portal</html>")); script.polls.add(json("""{"commands":"x"}""")); script.polls.add(json("[]"))
        val c = client(); c.start("s1")
        await("polls") { script.pollRequests.size >= 4 }
        assertTrue(c.running)
        assertEquals(listOf(1000L, 1000L, 1000L), pollSleeps.take(3))
    }

    @Test fun `unknown json fields in the answer are tolerated`() {
        script.polls.add(json("""{"commands":[{"seq":1,"id":"u1","type":"ads.shrink","payload":{"on":false,"later":{"x":1}},"at":"${iso(System.currentTimeMillis())}","audit":"zzz"}],"now":"${iso(System.currentTimeMillis())}","more":[1]}"""))
        val c = client(); c.start("s1")
        await("ack") { script.acks.isNotEmpty() }
        assertEquals(listOf("shrink false"), controls.calls)
    }

    @Test fun `the client only talks to the configured server and with the app session headers`() {
        script.polls.add(commands(cmd(1)))
        val c = client(); c.start("s1")
        await("ack + state") { script.acks.isNotEmpty() && script.states.isNotEmpty() }
        val origin = server.url("/").toString().trimEnd('/')
        for (r in script.pollRequests + script.acks + script.states) {
            assertEquals(origin, r.getHeader("Origin"))
            assertEquals(server.port, r.requestUrl!!.port)
        }
    }
}
