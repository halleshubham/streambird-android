package app.streambird.android.stream

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.content.ContextCompat
import app.streambird.android.R
import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.BrandKit
import app.streambird.android.data.CreateStreamRequest
import app.streambird.android.data.Settings
import app.streambird.android.data.StreamDto
import app.streambird.android.data.StreamStatusDestination
import app.streambird.android.data.uploadStreamThumbnail
import app.streambird.android.media.CodecProbe
import app.streambird.android.media.DeviceReading
import app.streambird.android.media.DeviceTelemetry
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
import app.streambird.android.media.StreamEngine
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import app.streambird.android.media.TickerSize
import app.streambird.android.media.TickerSpec
import app.streambird.android.media.NetworkWatcher
import app.streambird.android.media.Trigger
import app.streambird.android.media.ConnPhase
import app.streambird.android.media.WARMUP_SECONDS
import app.streambird.android.media.classifySendQuality
import app.streambird.android.media.pickBrandKit
import app.streambird.android.whip.WhipClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
)

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
) {
    val active get() = phase == LivePhase.STARTING || phase == LivePhase.LIVE || phase == LivePhase.ENDING
}

/**
 * Process-wide owner of the one running stream: creates it via the API, runs the [StreamEngine], samples telemetry and
 * exposes everything the Live screen needs. Lives in the application scope so that it survives the Activity; the
 * foreground [StreamingService] only keeps the process alive and shows the notification.
 */
