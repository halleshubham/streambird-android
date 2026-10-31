package app.streambird.android.media

import android.content.Context
import app.streambird.android.whip.Sdp
import app.streambird.android.whip.WhipClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.CameraVideoCapturer
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class IceServerSpec(val urls: List<String>, val username: String? = null, val credential: String? = null)

data class EngineConfig(
    val tier: StreamTier,
    val orientation: StreamOrientation,
    val priority: PicturePriority,
    val frontCamera: Boolean,
    val h264HighProfile: Boolean,
    val forceKeyFrames: Boolean,
    val logo: LogoOverlay?,
    val iceServers: List<IceServerSpec>,
    val whipUrl: String,
    val ticker: TickerSpec? = null,
)

val EngineConfig.outWidth get() = tier.width(orientation)
val EngineConfig.outHeight get() = tier.height(orientation)

class NoH264Exception(message: String) : Exception(message)

/** What the Live screen needs to render the composited preview. */
class PreviewHandle(val eglContext: EglBase.Context, val track: VideoTrack)

interface EngineListener {
    fun onStats(stats: EncoderStats, kbps: Double?, hardware: Boolean?)
    fun onEvent(text: String)
    /** CONNECTED / DISCONNECTED / FAILED / CLOSED etc. */
    fun onConnection(state: String)
    /** The camera reported an error/disconnect/freeze or stopped delivering frames; the engine is retrying. */
    fun onCameraProblem(text: String) {}
    fun onCameraRecovered() {}
    /** The camera could not be brought back: the stream has no picture and cannot continue. */
    fun onCameraFatal(text: String) {}
}

/**
 * Owns the whole native pipeline: Camera2 -> FrameCompositor (GL) -> VideoSource -> H.264 encoder -> RTCPeerConnection -> WHIP.
 * One instance per stream. Everything here is UNVERIFIED on a device (no camera/encoder in the build sandbox).
 */
