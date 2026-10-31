package app.streambird.android.stream

import app.streambird.android.data.ApiClient
import app.streambird.android.data.StreamPrefs
import app.streambird.android.media.DeviceReading
import app.streambird.android.media.EngineConfig
import app.streambird.android.media.EngineListener
import app.streambird.android.media.EnginePort
import app.streambird.android.media.PicturePriority
import app.streambird.android.media.PreviewHandle
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.TickerSpec
import app.streambird.android.media.Trigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The controller's concurrency rules, on the JVM, with a fake engine / Android environment and a scripted server (real ApiClient + OkHttp interceptor). */
class StreamControllerTest {
    private class FakeEngine(val gateStart: CompletableDeferred<Unit>? = null, val failStart: Boolean = false) : EnginePort {
        val stops = AtomicInteger(); val starts = AtomicInteger(); val republishes = AtomicInteger()
        override suspend fun start(cfg: EngineConfig) { lastConfig = cfg; starts.incrementAndGet(); if (failStart) error("no camera"); gateStart?.await() }
        override suspend fun stop() { stops.incrementAndGet(); delay(20) }
        override suspend fun republish() { republishes.incrementAndGet() }
        override fun simulateDrop() {}
        override fun setTicker(spec: TickerSpec?) {}
        override fun setPicturePriority(p: PicturePriority) {}
        val clockStarts = AtomicInteger(); val manuals = CopyOnWriteArrayList<Pair<app.streambird.android.ads.AdPlacement, app.streambird.android.ads.AdManual?>>()
        var lastConfig: EngineConfig? = null
        override fun startAdClock() { clockStarts.incrementAndGet() }
        override fun setAdManual(p: app.streambird.android.ads.AdPlacement, m: app.streambird.android.ads.AdManual?) { manuals.add(p to m) }
        override fun switchCamera(done: (error: String?, nowFront: Boolean) -> Unit) { done(null, false) }
        override val preview: PreviewHandle? = null
        override val droppedFrames = 0L
        override fun dropSummary() = ""
        override val frontCamera = false
        override val hasBothCameras = true
    }

    private class FakeEnv(val engines: MutableList<FakeEngine>, val next: () -> FakeEngine) : StreamEnv {
        val fgStarts = AtomicInteger(); val fgStops = AtomicInteger()
        var triggers: ((Trigger) -> Unit)? = null
        lateinit var listener: EngineListener
        override fun startForegroundService() { fgStarts.incrementAndGet() }
        override fun stopForegroundService() { fgStops.incrementAndGet() }
        override fun createEngine(listener: EngineListener): EnginePort { this.listener = listener; return next().also { engines.add(it) } }
        override fun readDevice(): DeviceReading? = null
        override fun watchDeviceNetwork(onChange: (String) -> Unit): () -> Unit = {}
        override fun watchNetworkTriggers(onTrigger: (Trigger) -> Unit): () -> Unit { triggers = onTrigger; return {} }
        override fun logHeader() = listOf("app" to "test")
        override fun testLogo() = null
        override fun decodeLogo(bytes: ByteArray) = null
        override fun prepareThumbnail(uri: String, portrait: Boolean) = ByteArray(0)
    }

    private class Prefs : StreamPrefs {
        override var baseUrl = "https://streambird.test"; override var lastEmail = ""; override var tickerText = ""; override var tickerSize = "S"
    }

    /** Scripted backend: records "METHOD /path"; responses use the real DTO shapes. */
    private class Server {
        val calls = CopyOnWriteArrayList<String>()
        @Volatile var statusJson: () -> Pair<Int, String> = { 200 to """{"status":"live","relayInputStatus":null,"destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","platformStatus":"live","viewerCount":0,"watchUrl":null,"errorMessage":null}]}""" }
        val streamCounter = AtomicInteger()
        fun count(prefix: String) = calls.count { it.startsWith(prefix) }
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val key = "${req.method} ${req.url.encodedPath}"
            calls.add(key)
            val (code, body) = when {
                key == "POST /api/streams" -> 201 to """{"id":"s${streamCounter.incrementAndGet()}","title":"t","status":"live","ingestUrl":null,"streamKey":null,"whipUrl":"https://mtx.test/p/whip","visibility":"public","orientation":"portrait","description":null,"scheduledAt":null,"isScheduledEvent":false,"timezone":null,"expectedDurationMinutes":null,"cancelledAt":null,"destinations":[]}"""
                key.endsWith("/end") -> 201 to """{"id":"s1","title":"t","status":"ended","whipUrl":null,"orientation":"portrait","destinations":[]}"""
                key.endsWith("/status") -> statusJson()
                key == "GET /api/brand-kits" -> 200 to """[{"id":"k1","name":"Kit","logoAssetId":null,"logoPosition":"left","logoSize":90,"wallpaperAssetId":null,"wallpaperDim":0,"lowerThirdStyle":"accent","isDefault":true,"connectionIds":[],"ads":[{"placement":"lower_third","assetId":"good","enabled":true,"mode":"continuous","showSec":null,"everySec":null},{"placement":"side_third","assetId":"bad","enabled":true,"mode":"interval","showSec":15,"everySec":180}]}]"""
                key == "GET /api/assets/good/file" -> 200 to "PNGBYTES"
                key == "GET /api/assets/bad/file" -> 404 to """{"statusCode":404,"message":"File not found."}"""
                key.endsWith("/turn-credentials") -> 200 to """{"iceServers":[]}"""
                else -> 404 to """{"statusCode":404,"message":"Not Found"}"""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var server: Server
    private lateinit var env: FakeEnv
    private lateinit var controller: StreamController
    private val engineQueue = CopyOnWriteArrayList<FakeEngine>()
    private var nextEngine: () -> FakeEngine = { FakeEngine() }

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server = Server()
        env = FakeEnv(engineQueue) { nextEngine() }
        val api = ApiClient(OkHttpClient.Builder().addInterceptor(server.interceptor).build()) { "https://streambird.test" }
        controller = StreamController(env, api, Prefs(), scope).apply { pollIntervalMs = 50; armDelayMs = 30; autoReturnMs = 600 }
    }

