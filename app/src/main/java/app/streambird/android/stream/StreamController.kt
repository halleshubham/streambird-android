package app.streambird.android.stream

import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.BrandKit
import app.streambird.android.data.CreateStreamRequest
import app.streambird.android.data.StreamDto
import app.streambird.android.data.StreamStatusDestination
import app.streambird.android.data.uploadStreamThumbnail
import app.streambird.android.media.DeviceReading
import app.streambird.android.media.EncoderStats
import app.streambird.android.media.EngineConfig
import app.streambird.android.media.EngineListener
import app.streambird.android.media.IceServerSpec
import app.streambird.android.media.LogRow
import app.streambird.android.media.LogoOverlay
import app.streambird.android.media.PicturePriority
import app.streambird.android.media.PreviewHandle
import app.streambird.android.media.SampleLog
import app.streambird.android.media.SendQuality
import app.streambird.android.media.SendSample
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.TickerSize
import app.streambird.android.media.TickerSpec
import app.streambird.android.media.Trigger
import app.streambird.android.media.ConnPhase
import app.streambird.android.media.WARMUP_SECONDS
import app.streambird.android.media.classifySendQuality
import app.streambird.android.media.pickBrandKit
import app.streambird.android.ads.AdLoadFailure
import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdOverride
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.mergeStreamAds
import app.streambird.android.ads.AdSource
import app.streambird.android.ads.ResolvedAd
import app.streambird.android.ads.describeAdFailures
import app.streambird.android.ads.describeSchedule
import app.streambird.android.data.StreamPrefs
import app.streambird.android.media.AdInput
import app.streambird.android.media.AdsConfig
import app.streambird.android.media.EnginePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class LogoChoice(val label: String) { BRAND("Brand kit logo"), TEST("Test logo (StreamBird bird)"), NONE("No logo") }

data class GoLiveRequest(
    val title: String,
    val visibility: String,
    val destinationIds: Set<String>,
    val tier: StreamTier,
    val orientation: StreamOrientation,
    val priority: PicturePriority,
    val frontCamera: Boolean,
    val h264HighProfile: Boolean,
    val forceKeyFrames: Boolean,
    val logoChoice: LogoChoice,
    /** Brand kit picked on the Setup screen; null = automatic (kit of the first destination, else the account default). */
    val brandKitId: String? = null,
    /** Optional thumbnail (photo-picker Uri), prepared and uploaded in the background after the stream exists. */
    val thumbnailUri: String? = null,
    /** connectionId -> permanent live link (PlatformConnection.liveLinkUrl), for "Share live link". */
    val liveLinks: Map<String, String?> = emptyMap(),
    /** News ticker at the bottom of the picture (empty = none) and its S/M/L size. */
    val tickerText: String = "",
    val tickerSize: TickerSize = TickerSize.S,
    /** Per-stream changes to the kit's ads (switch off, schedule, a picture from the phone). Kit ads apply when [useKitAds] (a kit is in use). */
    val adOverrides: Map<AdPlacement, AdOverride> = emptyMap(),
    val useKitAds: Boolean = false,
    /** Free plan: no custom ads; the StreamBird banner and the "Powered by" mark are drawn. */
    val freePlan: Boolean = false,
)

data class LiveAd(val placement: AdPlacement, val manual: AdManual? = null)

enum class LivePhase { IDLE, STARTING, LIVE, ENDING, ENDED, FAILED }

