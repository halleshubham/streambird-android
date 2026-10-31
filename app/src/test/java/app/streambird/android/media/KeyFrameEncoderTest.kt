package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame

class KeyFrameEncoderTest {
    private class FakeEncoder : VideoEncoder {
        val sent = ArrayList<Array<EncodedImage.FrameType>>()
        var inits = 0; var nativeRef = -1L
        override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus { inits++; return VideoCodecStatus.OK }
        override fun release() = VideoCodecStatus.OK
        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus { sent.add(info.frameTypes.clone()); return VideoCodecStatus.OK }
        override fun setRateAllocation(a: VideoEncoder.BitrateAllocation, f: Int) = VideoCodecStatus.OK
        override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF
        override fun getImplementationName() = "fake"
        override fun createNative(webrtcEnvRef: Long): Long { nativeRef = webrtcEnvRef; return 42L }
    }

    private val frame: VideoFrame = VideoFrame(object : VideoFrame.Buffer {
        override fun getWidth() = 2; override fun getHeight() = 2
        override fun toI420(): VideoFrame.I420Buffer? = null
        override fun retain() {}; override fun release() {}
        override fun cropAndScale(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int): VideoFrame.Buffer = this
    }, 0, 0L)

    private fun delta() = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))
    private fun key() = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameKey))

    @Test fun `forces a key frame when the interval is due, otherwise passes delta frames through`() {
        val inner = FakeEncoder(); val enc = KeyFrameEncoder(inner, 80)
        enc.encode(frame, delta())                                       // first call: lastKey = 0 so it is due -> key
        enc.encode(frame, delta()); enc.encode(frame, delta())           // not due yet
        Thread.sleep(100)
        enc.encode(frame, delta())                                       // due again
        assertEquals(listOf(EncodedImage.FrameType.VideoFrameKey), inner.sent[0].toList())
        assertEquals(EncodedImage.FrameType.VideoFrameDelta, inner.sent[1][0]); assertEquals(EncodedImage.FrameType.VideoFrameDelta, inner.sent[2][0])
        assertEquals(EncodedImage.FrameType.VideoFrameKey, inner.sent[3][0])
    }

    @Test fun `a keyframe the receiver asked for passes through and restarts the interval`() {
        val inner = FakeEncoder(); val enc = KeyFrameEncoder(inner, 10_000)
        enc.encode(frame, delta())                                       // forced key (start)
        enc.encode(frame, key())                                         // requested by PLI
        enc.encode(frame, delta())                                       // interval restarted by the PLI key: delta
        assertEquals(listOf(true, true, false), inner.sent.map { it[0] == EncodedImage.FrameType.VideoFrameKey })
    }

    @Test fun `initEncode resets the interval and delegates, createNative delegates`() {
        val inner = FakeEncoder(); val enc = KeyFrameEncoder(inner, 10_000)
        enc.encode(frame, delta())                                       // key, interval now running
        enc.encode(frame, delta())                                       // delta
        enc.initEncode(VideoEncoder.Settings(1, 1280, 720, 1000, 30, 1, false, null), VideoEncoder.Callback { _, _ -> })
        enc.encode(frame, delta())                                       // a re-initialised encoder starts with a key frame again
        assertEquals(1, inner.inits)
        assertEquals(listOf(true, false, true), inner.sent.map { it[0] == EncodedImage.FrameType.VideoFrameKey })
        assertEquals(42L, enc.createNative(7L)); assertEquals(7L, inner.nativeRef)
        assertTrue(enc.implementationName == "fake")
    }
}