    @After fun tearDown() { scope.cancel() }

    private val req get() = GoLiveRequest("t", "public", setOf("c1"), StreamTier.P720, StreamOrientation.PORTRAIT, PicturePriority.BALANCED, false, true, true, LogoChoice.NONE)

    private fun waitFor(what: String, ms: Long = 5000, cond: () -> Boolean) = runBlocking { withTimeout(ms) { while (!cond()) delay(10) } }

    private fun phases(): CopyOnWriteArrayList<LivePhase> {
        val seen = CopyOnWriteArrayList<LivePhase>()
        scope.launch { controller.ui.collect { seen.add(it.phase) } }
        return seen
    }

    @Test fun `end during STARTING never goes LIVE afterwards, ends the created stream once and stops the engine`() {
        val gate = CompletableDeferred<Unit>()
        nextEngine = { FakeEngine(gateStart = gate) }
        val seen = phases()
        controller.start(req)
        waitFor("engine start") { engineQueue.size == 1 && engineQueue[0].starts.get() == 1 }
        assertEquals("s1", controller.currentStreamId)             // own stream is known while STARTING (the stale-stream check relies on it)
        controller.end()
        waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        gate.complete(Unit)                                         // a late start completion must change nothing
        runBlocking { delay(150) }
        assertFalse(seen.contains(LivePhase.LIVE))
        assertEquals(LivePhase.ENDED, controller.ui.value.phase)
        assertEquals(1, server.count("POST /api/streams/s1/end"))
        assertEquals(1, engineQueue[0].stops.get())
        assertEquals(1, env.fgStops.get())
    }

    @Test fun `start failure ends the created stream and goes to FAILED`() {
        nextEngine = { FakeEngine(failStart = true) }
        controller.start(req)
        waitFor("failed") { controller.ui.value.phase == LivePhase.FAILED }
        assertEquals(1, server.count("POST /api/streams/s1/end"))
        assertEquals(1, engineQueue[0].stops.get())
        assertTrue(controller.ui.value.message.contains("no camera"))
        assertEquals(1, env.fgStops.get())
    }

    private fun goLive() { controller.start(req); waitFor("live") { controller.ui.value.phase == LivePhase.LIVE } }

    @Test fun `user end while live stops the engine once and calls end once`() {
        goLive()
        controller.end(); controller.end()                          // a double tap
        waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        assertEquals(1, engineQueue[0].stops.get()); assertEquals(1, server.count("POST /api/streams/s1/end"))
        assertTrue(controller.ui.value.message.startsWith("Stream ended"))
    }

    @Test fun `end racing the server saying ended stops once and calls end at most once`() {
        goLive()
        server.statusJson = { 200 to """{"status":"ended","relayInputStatus":null,"destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","platformStatus":null,"viewerCount":0,"watchUrl":null,"errorMessage":null}]}""" }
        controller.end()                                            // at the same time as the poll sees "ended"
        waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        runBlocking { delay(200) }
        assertEquals(1, engineQueue[0].stops.get())
        assertTrue(server.count("POST /api/streams/s1/end") <= 1)
        assertEquals(1, env.fgStops.get())
    }

    @Test fun `server ended it elsewhere stops everything WITHOUT calling end, rows show ended`() {
        goLive()
        server.statusJson = { 200 to """{"status":"ended","relayInputStatus":null,"destinations":[{"id":"d1","platformConnectionId":"c1","status":"live","platformStatus":"live","viewerCount":0,"watchUrl":null,"errorMessage":null}]}""" }
        waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        assertEquals(0, server.count("POST /api/streams/s1/end"))
        assertEquals(1, engineQueue[0].stops.get()); assertEquals(1, env.fgStops.get())
        assertTrue(controller.ui.value.message.contains("ended elsewhere"))
        assertEquals(listOf("ended"), controller.ui.value.destinations.map { it.status })
    }