data class LiveUi(
    val phase: LivePhase = LivePhase.IDLE,
    val message: String = "",
    val stream: StreamDto? = null,
    val request: GoLiveRequest? = null,
    val stats: EncoderStats? = null,
    val kbps: Double? = null,
    val quality: SendQuality? = null,
    val hardware: Boolean? = null,
    val device: DeviceReading? = null,
    val elapsedS: Long = 0,
    val destinations: List<StreamStatusDestination> = emptyList(),
    val droppedFrames: Long = 0,
    val events: List<String> = emptyList(),
    val logRows: Int = 0,
    val logoNote: String = "",
    val thumbnailNotice: ThumbnailNotice? = null,
    val tickerText: String = "",
    val tickerSize: TickerSize = TickerSize.S,
    val reconnect: ReconnectUi = ReconnectUi(),
    val canFlipCamera: Boolean = false,
    val frontCamera: Boolean = false,
    val dropDetail: String = "",
    /** The camera stopped delivering pictures (the engine is retrying) or failed. */
    val cameraNote: String? = null,
    /** Ads that run on this stream with their manual state (null = follows the schedule). */
    val adSlots: List<LiveAd> = emptyList(),
    /** Ads that could not be loaded / a GIF shown as a still: shown to the host. */
    val adsNote: String? = null,
    val freePlan: Boolean = false,
) {
    val active get() = phase == LivePhase.STARTING || phase == LivePhase.LIVE || phase == LivePhase.ENDING
}

/**
 * Process-wide owner of the one running stream: creates it via the API, runs the engine, samples telemetry and
 * exposes everything the Live screen needs. Lives in the application scope so that it survives the Activity; the
 * foreground service only keeps the process alive and shows the notification.
 *
 * Concurrency rules (each was a bug once): phase changes are claimed atomically with [tryMove] so that exactly one of
 * end() / endedByServer() / the start coroutine wins; the start coroutine is a cancellable Job that end() cancels and joins
 * BEFORE touching the engine; reconnect supervision is stopped FIRST in every teardown; engine.stop() is idempotent; the stream
 * created on the server is ended at most once; timers carry a generation so an old one cannot touch a later stream.
 */