@Singleton
class StreamController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui.asStateFlow()

    private val telemetry = DeviceTelemetry(context)
    private val whipHttp = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private var engine: StreamEngine? = null
    private var log: SampleLog? = null
    private var startedAtMs = 0L
    private var pollJob: Job? = null
    private var unwatchNetwork: (() -> Unit)? = null
    private var reconnector: Reconnector? = null
    private var netWatcher: NetworkWatcher? = null
    private var tickerSpec: TickerSpec? = null

    val preview: PreviewHandle? get() = engine?.preview

    fun start(req: GoLiveRequest) {
        if (_ui.value.active) return
        app.streambird.android.diag.Diag.log("GO LIVE requested: tier=${req.tier.label} ${req.orientation} dests=${req.destinationIds.size} ticker=${req.tickerText.isNotBlank()} thumb=${req.thumbnailUri != null}")
        _ui.value = LiveUi(phase = LivePhase.STARTING, message = "Creating the stream...", request = req)
        ContextCompat.startForegroundService(context, Intent(context, StreamingService::class.java))
        scope.launch { run(req) }
    }

    private suspend fun run(req: GoLiveRequest) {
        var stream: StreamDto? = null
        try {
            stream = api.createStream(
                CreateStreamRequest(title = req.title.ifBlank { "Mobile stream" }, visibility = req.visibility, orientation = req.orientation.wire, destinationConnectionIds = req.destinationIds.toList()),
            )
            launchThumbnail(stream, req)
            val whipUrl = stream.whipUrl ?: error("The server returned no publish (WHIP) URL for this stream.")
            _ui.update { it.copy(stream = stream, message = "Preparing camera and encoder...") }

            val ice = runCatching { api.turnCredentials().iceServers.map { IceServerSpec(it.urlList(), it.username, it.credential) } }
                .getOrDefault(emptyList()).ifEmpty { listOf(IceServerSpec(listOf("stun:stun.l.google.com:19302"))) }
            val (logo, logoNote) = loadLogo(req)
            _ui.update { it.copy(logoNote = logoNote) }

            val header = listOf(
                "app" to "StreamBird Android spike ${app.streambird.android.BuildConfig.VERSION_NAME}",
                "device" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
                "server" to settings.baseUrl,
                "stream_id" to stream.id,
                "tier" to "${req.tier.label} ${req.orientation.wire} ${req.tier.sizeLabel(req.orientation)} max ${req.tier.maxBitrateBps / 1000} kbps",
                "picture_priority" to req.priority.name,
                "camera" to if (req.frontCamera) "front" else "back",
                "h264_high_profile" to req.h264HighProfile.toString(),
                "force_keyframes_2s" to req.forceKeyFrames.toString(),
                "ticker" to if (req.tickerText.isBlank()) "off" else "on size=${req.tickerSize.label} len=${req.tickerText.length}",
                "logo" to "${req.logoChoice} $logoNote",
                "codecs" to CodecProbe.summary(),
            )
            log = SampleLog(header)

            val eng = StreamEngine(context, WhipClient(whipHttp), listener)
            engine = eng
            unwatchNetwork = telemetry.watchNetwork { event(it, withRow = true) }
            tickerSpec = TickerSpec.next(null, req.tickerText, req.tickerSize, android.os.SystemClock.elapsedRealtime())
            _ui.update { it.copy(tickerText = tickerSpec?.text.orEmpty(), tickerSize = req.tickerSize) }
            event("ticker ${if (tickerSpec != null) "on size=${req.tickerSize.label} len=${tickerSpec!!.text.length}" else "off"}", withRow = false)
            eng.start(EngineConfig(req.tier, req.orientation, req.priority, req.frontCamera, req.h264HighProfile, req.forceKeyFrames, logo, ice, whipUrl, tickerSpec))
            // The first connection is left alone: watchers start only once it has been healthy for a few seconds.
            scope.launch { armReconnector(stream.id) }

            startedAtMs = System.currentTimeMillis()
            _ui.update { it.copy(canFlipCamera = eng.hasBothCameras, frontCamera = eng.frontCamera) }
            _ui.update { it.copy(phase = LivePhase.LIVE, message = "Live. Platforms need a few seconds to show the picture.") }
            event("LIVE", withRow = true)
            pollJob = scope.launch { pollStatus(stream.id) }
        } catch (t: Throwable) {
            val msg = (t as? ApiException)?.message ?: t.message ?: t.javaClass.simpleName
            event("start failed: $msg", withRow = false)
            runCatching { engine?.stop() }
            engine = null
            unwatchNetwork?.invoke(); unwatchNetwork = null
            stopReconnector()
            // The stream row was created and is LIVE server-side: end it so it does not linger.
            stream?.let { runCatching { api.endStream(it.id) } }
            _ui.update { it.copy(phase = LivePhase.FAILED, message = "Could not go live: $msg") }
            stopService()
        }
    }

    fun end() {
        val cur = _ui.value
        if (!cur.active || cur.phase == LivePhase.ENDING) return
        _ui.update { it.copy(phase = LivePhase.ENDING, message = "Ending the stream...") }
        scope.launch {
            pollJob?.cancel()
            unwatchNetwork?.invoke(); unwatchNetwork = null
            engine?.dropSummary()?.takeIf { it.isNotEmpty() }?.let { event(it, withRow = true) }
            event("END requested", withRow = true)
            runCatching { engine?.stop() }
            engine = null
            val id = cur.stream?.id
            val err = id?.let { runCatching { api.endStream(it) }.exceptionOrNull() }
            _ui.update {
                it.copy(
                    phase = LivePhase.ENDED,
                    message = if (err == null) "Stream ended." else "Stopped sending, but the server did not confirm: ${err.message}",
                )
            }
            stopService()
        }
    }

    /** Back to the setup screen after Ended/Failed (keeps the log for sharing until the next start). */
    fun acknowledge() {
        if (_ui.value.phase == LivePhase.ENDED || _ui.value.phase == LivePhase.FAILED) _ui.update { it.copy(phase = LivePhase.IDLE) }
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
    private fun launchThumbnail(stream: StreamDto, req: GoLiveRequest) {
        val uri = req.thumbnailUri ?: return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val notice = try {
                val enc = ThumbnailPreparer.prepare(context, android.net.Uri.parse(uri), stream.orientation == "portrait")
                thumbnailNotice(api.uploadStreamThumbnail(stream.id, enc.bytes))
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                thumbnailFailure((t as? ApiException)?.message ?: t.message)
            }
            event("thumbnail: ${notice.kind}", withRow = false)
            _ui.update { if (it.stream == null || it.stream.id == stream.id) it.copy(thumbnailNotice = notice) else it }
        }
    }

    fun logCsv(): String? = log?.toCsv()

    private val listener = object : EngineListener {
        override fun onStats(stats: EncoderStats, kbps: Double?, hardware: Boolean?) {
            val req = _ui.value.request ?: return
            val elapsed = (System.currentTimeMillis() - startedAtMs) / 1000
            val warming = elapsed < WARMUP_SECONDS
            val q = classifySendQuality(SendSample(stats.width, stats.height, stats.fps, kbps?.times(1000), stats.limitationReason), req.tier.width(req.orientation), req.tier.height(req.orientation), warming)
            val dev = telemetry.read()
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
    }

    // ---- reconnect (network change / dead transport) ----

    /** Waits for first stats + 8 s of LIVE, then starts network/stall supervision (so nothing can disturb the initial connect). */
    private suspend fun armReconnector(streamId: String) {
        delay(8_000)
        while (_ui.value.phase == LivePhase.LIVE && _ui.value.stats == null) delay(500)
        if (_ui.value.phase != LivePhase.LIVE || _ui.value.stream?.id != streamId || reconnector != null) return
        event("reconnect supervision armed", withRow = false)
        startReconnector()
    }

    private fun startReconnector() {
        val host = object : ReconnectHost {
            override suspend fun republish() { engine?.republish() ?: error("stream engine is gone") }
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
        netWatcher = NetworkWatcher(context) { t -> r.onTrigger(t) }.also { it.start() }
    }

    private fun stopReconnector() {
        netWatcher?.stop(); netWatcher = null
        reconnector?.stop(); reconnector = null
    }

    /** Debug aid (Details section): closes the transport like a network switch would. The reconnect path should kick in. */
    fun simulateDrop() {
        event("SIMULATED network drop", withRow = true)
        engine?.simulateDrop()
    }

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
            LogRow(System.currentTimeMillis(), (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0) / 1000, null, null, false, telemetry.read(), event = text),
        )
    }

    private suspend fun pollStatus(streamId: String) {
        while (scope.isActive) {
            delay(10_000)
            val st = runCatching { api.streamStatus(streamId) }.getOrNull() ?: continue
            _ui.update { it.copy(destinations = st.destinations) }
            if (st.status != "live" && _ui.value.phase == LivePhase.LIVE) {
                event("server says stream is ${st.status}", withRow = true)
                _ui.update { it.copy(message = "The server ended this stream (${st.status}).") }
                end()
                return
            }
        }
    }

    // ---- logo ----

    private suspend fun loadLogo(req: GoLiveRequest): Pair<LogoOverlay?, String> = when (req.logoChoice) {
        LogoChoice.NONE -> null to "none"
        LogoChoice.TEST -> LogoOverlay(testLogoBitmap(), onRight = false, heightPx = 120) to "test logo (StreamBird bird)"
        LogoChoice.BRAND -> try {
            val kits = api.brandKits()
            val kit: BrandKit? = kits.firstOrNull { it.id == req.brandKitId } ?: pickBrandKit(kits, req.destinationIds, { it.isDefault }, { it.connectionIds })
            val assetId = kit?.logoAssetId
            if (kit == null || assetId == null) null to "brand kit has no logo"
            else {
                val bytes = api.assetFile(assetId)
                val bmp = decodeLogo(bytes) ?: error("logo image could not be decoded")
                LogoOverlay(bmp, onRight = kit.logoPosition == "right", heightPx = kit.logoSize) to "kit '${kit.name}' ${bmp.width}x${bmp.height}, ${kit.logoPosition}, ${kit.logoSize}px"
            }
        } catch (t: Throwable) {
            null to "brand logo unavailable (${t.message})"
        }
    }

    private fun decodeLogo(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 512) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun testLogoBitmap(): Bitmap {
        return BitmapFactory.decodeResource(context.resources, R.drawable.logo_bird)
    }

    private fun stopService() {
        context.stopService(Intent(context, StreamingService::class.java))
    }
}
