package app.streambird.android.media

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame

/**
 * libwebrtc's Android MediaCodec H.264 encoder only emits a keyframe every ~20 s (plus on PLI/FIR). RTMP destinations
 * (YouTube/Facebook/Twitch) want one every 2-4 s, and the downstream path here is `ffmpeg -c:v copy`, so the GOP the phone
 * produces is the GOP the platform sees. The web studio works around the same thing with a 2 s `generateKeyFrame()` timer;
 * Android's Java API has no such method, so this wrapper turns every [intervalMs]-th frame request into a keyframe request.
 */
class KeyFrameEncoderFactory(
    private val inner: VideoEncoderFactory,
    private val intervalMs: Long = 2000,
) : VideoEncoderFactory {
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        val enc = inner.createEncoder(info) ?: return null
        return if (info.name.equals("H264", ignoreCase = true)) KeyFrameEncoder(enc, intervalMs) else enc
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs
    override fun getImplementations(): Array<VideoCodecInfo> = inner.implementations
    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = inner.encoderSelector
}

class KeyFrameEncoder(private val d: VideoEncoder, private val intervalMs: Long) : VideoEncoder {
    @Volatile private var lastKeyMs = 0L

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        lastKeyMs = 0L
        return d.initEncode(settings, callback)
    }

    override fun release(): VideoCodecStatus = d.release()

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val now = System.nanoTime() / 1_000_000
        val requested = info.frameTypes.any { it == EncodedImage.FrameType.VideoFrameKey }
        val due = now - lastKeyMs >= intervalMs
        return if (requested || due) {
            lastKeyMs = now
            if (requested) d.encode(frame, info)
            else d.encode(frame, VideoEncoder.EncodeInfo(Array(info.frameTypes.size) { EncodedImage.FrameType.VideoFrameKey }))
        } else d.encode(frame, info)
    }

    @Suppress("DEPRECATION")
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, frameRate: Int): VideoCodecStatus =
        d.setRateAllocation(allocation, frameRate)

    override fun setRates(params: VideoEncoder.RateControlParameters): VideoCodecStatus = d.setRates(params)
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = d.scalingSettings
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = d.resolutionBitrateLimits
    override fun getImplementationName(): String = d.implementationName
    override fun createNative(webrtcEnvRef: Long): Long = d.createNative(webrtcEnvRef)
    override fun isHardwareEncoder(): Boolean = d.isHardwareEncoder
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = d.encoderInfo
}
