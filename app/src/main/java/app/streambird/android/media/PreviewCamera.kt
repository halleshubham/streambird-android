package app.streambird.android.media

import android.content.Context
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * The camera-first screen's picture BEFORE going live: the same WebRTC Camera2 capturer the engine uses, drawn straight into a
 * [SurfaceViewRenderer] (no encoder, no network). It owns the camera only while it runs; [stop] releases it and MUST be called
 * before the stream starts (the engine opens the camera itself). Not unit-testable (real camera + GL); main thread only.
 */
class PreviewCamera(private val context: Context) {
    private var egl: EglBase? = null
    private var helper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var renderer: SurfaceViewRenderer? = null

    val running get() = capturer != null

    /** Starts [front]/back camera into [view]. Returns an error text, or null when the picture is coming. */
    fun start(view: SurfaceViewRenderer, front: Boolean): String? {
        stop()
        return try {
            val eglBase = EglBase.create().also { egl = it }
            view.init(eglBase.eglBaseContext, null)
            view.setEnableHardwareScaler(false)
            view.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            // The capturer hands the front camera over mirrored and the engine un-mirrors it for the stream, so un-mirror here too: the preview shows what viewers get.
            view.setMirror(front)
            renderer = view
            val en = Camera2Enumerator(context)
            val name = en.deviceNames.firstOrNull { if (front) en.isFrontFacing(it) else en.isBackFacing(it) } ?: en.deviceNames.firstOrNull()
                ?: return "No camera found on this phone.".also { stop() }
            val h = SurfaceTextureHelper.create("sb-preview", eglBase.eglBaseContext).also { helper = it }
            val cam = en.createCapturer(name, null) ?: return "The camera could not be opened.".also { stop() }
            cam.initialize(h, context, object : CapturerObserver {
                private val sink: VideoSink = view
                override fun onCapturerStarted(success: Boolean) {}
                override fun onCapturerStopped() {}
                override fun onFrameCaptured(frame: VideoFrame) { sink.onFrame(frame) }
            })
            cam.startCapture(1280, 720, 30)
            capturer = cam
            null
        } catch (t: Throwable) {
            stop()
            "Camera preview failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** Idempotent. Stops the capture, then frees the GL objects, in the order WebRTC needs. */
    fun stop() {
        val cam = capturer; capturer = null
        val h = helper; helper = null
        val r = renderer; renderer = null
        val e = egl; egl = null
        if (cam != null) {
            runCatching { cam.stopCapture() }
            runCatching { cam.dispose() }
        }
        runCatching { r?.release() }
        runCatching { h?.dispose() }
        runCatching { e?.release() }
    }
}
