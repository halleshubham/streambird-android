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
    val priority: PicturePriority,
    val frontCamera: Boolean,
    val h264HighProfile: Boolean,
    val forceKeyFrames: Boolean,
    val logo: LogoOverlay?,
    val iceServers: List<IceServerSpec>,
    val whipUrl: String,
)

class NoH264Exception(message: String) : Exception(message)

/** What the Live screen needs to render the composited preview. */
class PreviewHandle(val eglContext: EglBase.Context, val track: VideoTrack)

interface EngineListener {
    fun onStats(stats: EncoderStats, kbps: Double?, hardware: Boolean?)
    fun onEvent(text: String)
    /** CONNECTED / DISCONNECTED / FAILED / CLOSED etc. */
    fun onConnection(state: String)
}

/**
 * Owns the whole native pipeline: Camera2 -> FrameCompositor (GL) -> VideoSource -> H.264 encoder -> RTCPeerConnection -> WHIP.
 * One instance per stream. Everything here is UNVERIFIED on a device (no camera/encoder in the build sandbox).
 */
class StreamEngine(
    private val context: Context,
    private val whip: WhipClient,
    private val listener: EngineListener,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var statsJob: Job? = null

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
    private var pc: PeerConnection? = null
    private var videoSender: org.webrtc.RtpSender? = null
    private var resourceUrl: String? = null
    private var config: EngineConfig? = null
    private var prevStats: EncoderStats? = null

    @Volatile private var gatheringDone = false
    @Volatile private var connState: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW

    var preview: PreviewHandle? = null; private set
    val droppedFrames: Long get() = compositor?.droppedFrames ?: 0

    suspend fun start(cfg: EngineConfig) {
        config = cfg
        withContext(Dispatchers.Main) { buildPipeline(cfg) }
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
        val comp = FrameCompositor(source.capturerObserver, h, cfg.tier.width, cfg.tier.height, cfg.logo, unmirror = cfg.frontCamera)
        compositor = comp

        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { if (cfg.frontCamera) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera found")
        val cam = enumerator.createCapturer(name, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String) = listener.onEvent("camera error: $errorDescription")
            override fun onCameraDisconnected() = listener.onEvent("camera disconnected")
            override fun onCameraFreezed(errorDescription: String) = listener.onEvent("camera froze: $errorDescription")
            override fun onCameraOpening(cameraName: String) {}
            override fun onFirstFrameAvailable() = listener.onEvent("camera first frame")
            override fun onCameraClosed() {}
        }) ?: throw IllegalStateException("Could not open camera $name")
        capturer = cam
        cam.initialize(h, context, comp)
        val size = CameraEnumerationAndroid.getClosestSupportedSize(
            enumerator.getSupportedFormats(name).orEmpty().map { org.webrtc.Size(it.width, it.height) }, cfg.tier.captureWidth, cfg.tier.captureHeight,
        )
        cam.startCapture(size.width, size.height, FPS)
        listener.onEvent("camera $name capture ${size.width}x${size.height}@$FPS -> output ${cfg.tier.width}x${cfg.tier.height}")
        source.adaptOutputFormat(cfg.tier.width, cfg.tier.height, FPS)

        val vt = f.createVideoTrack("sb-video", source).also { videoTrack = it }
        val asrc = f.createAudioSource(MediaConstraints()).also { audioSource = it }
        audioTrack = f.createAudioTrack("sb-audio", asrc)
        preview = PreviewHandle(egl.eglBaseContext, vt)
    }

    private suspend fun publish(cfg: EngineConfig) {
        val f = factory!!
        val rtc = PeerConnection.RTCConfiguration(
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
        val conn = f.createPeerConnection(rtc, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) gatheringDone = true }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                connState = s
                listener.onConnection(s.name)
                listener.onEvent("peer connection $s")
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) { listener.onEvent("ice $s") }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: throw IllegalStateException("PeerConnection could not be created")
        pc = conn

        val vTrans = conn.addTransceiver(videoTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        conn.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        videoSender = vTrans.sender

        // H.264 first (web: setCodecPreferences). Belt and braces: the offer SDP is also re-ordered below.
        val codecs = f.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        val h264 = codecs.filter { it.name.equals("H264", true) }
        vTrans.setCodecPreferences(h264 + codecs.filterNot { it.name.equals("H264", true) })

        val offer = conn.awaitOffer()
        val munged = SessionDescription(SessionDescription.Type.OFFER, Sdp.preferH264(offer.description))
        conn.awaitSetLocal(munged)
        // WHIP is one HTTP exchange, not trickle ICE: wait for gathering, capped (the web studio waits 3 s).
        withTimeoutOrNull(3000) { while (!gatheringDone) delay(50) }
        val localSdp = conn.localDescription?.description ?: munged.description
        if (!Sdp.hasH264(localSdp)) throw NoH264Exception("The offer contains no H.264. ${CodecProbe.summary()}")

        val session = whip.publish(cfg.whipUrl, localSdp)
        resourceUrl = session.resourceUrl
        conn.awaitSetRemote(SessionDescription(SessionDescription.Type.ANSWER, session.answerSdp))

        applySenderParameters(cfg)
        val ok = withTimeoutOrNull(15_000) {
            while (connState != PeerConnection.PeerConnectionState.CONNECTED) {
                if (connState == PeerConnection.PeerConnectionState.FAILED) throw IllegalStateException("Connection to the StreamBird ingest failed (ICE). Check the network/UDP.")
                delay(100)
            }
            true
        }
        if (ok == null) throw IllegalStateException("Timed out connecting to the StreamBird ingest (15 s).")
        startStats()
    }

    /** maxBitrate per tier (web: sender.setParameters), degradationPreference (web: applyPicturePriority), BWE start/max hints. */
    private fun applySenderParameters(cfg: EngineConfig) {
        val sender = videoSender ?: return
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
        val bwe = pc?.setBitrate(cfg.tier.maxBitrateBps / 10, cfg.tier.startBitrateBps, cfg.tier.maxBitrateBps)
        listener.onEvent("sender params applied=$ok maxBitrate=${cfg.tier.maxBitrateBps} priority=${cfg.priority} bwe=$bwe")
    }

    fun setPicturePriority(p: PicturePriority) {
        val cfg = config ?: return
        config = cfg.copy(priority = p)
        applySenderParameters(config!!)
    }

    // ---- stats ----

    private fun startStats() {
        statsJob = scope.launch {
            while (isActive) {
                val raw = suspendCancellableCoroutine<List<RawStat>> { cont ->
                    val c = pc
                    if (c == null) cont.resume(emptyList())
                    else c.getStats { report ->
                        cont.resume(report.statsMap.values.map { RawStat(it.type, it.id, it.timestampUs, it.members) })
                    }
                }
                val cur = StatsParser.parse(raw)
                if (cur != null) {
                    val kbps = StatsParser.bitrateBetween(prevStats, cur)?.div(1000.0)
                    prevStats = cur
                    listener.onStats(cur, kbps, StatsParser.isHardwareEncoder(cur.encoderImpl, cur.powerEfficientEncoder))
                }
                delay(2000)
            }
        }
    }

    // ---- teardown ----

    suspend fun stop() {
        statsJob?.cancel()
        withContext(Dispatchers.IO) {
            runCatching { whip.hangUp(resourceUrl) }
            runCatching { capturer?.stopCapture() }
            runCatching { pc?.close() }
            runCatching { compositor?.release() }
            runCatching { capturer?.dispose() }
            runCatching { videoTrack?.dispose() }
            runCatching { audioTrack?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { pc?.dispose() }
            runCatching { helper?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            runCatching { eglBase?.release() }
        }
        preview = null; pc = null; factory = null; capturer = null
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
        @Volatile private var initialized = false

        @Synchronized
        private fun initFactoryOnce(ctx: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
            initialized = true
        }
    }
}