    @Test fun `a 404 on the status poll also stops without calling end`() {
        goLive()
        server.statusJson = { 404 to """{"statusCode":404,"message":"Stream not found"}""" }
        waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        assertEquals(0, server.count("POST /api/streams/s1/end")); assertEquals(1, engineQueue[0].stops.get())
    }

    @Test fun `acknowledge leaves a clean state and a second stream works`() {
        goLive()
        controller.end(); waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        controller.acknowledge()
        assertEquals(LivePhase.IDLE, controller.ui.value.phase); assertNull(controller.ui.value.stream); assertNull(controller.currentStreamId)
        goLive()
        assertEquals("s2", controller.currentStreamId); assertEquals(2, engineQueue.size)
        assertEquals(1, engineQueue[1].starts.get()); assertEquals(0, engineQueue[1].stops.get())
        assertEquals(2, env.fgStarts.get())
        controller.end(); waitFor("ended2") { controller.ui.value.phase == LivePhase.ENDED }
        assertEquals(1, server.count("POST /api/streams/s2/end")); assertEquals(1, engineQueue[0].stops.get())
    }

    @Test fun `an old auto-return timer never acknowledges a later stream's ended screen`() {
        goLive(); controller.end(); waitFor("ended1") { controller.ui.value.phase == LivePhase.ENDED }
        runBlocking { delay(100) }
        controller.acknowledge()                                    // Done pressed; stream 1's timer (600 ms) is still pending
        goLive(); controller.end(); waitFor("ended2") { controller.ui.value.phase == LivePhase.ENDED }
        runBlocking { delay(450) }                                  // stream 1's timer fires about now: stream 2's own is still 150+ ms away
        assertEquals(LivePhase.ENDED, controller.ui.value.phase)
        waitFor("own timer") { controller.ui.value.phase == LivePhase.IDLE }   // stream 2's own timer does return to the start
    }

    @Test fun `end stops the reconnect supervision first (no republish after end)`() {
        goLive()
        env.listener.onStats(app.streambird.android.media.EncoderStats(720, 1280, 30.0, 1000, 1.0, null, "MediaCodec", true, 0, 0, 0, 0, 0, 50.0, null, null, null), 1000.0, true)
        waitFor("armed") { env.triggers != null }
        controller.end(); waitFor("ended") { controller.ui.value.phase == LivePhase.ENDED }
        env.triggers?.invoke(Trigger.Stall)                         // a late detection must be ignored
        runBlocking { delay(150) }
        assertEquals(0, engineQueue[0].republishes.get())
    }

    @Test fun `ads load independently, a failing kit ad is named, the others run, the clock starts at LIVE once`() {
        controller.start(req.copy(logoChoice = LogoChoice.NONE, useKitAds = true, adOverrides = mapOf(
            app.streambird.android.ads.AdPlacement.FULL_SCREEN to app.streambird.android.ads.AdOverride(phone = app.streambird.android.ads.PhoneAd(byteArrayOf(1, 2, 3), "p.png", false)),
        )))
        waitFor("live") { controller.ui.value.phase == LivePhase.LIVE }
        val ui = controller.ui.value
        assertEquals(listOf(app.streambird.android.ads.AdPlacement.LOWER_THIRD, app.streambird.android.ads.AdPlacement.FULL_SCREEN), ui.adSlots.map { it.placement })
        assertTrue(ui.adsNote!!.contains("Left third")); assertFalse(ui.adsNote!!.contains("Lower third"))
        assertEquals(listOf("lower_third", "full_screen"), engineQueue[0].lastConfig!!.ads!!.inputs.map { it.placement.wire })
        assertEquals(1, engineQueue[0].clockStarts.get())
        controller.setAdManual(app.streambird.android.ads.AdPlacement.LOWER_THIRD, app.streambird.android.ads.AdManual.HIDDEN)
        assertEquals(app.streambird.android.ads.AdManual.HIDDEN, controller.ui.value.adSlots.first().manual)
        assertEquals(1, engineQueue[0].manuals.size)
    }

    @Test fun `free plan carries no custom ads, only the StreamBird banner flag`() {
        controller.start(req.copy(freePlan = true, useKitAds = true))
        waitFor("live") { controller.ui.value.phase == LivePhase.LIVE }
        val cfg = engineQueue[0].lastConfig!!.ads!!
        assertTrue(cfg.freePlan); assertTrue(cfg.inputs.isEmpty())
        assertEquals(0, server.count("GET /api/brand-kits"))           // nothing is even fetched
    }
}