class StreamEngine(
    private val context: Context,
    private val whip: WhipClient,
    private val listener: EngineListener,
) : EnginePort {
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var statsJob: Job? = null
    private var watchdogJob: Job? = null
    /** Old connections whose cleanup is pending; anything left is disposed in [stop]. */
    private val retired = java.util.concurrent.ConcurrentLinkedQueue<Link>()
    private val cleanupJobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()
    private val lifecycle = EngineLifecycle()
    private val stopped get() = lifecycle.isStopped

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var compositor: FrameCompositor? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var config: EngineConfig? = null
    private var prevStats: EncoderStats? = null

    /** One per PeerConnection we create: events of a connection that is no longer the active one are only logged. */
    private class Link(val gen: Int, val conn: PeerConnection, val sender: org.webrtc.RtpSender, @Volatile var resourceUrl: String? = null) {
        @Volatile var state: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW
        @Volatile var gathered = false
        /** Set before the connection is closed/disposed: nobody may call into it any more. */
        @Volatile var closed = false
    }
    private var genCounter = 0
    @Volatile private var activeGen = 0

    override var preview: PreviewHandle? = null; private set
    override val droppedFrames: Long get() = compositor?.droppedFrames ?: 0
    override fun dropSummary(): String = compositor?.let {
        "compositor: composed=${it.composedFrames} droppedNoSlot=${it.droppedNoSlot} droppedError=${it.droppedError} lastDropAtFrame=${it.lastDropAtFrame}" +
            (it.lastError?.let { e -> " lastError=$e" } ?: "")
    } ?: ""

    private var enumerator: Camera2Enumerator? = null
    @Volatile private var captureSize: org.webrtc.Size? = null
    @Volatile override var frontCamera: Boolean = false; private set
    /** True when the phone has both a front and a back camera, so the flip button makes sense. */
    override var hasBothCameras: Boolean = false; private set

    /**
     * Switches to the other camera without touching the PeerConnection or encoder (the stream keeps going).
     * [done] gets null on success or an error text; on error the old camera stays active.
     */
    override fun switchCamera(done: (error: String?, nowFront: Boolean) -> Unit) {
        val cam = capturer
        val en = enumerator
        if (cam == null || en == null || !hasBothCameras) { done("This phone has only one camera.", frontCamera); return }
        val wantFront = !frontCamera
        val target = en.deviceNames.firstOrNull { if (wantFront) en.isFrontFacing(it) else en.isBackFacing(it) }
        if (target == null) { done("No ${if (wantFront) "front" else "back"} camera found.", frontCamera); return }
        cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                frontCamera = isFront
                compositor?.unmirror = isFront
                done(null, isFront)
            }
            override fun onCameraSwitchError(errorDescription: String?) = done(errorDescription ?: "camera switch failed", frontCamera)
        }, target)
    }

    override suspend fun start(cfg: EngineConfig) {
        config = cfg
        // Factory, audio module and camera are created off the main thread (they can take a while).
        withContext(Dispatchers.Default) { buildPipeline(cfg) }
        if (stopped) throw kotlinx.coroutines.CancellationException("engine stopped")
        publish(cfg)
    }

    // ---- pipeline ----

    private fun buildPipeline(cfg: EngineConfig) {
        initFactoryOnce(context)
        val egl = EglBase.create().also { eglBase = it }

        var encoderFactory: org.webrtc.VideoEncoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, /* intel vp8 */ false, cfg.h264HighProfile)
        if (cfg.forceKeyFrames) encoderFactory = KeyFrameEncoderFactory(encoderFactory, 2000)
        val admLocal = JavaAudioDeviceModule.builder(context).createAudioDeviceModule().also { adm = it }
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(admLocal)
            .createPeerConnectionFactory()
        factory = f

        val caps = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        if (caps.none { it.name.equals("H264", true) }) {
            throw NoH264Exception("This phone's WebRTC build offers no H.264 encoder (${CodecProbe.summary()}). StreamBird needs H.264.")
        }

        val h = SurfaceTextureHelper.create("sb-capture", egl.eglBaseContext).also { helper = it }
        val source = f.createVideoSource(false).also { videoSource = it }
        val comp = FrameCompositor(source.capturerObserver, h, cfg.outWidth, cfg.outHeight, cfg.logo, unmirror = cfg.frontCamera)
        comp.ticker = cfg.ticker
        compositor = comp

        val enumerator = Camera2Enumerator(context).also { this.enumerator = it }
        hasBothCameras = enumerator.deviceNames.any { enumerator.isFrontFacing(it) } && enumerator.deviceNames.any { enumerator.isBackFacing(it) }
        val name = enumerator.deviceNames.firstOrNull { if (cfg.frontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera found")
        val cam = enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String) { listener.onEvent("camera error: $errorDescription"); listener.onCameraProblem("Camera error: $errorDescription") }
            override fun onCameraDisconnected() { listener.onEvent("camera disconnected"); listener.onCameraProblem("The camera was disconnected.") }
            override fun onCameraFreezed(errorDescription: String) { listener.onEvent("camera froze: $errorDescription"); listener.onCameraProblem("The camera froze.") }
            override fun onCameraOpening(cameraName: String) {}
            override fun onFirstFrameAvailable() = listener.onEvent("camera first frame")
            override fun onCameraClosed() {}
        }) ?: throw IllegalStateException("Could not open camera $name")
        capturer = cam
        frontCamera = enumerator.isFrontFacing(name)
        compositor?.unmirror = frontCamera
        cam.initialize(h, context, comp)
        val size = CameraEnumerationAndroid.getClosestSupportedSize(
            enumerator.getSupportedFormats(name).orEmpty().map { org.webrtc.Size(it.width, it.height) }, cfg.tier.captureWidth, cfg.tier.captureHeight,
        )
        captureSize = size
        cam.startCapture(size.width, size.height, FPS)
        startCameraWatchdog()
        listener.onEvent("camera $name capture ${size.width}x${size.height}@$FPS -> output ${cfg.outWidth}x${cfg.outHeight}")
        source.adaptOutputFormat(cfg.outWidth, cfg.outHeight, FPS)

        val vt = f.createVideoTrack("sb-video", source).also { videoTrack = it }
        val asrc = f.createAudioSource(MediaConstraints()).also { audioSource = it }
        audioTrack = f.createAudioTrack("sb-audio", asrc)
        preview = PreviewHandle(egl.eglBaseContext, vt)
    }

    /**
     * Every PeerConnection lifecycle call (create, negotiate, setParameters, getStats, close, dispose) runs on THIS ONE thread,
     * so nothing can touch a connection while another thread closes or disposes it. [Link.gen] / [Link.closed] guard the rest.
     */
    private val pcExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "sb-pc") }
    private val pcDispatcher = pcExecutor.asCoroutineDispatcher()
    private val statsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile private var cur: Link? = null

    private fun trace(text: String) { app.streambird.android.diag.Diag.log("engine: $text") }

    private suspend fun publish(cfg: EngineConfig) {
        withContext(pcDispatcher) {
            trace("publish: creating the peer connection")
            val link = connect(cfg)
            cur = link; activeGen = link.gen
            applySenderParameters(cfg)
        }
        startStats()
    }

    // Same configuration as 0.2.0 (the 0.2.1 GATHER_CONTINUALLY / STUN keep-alive options were removed: see docs/FEATURES.md).
    private fun rtcConfig(cfg: EngineConfig) = PeerConnection.RTCConfiguration(
        cfg.iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).also { b ->
                s.username?.let { b.setUsername(it) }
                s.credential?.let { b.setPassword(it) }
            }.createIceServer()
        },
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
    }

    /**
     * Creates a NEW PeerConnection on the existing tracks, negotiates it with a fresh WHIP POST and waits until it is connected.
     * Used for the first publish and for every re-publish. Throws (after cleaning up the new connection) on any failure.
     * Must run on [pcDispatcher].
     */
    private suspend fun connect(cfg: EngineConfig): Link {
        val f = factory!!
        val gen = ++genCounter
        var link: Link? = null
        val observer = object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) link?.gathered = true }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                val l = link
                l?.state = s
                if (l == null || l.closed) return
                val current = gen == activeGen || activeGen == 0
                listener.onEvent("peer connection $s" + if (current) "" else " (pc #$gen, not active)")
                if (current) listener.onConnection(s.name)
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { if (link?.closed == false) listener.onEvent("ice $s" + if (gen == activeGen || activeGen == 0) "" else " (pc #$gen)") }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }
        val conn = f.createPeerConnection(rtcConfig(cfg), observer) ?: throw IllegalStateException("PeerConnection could not be created")
        trace("pc #$gen created")
        try {
            val vTrans = conn.addTransceiver(videoTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            conn.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
            val l = Link(gen, conn, vTrans.sender).also { link = it }

            // H.264 first (web: setCodecPreferences). Belt and braces: the offer SDP is also re-ordered below.
            val codecs = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val h264 = codecs.filter { it.name.equals("H264", true) }
            vTrans.setCodecPreferences(h264 + codecs.filterNot { it.name.equals("H264", true) })

            val offer = conn.awaitOffer()
            trace("pc #$gen offer created")
            val munged = SessionDescription(SessionDescription.Type.OFFER, Sdp.preferH264(offer.description))
            conn.awaitSetLocal(munged)
            // WHIP is one HTTP exchange, not trickle ICE: wait for gathering, capped (the web studio waits 3 s).
            withTimeoutOrNull(3000) { while (!l.gathered) delay(50) }
            val localSdp = conn.localDescription?.description ?: munged.description
            if (!Sdp.hasH264(localSdp)) throw NoH264Exception("The offer contains no H.264. ${CodecProbe.summary()}")

            trace("pc #$gen WHIP POST")
            val session = whip.publish(cfg.whipUrl, localSdp)
            l.resourceUrl = session.resourceUrl
            trace("pc #$gen WHIP answer received")
            conn.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, session.answerSdp))

            val ok = withTimeoutOrNull(15_000) {
                while (l.state != PeerConnection.PeerConnectionState.CONNECTED) {
                    if (l.state == PeerConnection.PeerConnectionState.FAILED) throw IllegalStateException("Connection to the StreamBird ingest failed (ICE). Check the network/UDP.")
                    delay(100)
                }
                true
            }
            if (ok == null) throw IllegalStateException("Timed out connecting to the StreamBird ingest (15 s).")
            trace("pc #$gen connected")
            return l
        } catch (t: Throwable) {
            // Failed or cancelled attempt: hang up what the server may already have accepted, and free the new connection.
            trace("pc #$gen attempt failed: ${t.message ?: t.javaClass.simpleName}")
            val res = link?.resourceUrl
            link?.closed = true
            withContext(kotlinx.coroutines.NonCancellable + pcDispatcher) {
                runCatching { whip.hangUp(res) }
                runCatching { conn.close() }
                runCatching { conn.dispose() }
            }
            throw t
        }
    }

    /**
     * Recovers a dead transport WITHOUT touching camera, compositor, encoder pipeline, tracks or the server-side stream.
     * Only runs when something is wrong, so the old connection is treated as dead: it is closed FIRST (frees its hardware encoder: many
     * phones allow only 1-2 at once and both share one VideoTrack), then a new PeerConnection is negotiated with a fresh WHIP POST to
     * the same URL, and only when media really flows on it (bytes sent advance) is the old WHIP resource DELETEd.
     * Shares one lock with [stop]; fails once the engine is stopped. Throws on failure; the caller retries with backoff.
     */
    override suspend fun republish() {
        lifecycle.exclusive {
            withContext(pcDispatcher) {
                val cfg = config ?: error("not started")
                val old = cur
                trace("republish: start (old pc #${old?.gen})")
                if (old != null && !old.closed) {
                    old.closed = true
                    runCatching { old.conn.close() }
                    retired.add(old)
                }
                val link = connect(cfg)
                try {
                    awaitMediaFlowing(link)
                } catch (t: Throwable) {
                    link.closed = true
                    withContext(kotlinx.coroutines.NonCancellable + pcDispatcher) {
                        runCatching { whip.hangUp(link.resourceUrl) }; runCatching { link.conn.close() }; runCatching { link.conn.dispose() }
                    }
                    throw t
                }
                if (stopped) { // stop() won while we were negotiating: do not leak the new link
                    link.closed = true
                    runCatching { whip.hangUp(link.resourceUrl) }; runCatching { link.conn.close() }; runCatching { link.conn.dispose() }
                    throw kotlinx.coroutines.CancellationException("engine stopped")
                }
                cur = link; activeGen = link.gen
                prevStats = null
                applySenderParameters(cfg)
                trace("republish: swapped to pc #${link.gen}")
                if (old != null) cleanupJobs.add(scope.launch(pcDispatcher) { disposeRetired(old) })
            }
        }
    }

    private suspend fun disposeRetired(old: Link) {
        if (!retired.remove(old)) return
        runCatching { whip.hangUp(old.resourceUrl) }
        runCatching { old.conn.close() }
        awaitStatsIdle()
        delay(1000)
        runCatching { old.conn.dispose() }
    }

    /** CONNECTED (ICE/DTLS) does not prove media flows: wait until the new sender's bytesSent advance (up to 5 s). */
    private suspend fun awaitMediaFlowing(link: Link) {
        val ok = withTimeoutOrNull(5000) {
            var first: Long? = null
            while (true) {
                val raw = readStats(link)
                val bytes = StatsParser.parse(raw)?.bytesSent
                if (bytes != null) { if (first == null) first = bytes else if (bytes > first + 1000) return@withTimeoutOrNull true }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (ok != true) throw IllegalStateException("The new connection is up but no video is flowing on it.")
        trace("pc #${link.gen} media flowing")
    }

    private suspend fun awaitStatsIdle() { withTimeoutOrNull(4000) { while (statsInFlight.get() > 0) delay(50) } }

    /** Debug aid: kills the transport the way a network switch would (the connection closes under the running stream). */
    override fun simulateDrop() {
        scope.launch(pcDispatcher) {
            val l = cur
            trace("simulate drop (pc #${l?.gen})")
            l?.closed = true
            runCatching { l?.conn?.close() }
            listener.onConnection("CLOSED")
        }
    }

    override fun setTicker(spec: TickerSpec?) { compositor?.ticker = spec }

    /**
     * Camera liveness: the compositor counts frames it composed. No new frame for 5 s -> restart the capture (up to 3 times, each reported to the UI);
     * still nothing -> [EngineListener.onCameraFatal], so the user sees a clear message instead of a "Live" stream without a picture.
     */
    private fun startCameraWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var last = -1L
            var staleSince = System.currentTimeMillis()
            var attempts = 0
            while (isActive) {
                delay(1000)
                val f = compositor?.composedFrames ?: continue
                val now = System.currentTimeMillis()
                if (f != last) {
                    last = f; staleSince = now
                    if (attempts > 0) { attempts = 0; listener.onEvent("camera recovered"); listener.onCameraRecovered() }
                } else if (now - staleSince > CAMERA_STALL_MS) {
                    attempts++
                    staleSince = now
                    if (attempts > CAMERA_RETRIES) {
                        listener.onEvent("camera gave up after $CAMERA_RETRIES restarts")
                        listener.onCameraFatal("The camera stopped delivering pictures and could not be restarted." + (compositor?.lastError?.let { " ($it)" } ?: ""))
                        return@launch
                    }
                    listener.onEvent("camera delivers no frames, restarting capture ($attempts/$CAMERA_RETRIES)")
                    listener.onCameraProblem("The camera stopped delivering pictures. Retrying ($attempts/$CAMERA_RETRIES)...")
                    runCatching { capturer?.stopCapture() }
                    runCatching { captureSize?.let { capturer?.startCapture(it.width, it.height, FPS) } }
                }
            }
        }
    }

    /** maxBitrate per tier (web: sender.setParameters), degradationPreference (web: applyPicturePriority), BWE start/max hints. Run on [pcDispatcher]. */
    private fun applySenderParameters(cfg: EngineConfig) {
        val l = cur ?: return
        if (l.closed) return
        try {
            val sender = l.sender
            val p = sender.parameters
            p.encodings.firstOrNull()?.let {
                it.maxBitrateBps = cfg.tier.maxBitrateBps
                it.maxFramerate = FPS
            }
            p.degradationPreference = when (cfg.priority) {
                PicturePriority.MAINTAIN_RESOLUTION -> RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                PicturePriority.BALANCED -> RtpParameters.DegradationPreference.BALANCED
            }
            val ok = sender.setParameters(p)
            // Without a hint the bandwidth estimator starts near 300 kbps and ramps slowly -> low-res start.
            val bwe = l.conn.setBitrate(cfg.tier.maxBitrateBps / 10, cfg.tier.startBitrateBps, cfg.tier.maxBitrateBps)
            listener.onEvent("sender params applied=$ok maxBitrate=${cfg.tier.maxBitrateBps} priority=${cfg.priority} bwe=$bwe")
        } catch (t: Throwable) {
            trace("applySenderParameters failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override fun setPicturePriority(p: PicturePriority) {
        val cfg = config ?: return
        config = cfg.copy(priority = p)
        scope.launch(pcDispatcher) { applySenderParameters(config!!) }
    }

    // ---- stats ----

    private fun startStats() {
        statsJob = scope.launch {
            while (isActive) {
                try {
                    val raw = readStats()
                    val cur = StatsParser.parse(raw)
                    if (cur != null) {
                        val kbps = StatsParser.bitrateBetween(prevStats, cur)?.div(1000.0)
                        prevStats = cur
                        listener.onStats(cur, kbps, StatsParser.isHardwareEncoder(cur.encoderImpl, cur.powerEfficientEncoder))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    trace("stats error: ${t.message ?: t.javaClass.simpleName}")
                }
                delay(2000)
            }
        }
    }

    /** getStats on the PC thread, only for the active, not-closed connection; never waits forever (a closing PC may not answer). */
    private suspend fun readStats(link: Link? = null): List<RawStat> {
        val l = link ?: cur ?: return emptyList()
        if (l.closed) return emptyList()
        statsInFlight.incrementAndGet()
        try {
            return withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<List<RawStat>> { cont ->
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun finish(v: List<RawStat>) { if (done.compareAndSet(false, true) && cont.isActive) cont.resume(v) }
                    try {
                        pcExecutor.execute {
                            try {
                                if (l.closed) finish(emptyList())
                                else l.conn.getStats { report -> finish(report.statsMap.values.map { RawStat(it.type, it.id, it.timestampUs, it.members) }) }
                            } catch (t: Throwable) { finish(emptyList()) }
                        }
                    } catch (t: Throwable) { finish(emptyList()) }
                }
            } ?: emptyList()
        } finally {
            // The callback may still arrive after a timeout; give it a moment before anyone disposes the connection.
            scope.launch { delay(500); statsInFlight.decrementAndGet() }
        }
    }

    // ---- teardown ----

    /**
     * Idempotent, concurrency-safe teardown. The first caller does the work, later callers wait for it. The work itself is NonCancellable
     * (a cancelled caller must not leave the camera/encoder half released); only the waits inside it are bounded. Order: camera frames stop,
     * connection closed and disposed, then tracks, sources, capturer, factory, audio module, EGL, executors.
     */
    override suspend fun stop() {
        lifecycle.stop { gotLock ->
            runCatching { watchdogJob?.cancel() }
            runCatching { statsJob?.cancel(); statsJob?.join() }
            runCatching { capturer?.stopCapture() }
            val l = cur
            l?.closed = true
            withContext(Dispatchers.IO) { runCatching { withTimeoutOrNull(3000) { whip.hangUp(l?.resourceUrl) } } }
            withContext(pcDispatcher) { runCatching { l?.conn?.close() } }
            withTimeoutOrNull(4000) { cleanupJobs.forEach { it.cancelAndJoin() } }
            awaitStatsIdle()
            withContext(pcDispatcher) {
                runCatching { l?.conn?.dispose() }
                // old connections whose deferred cleanup never ran
                while (true) { val o = retired.poll() ?: break; runCatching { o.conn.close() }; runCatching { o.conn.dispose() } }
            }
            withContext(Dispatchers.IO) {
                runCatching { videoTrack?.dispose() }
                runCatching { audioTrack?.dispose() }
                runCatching { videoSource?.dispose() }
                runCatching { audioSource?.dispose() }
                runCatching { compositor?.release() }
                runCatching { capturer?.dispose() }
                runCatching { helper?.dispose() }
                runCatching { factory?.dispose() }
                runCatching { adm?.release() }
                runCatching { eglBase?.release() }
            }
            cur = null; preview = null; factory = null; capturer = null
            runCatching { pcExecutor.shutdown() }
            runCatching { scope.cancel() }
            if (!gotLock) trace("stop: republish lock timed out, tore down anyway")
        }
    }

    // ---- SDP plumbing ----

    private suspend fun PeerConnection.awaitOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(d: SessionDescription) = cont.resume(d)
            override fun onCreateFailure(e: String) = cont.resumeWithException(IllegalStateException("createOffer: $e"))
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.awaitSetLocal(d: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(object : SimpleSdp() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String) = cont.resumeWithException(IllegalStateException("setLocalDescription: $e"))
        }, d)
    }

    private suspend fun PeerConnection.awaitSetRemote(d: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(e: String) = cont.resumeWithException(IllegalStateException("setRemoteDescription: $e"))
        }, d)
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) {}
        override fun onSetFailure(e: String) {}
    }

    companion object {
        const val FPS = 30
        const val CAMERA_STALL_MS = 5_000L
        const val CAMERA_RETRIES = 3
        @Volatile private var initialized = false

        @Synchronized
        private fun initFactoryOnce(ctx: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            initialized = true
        }
    }
}