@Singleton
class StreamController @Inject constructor(
    private val env: StreamEnv,
    private val api: ApiClient,
    private val settings: StreamPrefs,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    @Volatile private var engine: EnginePort? = null
    private var log: SampleLog? = null
    @Volatile private var startedAtMs = 0L
    private var runJob: Job? = null
    private var pollJob: Job? = null
    private var armJob: Job? = null
    private var unwatchNetwork: (() -> Unit)? = null
    private var unwatchTriggers: (() -> Unit)? = null
    private var reconnector: Reconnector? = null
    private var tickerSpec: TickerSpec? = null
    @Volatile private var gen = 0
    private val created = AtomicReference<StreamDto?>(null)
    private val endSent = AtomicBoolean(false)
    private var logoBmp: android.graphics.Bitmap? = null

    /** Timings; tests shorten them. */
    internal var pollIntervalMs = 5_000L
    internal var armDelayMs = 8_000L
    internal var autoReturnMs = AUTO_RETURN_MS

    val preview: PreviewHandle? get() = engine?.preview

    /** Id of the stream this phone has created (also during STARTING), so the stale-stream check never flags our own. */
    val currentStreamId: String? get() = created.get()?.id

    /** Atomically moves the phase from one of [from] to the state [update] returns; false when the phase was something else. */
    private fun tryMove(vararg from: LivePhase, update: (LiveUi) -> LiveUi): Boolean {
        while (true) {
            val c = _ui.value
            if (c.phase !in from) return false
            if (_ui.compareAndSet(c, update(c))) return true
        }
    }

    fun start(req: GoLiveRequest) {
        val ok = tryMove(LivePhase.IDLE, LivePhase.ENDED, LivePhase.FAILED) { LiveUi(phase = LivePhase.STARTING, message = "Creating the stream...", request = req) }
        if (!ok) return
        app.streambird.android.diag.Diag.log("GO LIVE requested: tier=${req.tier.label} ${req.orientation} dests=${req.destinationIds.size} ticker=${req.tickerText.isNotBlank()} thumb=${req.thumbnailUri != null}")
        val g = ++gen
        created.set(null); endSent.set(false); engine = null; tickerSpec = null; log = null
        env.startForegroundService()
        runJob = scope.launch { run(req, g) }
    }

    /** UI updates of the start coroutine only apply while that very start is still STARTING. */
    private fun startUpdate(g: Int, f: (LiveUi) -> LiveUi) = _ui.update { if (gen == g && it.phase == LivePhase.STARTING) f(it) else it }

    private suspend fun run(req: GoLiveRequest, g: Int) {
        try {
            val stream = api.createStream(
                CreateStreamRequest(title = req.title.ifBlank { "Mobile stream" }, visibility = req.visibility, orientation = req.orientation.wire, destinationConnectionIds = req.destinationIds.toList()),
            )
            created.set(stream)
            currentCoroutineContext().ensureActive()
            startUpdate(g) { it.copy(stream = stream, message = "Preparing camera and encoder...") }
            launchThumbnail(stream, req, g)
            val whipUrl = stream.whipUrl ?: error("The server returned no publish (WHIP) URL for this stream.")

            val ice = runCatching { api.turnCredentials().iceServers.map { IceServerSpec(it.urlList(), it.username, it.credential) } }
                .getOrDefault(emptyList()).ifEmpty { listOf(IceServerSpec(listOf("stun:stun.l.google.com:19302"))) }
            currentCoroutineContext().ensureActive()
            val (logo, logoNote) = loadLogo(req)
            currentCoroutineContext().ensureActive()
            startUpdate(g) { it.copy(logoNote = logoNote) }
            // Each ad loads on its own: one failing fetch drops only that ad, and the host is told which.
            val (adInputs, adFailures) = loadAds(req)
            currentCoroutineContext().ensureActive()
            startUpdate(g) { it.copy(adSlots = adInputs.map { a -> LiveAd(a.placement) }, adsNote = describeAdFailures(adFailures), freePlan = req.freePlan) }
            adFailures.forEach { event("ad ${it.placement.wire} failed to load: ${it.reason}", withRow = true) }

            log = SampleLog(
                env.logHeader() + listOf(
                    "server" to settings.baseUrl, "stream_id" to stream.id,
                    "tier" to "${req.tier.label} ${req.orientation.wire} ${req.tier.sizeLabel(req.orientation)} max ${req.tier.maxBitrateBps / 1000} kbps",
                    "picture_priority" to req.priority.name,
                    "camera" to if (req.frontCamera) "front" else "back",
                    "h264_high_profile" to req.h264HighProfile.toString(),
                    "force_keyframes_2s" to req.forceKeyFrames.toString(),
                    "ticker" to if (req.tickerText.isBlank()) "off" else "on size=${req.tickerSize.label} len=${req.tickerText.length}",
                    "logo" to "${req.logoChoice} $logoNote",
                    "ads" to if (req.freePlan) "free plan: StreamBird banner + mark" else adInputs.joinToString(", ") { "${it.placement.wire} ${if (it.enabled) "on" else "off"} ${describeSchedule(it.schedule)}" }.ifEmpty { "none" },
                ),
            )

            val eng = env.createEngine(listener)
            engine = eng
            unwatchNetwork = env.watchDeviceNetwork { event(it, withRow = true) }
            tickerSpec = TickerSpec.next(null, req.tickerText, req.tickerSize, android.os.SystemClock.elapsedRealtime())
            startUpdate(g) { it.copy(tickerText = tickerSpec?.text.orEmpty(), tickerSize = req.tickerSize) }
            event("ticker ${if (tickerSpec != null) "on size=${req.tickerSize.label} len=${tickerSpec!!.text.length}" else "off"}", withRow = false)
            eng.start(EngineConfig(req.tier, req.orientation, req.priority, req.frontCamera, req.h264HighProfile, req.forceKeyFrames, logo, ice, whipUrl, tickerSpec, AdsConfig(adInputs, req.freePlan)))
            currentCoroutineContext().ensureActive()

            startedAtMs = System.currentTimeMillis()
            adInputs.forEach { event("ad ${it.placement.wire} ${if (it.enabled) "on" else "off"} (${describeSchedule(it.schedule)})", withRow = true) }
            if (req.freePlan) event("ad lower_third (StreamBird banner) on, Powered by mark on", withRow = true)
            val live = tryMove(LivePhase.STARTING) {
                if (gen != g) it else it.copy(phase = LivePhase.LIVE, canFlipCamera = eng.hasBothCameras, frontCamera = eng.frontCamera, message = "Live. Platforms need a few seconds to show the picture.")
            }
            if (!live || gen != g) return // end() claimed the stream meanwhile: it tears everything down
            eng.startAdClock() // the ad clock starts at 0 on the real go-live and is never restarted by a reconnect
            event("LIVE", withRow = true)
            armJob = scope.launch { armReconnector(stream.id, g) }
            pollJob = scope.launch { pollStatus(stream.id, g) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val msg = (t as? ApiException)?.message ?: t.message ?: t.javaClass.simpleName
            event("start failed: $msg", withRow = false)
            stopReconnector()
            runCatching { engine?.stop() }
            engine = null
            unwatchNetwork?.invoke(); unwatchNetwork = null
            // The stream row was created and is LIVE server-side: end it (once) so it does not linger.
            endCreated()
            if (gen == g && tryMove(LivePhase.STARTING) { it.copy(phase = LivePhase.FAILED, message = "Could not go live: $msg") }) env.stopForegroundService()
        }
    }

    /** POST /streams/:id/end for the stream this phone created, at most once. Returns the failure, or null when ended / nothing to end. */
    private suspend fun endCreated(): Throwable? {
        val s = created.get() ?: return null
        if (!endSent.compareAndSet(false, true)) return null
        return try { api.endStream(s.id); null } catch (e: CancellationException) { throw e } catch (e: Throwable) { e }
    }

    /** End pressed on the phone (or the camera died: [reason]). */
    fun end(reason: String? = null) {
        if (!tryMove(LivePhase.STARTING, LivePhase.LIVE) { it.copy(phase = LivePhase.ENDING, message = "Ending the stream...") }) return
        val g = gen
        scope.launch {
            stopReconnector()
            armJob?.cancel(); pollJob?.cancel()
            runJob?.cancelAndJoin() // the start coroutine is finished or cancelled before anything below reads the engine / created stream
            unwatchNetwork?.invoke(); unwatchNetwork = null
            engine?.dropSummary()?.takeIf { it.isNotEmpty() }?.let { event(it, withRow = true) }
            event("END requested" + (reason?.let { " ($it)" } ?: ""), withRow = true)
            runCatching { engine?.stop() }
            engine = null
            releaseLogo()
            val err = endCreated()
            val id = created.get()?.id
            // Show the real destination statuses after the end (not the stale "live" from the last poll).
            val last = id?.let { runCatching { api.streamStatus(it) }.getOrNull() }
            _ui.update {
                if (gen != g) it else it.copy(
                    phase = LivePhase.ENDED,
                    message = reason?.let { r -> "Stopped: $r" } ?: StatusFollow.endMessage(last?.status ?: if (err == null) "ended" else null, byUs = true, confirmed = err == null),
                    destinations = StatusFollow.finalDestinations(last?.status ?: "ended", last?.destinations ?: it.destinations),
                )
            }
            env.stopForegroundService()
            scheduleAutoReturn(g)
        }
    }

    /** Back to the start after Ended/Failed (keeps the log for sharing until the next start). */
    fun acknowledge() {
        val p = _ui.value.phase
        if (p != LivePhase.ENDED && p != LivePhase.FAILED) return
        // Clean slate: nothing of the finished stream may leak into the next Go live, and old timers become no-ops.
        gen++
        runJob?.cancel(); pollJob?.cancel(); armJob?.cancel()
        stopReconnector()
        val e = engine; engine = null
        if (e != null) scope.launch { runCatching { e.stop() } }
        releaseLogo()
        created.set(null)
        _ui.value = LiveUi()
    }

    fun setPriority(p: PicturePriority) {
        engine?.setPicturePriority(p)
        _ui.update { it.copy(request = it.request?.copy(priority = p)) }
        event("picture priority -> $p", withRow = true)
    }

    /** Flip front/back while live. The stream, encoder and connection are untouched; failures keep the current camera. */
    fun flipCamera() {
        val eng = engine ?: return
        if (_ui.value.phase != LivePhase.LIVE) return
        val before = if (eng.frontCamera) "front" else "back"
        eng.switchCamera { error, nowFront ->
            if (error == null) {
                val after = if (nowFront) "front" else "back"
                _ui.update { it.copy(frontCamera = nowFront, message = "Camera: $after.") }
                event("camera $before -> $after", withRow = true)
            } else {
                _ui.update { it.copy(message = "Could not switch camera ($error). Still using the $before camera.") }
                event("camera switch failed: $error", withRow = true)
            }
        }
    }

    fun dismissThumbnailNotice() = _ui.update { it.copy(thumbnailNotice = null) }

    /** Background thumbnail upload: never delays go-live and never fails the stream; the outcome becomes a dismissible notice. */
    private fun launchThumbnail(stream: StreamDto, req: GoLiveRequest, g: Int) {
        val uri = req.thumbnailUri ?: return
        scope.launch {
            val notice = try {
                val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { env.prepareThumbnail(uri, stream.orientation == "portrait") }
                thumbnailNotice(api.uploadStreamThumbnail(stream.id, bytes))
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                thumbnailFailure((t as? ApiException)?.message ?: t.message)
            }
            event("thumbnail: ${notice.kind}", withRow = false)
            _ui.update { if (gen == g) it.copy(thumbnailNotice = notice) else it }
        }
    }

    private fun readDevice() = env.readDevice() ?: DeviceReading(null, null, null, null, "unknown")

    fun logCsv(): String? = log?.toCsv()

    private val listener = object : EngineListener {
        override fun onStats(stats: EncoderStats, kbps: Double?, hardware: Boolean?) {
            val req = _ui.value.request ?: return
            val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000
            val warming = elapsed < WARMUP_SECONDS
            val q = classifySendQuality(SendSample(stats.width, stats.height, stats.fps, kbps?.times(1000), stats.limitationReason), req.tier.width(req.orientation), req.tier.height(req.orientation), warming)
            val dev = readDevice()
            reconnector?.onStats(stats)
            log?.add(LogRow(System.currentTimeMillis(), elapsed, stats, kbps, q.limited, dev))
            _ui.update {
                it.copy(
                    stats = stats, kbps = kbps, quality = q, hardware = hardware, device = dev, elapsedS = elapsed,
                    droppedFrames = engine?.droppedFrames ?: 0, dropDetail = engine?.dropSummary().orEmpty(), logRows = log?.size ?: 0,
                )
            }
        }

        // Connection-state events become CSV rows too, so a log shows exactly when the transport changed.
        override fun onEvent(text: String) = event(text, withRow = text.startsWith("peer connection") || text.startsWith("ice "))

        override fun onConnection(state: String) {
            reconnector?.onPeerState(state)
            if (state == "CONNECTED") _ui.update { if (it.phase == LivePhase.LIVE && !it.reconnect.phaseBusy) it.copy(message = "Live.") else it }
        }

        override fun onCameraProblem(text: String) { _ui.update { if (it.phase == LivePhase.LIVE) it.copy(cameraNote = text) else it } }
        override fun onCameraRecovered() { _ui.update { it.copy(cameraNote = null) } }
        override fun onCameraFatal(text: String) { end(reason = text) }
        override fun onAdNote(text: String) { _ui.update { it.copy(adsNote = listOfNotNull(it.adsNote, text).joinToString("\n")) } }
    }

    // ---- reconnect (network change / dead transport) ----

    /** Waits for first stats + 8 s of LIVE, then starts network/stall supervision (so nothing can disturb the initial connect). */
    private suspend fun armReconnector(streamId: String, g: Int) {
        delay(armDelayMs)
        while (gen == g && _ui.value.phase == LivePhase.LIVE && _ui.value.stats == null) delay(500)
        if (gen != g || _ui.value.phase != LivePhase.LIVE || _ui.value.stream?.id != streamId || reconnector != null) return
        event("reconnect supervision armed", withRow = false)
        startReconnector()
    }

    private fun startReconnector() {
        val host = object : ReconnectHost {
            override suspend fun republish() { (engine ?: error("stream engine is gone")).republish() }
            override fun log(text: String) = event(text, withRow = true)
            override fun nowMs() = System.currentTimeMillis()
            override fun onUi(ui: ReconnectUi) {
                _ui.update { cur ->
                    if (cur.phase != LivePhase.LIVE) return@update cur
                    val msg = when {
                        ui.phase == ConnPhase.RECONNECTING || ui.phase == ConnPhase.WAITING_NETWORK -> "Reconnecting..."
                        ui.phase == ConnPhase.GAVE_UP -> "Could not reconnect. Check your network, or end the stream."
                        ui.backOnlineAtMs > 0 -> "Back online."
                        else -> cur.message
                    }
                    cur.copy(reconnect = ui, message = msg)
                }
            }
        }
        val r = Reconnector(scope, host).also { reconnector = it }
        unwatchTriggers = env.watchNetworkTriggers { t -> r.onTrigger(t) }
    }

    private fun stopReconnector() {
        unwatchTriggers?.invoke(); unwatchTriggers = null
        reconnector?.stop(); reconnector = null
    }

    /** Debug aid (Details section): closes the transport like a network switch would. The reconnect path should kick in. */
    fun simulateDrop() {
        event("SIMULATED network drop", withRow = true)
        engine?.simulateDrop()
    }

    /** Show now / Hide / Follow schedule for one ad (never saved). */
    fun setAdManual(p: AdPlacement, m: AdManual?) {
        engine?.setAdManual(p, m)
        _ui.update { it.copy(adSlots = it.adSlots.map { a -> if (a.placement == p) a.copy(manual = m) else a }) }
        event("ad ${p.wire} " + when (m) { AdManual.SHOWN -> "shown by hand"; AdManual.HIDDEN -> "hidden by hand"; null -> "follows the schedule" }, withRow = true)
    }

    fun dismissAdsNote() = _ui.update { it.copy(adsNote = null) }

    /** Live ticker edit: text and size apply to the running picture without restarting anything. */
    fun setTicker(rawText: String, size: TickerSize) {
        val spec = TickerSpec.next(tickerSpec, rawText, size, android.os.SystemClock.elapsedRealtime())
        tickerSpec = spec
        engine?.setTicker(spec)
        settings.tickerText = spec?.text.orEmpty(); settings.tickerSize = size.name
        _ui.update { it.copy(tickerText = spec?.text.orEmpty(), tickerSize = size) }
        event(if (spec != null) "ticker on size=${size.label} len=${spec.text.length}" else "ticker off", withRow = true)
    }

    private fun event(text: String, withRow: Boolean) {
        app.streambird.android.diag.Diag.log(text)
        val stamp = SampleLog.iso(System.currentTimeMillis()).substring(11, 19)
        _ui.update { it.copy(events = (it.events + "$stamp $text").takeLast(40)) }
        if (withRow) log?.add(
            LogRow(System.currentTimeMillis(), (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0) / 1000, null, null, false, readDevice(), event = text),
        )
    }

    /** Follows the server every 5 s (same fields as the web): when the stream is no longer live, stop everything here. */
    private suspend fun pollStatus(streamId: String, g: Int) {
        var failures = 0
        while (currentCoroutineContext().isActive && gen == g) {
            delay(pollIntervalMs)
            val st = try { api.streamStatus(streamId).also { failures = 0 } } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                failures++
                if (e is ApiException && StatusFollow.gone(e.status)) { scope.launch { endedByServer("gone", emptyList(), g) }; return }
                if (failures == 3) event("status poll failing: ${e.message}", withRow = true)
                continue
            }
            if (gen != g) return
            _ui.update { it.copy(destinations = StatusFollow.finalDestinations(st.status, st.destinations)) }
            if (StatusFollow.mustStop(st.status) && _ui.value.phase == LivePhase.LIVE) {
                event("server says stream is ${st.status}", withRow = true)
                scope.launch { endedByServer(st.status, st.destinations, g) }
                return
            }
        }
    }

    /** The stream ended on the server (website, another device, force-end, failure): stop publishing, camera and service; do NOT call /end. */
    private suspend fun endedByServer(status: String, dests: List<StreamStatusDestination>, g: Int) {
        if (gen != g || !tryMove(LivePhase.LIVE) { it.copy(phase = LivePhase.ENDING, message = "The stream was ended elsewhere. Stopping...") }) return
        stopReconnector()
        armJob?.cancel(); pollJob?.cancel()
        runJob?.cancelAndJoin()
        unwatchNetwork?.invoke(); unwatchNetwork = null
        endSent.set(true) // the server already ended it: nothing for us to end
        runCatching { engine?.stop() }
        engine = null
        releaseLogo()
        _ui.update {
            if (gen != g) it else it.copy(
                phase = LivePhase.ENDED, message = StatusFollow.endMessage(status, byUs = false),
                destinations = StatusFollow.finalDestinations(status, dests.ifEmpty { it.destinations }),
            )
        }
        event("ended by the server ($status)", withRow = true)
        env.stopForegroundService()
        scheduleAutoReturn(g)
    }

    /** The ended summary returns to the start by itself after a few seconds (or on Done / Back); an old timer never touches a later stream. */
    private fun scheduleAutoReturn(g: Int) {
        scope.launch { delay(autoReturnMs); if (gen == g && _ui.value.phase == LivePhase.ENDED) acknowledge() }
    }

    // ---- ads ----

    /** Loads every custom ad independently (library file via the API, or the phone picture from memory). Never on Free. */
    private suspend fun loadAds(req: GoLiveRequest): Pair<List<AdInput>, List<AdLoadFailure>> {
        if (req.freePlan) return emptyList<AdInput>() to emptyList()
        val inputs = ArrayList<AdInput>(); val failures = ArrayList<AdLoadFailure>()
        // The kit (fresh from the server, same choice as the logo) supplies the ads unless the stream runs without a kit.
        var kitAds: List<app.streambird.android.data.KitAd>? = null
        if (req.useKitAds) {
            try {
                val kits = api.brandKits()
                kitAds = (kits.firstOrNull { it.id == req.brandKitId } ?: pickBrandKit(kits, req.destinationIds, { it.isDefault }, { it.connectionIds }))?.ads
            } catch (e: CancellationException) { throw e } catch (t: Throwable) {
                failures.add(AdLoadFailure(AdPlacement.LOWER_THIRD, "brand kit ads unavailable: ${(t as? ApiException)?.message ?: t.message}"))
            }
        }
        for (a in mergeStreamAds(kitAds, req.adOverrides, freePlan = false)) {
            try {
                val bytes = if (a.source == AdSource.PHONE) a.phone!!.bytes else api.assetFile(a.assetId!!)
                inputs.add(AdInput(a.placement, bytes, a.enabled, a.schedule))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failures.add(AdLoadFailure(a.placement, (t as? ApiException)?.message ?: t.message ?: t.javaClass.simpleName))
            }
        }
        return inputs to failures
    }

    // ---- logo ----

    private fun releaseLogo() { runCatching { logoBmp?.recycle() }; logoBmp = null }

    private suspend fun loadLogo(req: GoLiveRequest): Pair<LogoOverlay?, String> = when (req.logoChoice) {
        LogoChoice.NONE -> null to "none"
        LogoChoice.TEST -> env.testLogo()?.let { logoBmp = it; LogoOverlay(it, onRight = false, heightPx = 120) to "test logo (StreamBird bird)" } ?: (null to "test logo unavailable")
        LogoChoice.BRAND -> try {
            val kits = api.brandKits()
            val kit: BrandKit? = kits.firstOrNull { it.id == req.brandKitId } ?: pickBrandKit(kits, req.destinationIds, { it.isDefault }, { it.connectionIds })
            val assetId = kit?.logoAssetId
            if (kit == null || assetId == null) null to "brand kit has no logo"
            else {
                val bytes = api.assetFile(assetId)
                val bmp = env.decodeLogo(bytes) ?: error("logo image could not be decoded")
                logoBmp = bmp
                LogoOverlay(bmp, onRight = kit.logoPosition == "right", heightPx = kit.logoSize) to "kit '${kit.name}' ${bmp.width}x${bmp.height}, ${kit.logoPosition}, ${kit.logoSize}px"
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            null to "brand logo unavailable (${t.message})"
        }
    }

    companion object { const val AUTO_RETURN_MS = 8_000L }
}
