package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals as eq
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class StatsParserTest {
    // Shapes follow what libwebrtc's Java RTCStats.getMembers() returns: Long/Integer/Double/BigInteger/String/Boolean.
    private fun outbound(ts: Double, bytes: Any, w: Any = 720L, h: Any = 1280L, reason: String = "none", impl: String = "HWEncoder") = RawStat(
        "outbound-rtp", "OT1", ts,
        mapOf(
            "kind" to "video", "frameWidth" to w, "frameHeight" to h, "framesPerSecond" to 29.7, "bytesSent" to bytes,
            "qualityLimitationReason" to reason, "encoderImplementation" to impl, "framesEncoded" to 300L, "totalEncodeTime" to 3.0,
            "keyFramesEncoded" to 5L, "nackCount" to 2L, "pliCount" to 1L, "retransmittedPacketsSent" to 7L,
        ),
    )

    private val remote = RawStat("remote-inbound-rtp", "RIV", 0.0, mapOf("kind" to "video", "roundTripTime" to 0.042, "fractionLost" to 0.015))
    private val pair = RawStat("candidate-pair", "CP", 0.0, mapOf("nominated" to true, "state" to "succeeded", "currentRoundTripTime" to 0.050, "availableOutgoingBitrate" to 2_500_000.0))

    @Test fun `parses outbound rtp video with remote RTT and loss`() {
        val st = StatsParser.parse(listOf(RawStat("outbound-rtp", "OA", 0.0, mapOf("kind" to "audio")), outbound(2_000_000.0, BigInteger.valueOf(500_000)), remote, pair))!!
        assertEquals(720, st.width); assertEquals(1280, st.height)
        assertEquals(29.7, st.fps, 0.001)
        assertEquals(500_000L, st.bytesSent)
        assertEquals(2000.0, st.timestampMs, 0.001)
        assertNull(st.limitationReason)
        assertEquals("HWEncoder", st.encoderImpl)
        assertEquals(42.0, st.rttMs!!, 0.001)       // remote-inbound preferred over candidate pair
        assertEquals(1.5, st.lossPct!!, 0.001)
        assertEquals(2_500_000.0, st.availableOutgoingBps!!, 0.1)
        assertEquals(10.0, st.avgEncodeMs!!, 0.001)  // 3.0 s / 300 frames
        assertEquals(5L, st.keyFramesEncoded); assertEquals(2L, st.nackCount); assertEquals(1L, st.pliCount)
    }

    @Test fun `rtt falls back to the candidate pair and limitation none maps to null`() {
        val st = StatsParser.parse(listOf(outbound(0.0, 1L, reason = "bandwidth"), pair))!!
        assertEquals("bandwidth", st.limitationReason)
        assertEquals(50.0, st.rttMs!!, 0.001)
        assertNull(st.lossPct)
    }

    @Test fun `no video outbound or zero size gives null`() {
        assertNull(StatsParser.parse(emptyList()))
        assertNull(StatsParser.parse(listOf(outbound(0.0, 1L, w = 0L, h = 0L))))
    }

    @Test fun `numbers of any boxed type are accepted`() {
        val st = StatsParser.parse(listOf(outbound(0.0, 12345, w = 540, h = 960.0)))!!
        assertEquals(540, st.width); assertEquals(960, st.height); assertEquals(12345L, st.bytesSent)
    }

    @Test fun `bitrate is computed from bytesSent deltas`() {
        val a = StatsParser.parse(listOf(outbound(1_000_000.0, 0L)))!!      // 1000 ms
        val b = StatsParser.parse(listOf(outbound(3_000_000.0, 750_000L)))!! // 3000 ms
        eq(3_000_000.0, StatsParser.bitrateBetween(a, b)!!, 0.5)             // 750000 B * 8 / 2 s
        assertNull(StatsParser.bitrateBetween(null, b))
        assertNull(StatsParser.bitrateBetween(b, a))                          // time and counters going backwards
    }

    @Test fun `hardware detection by implementation name`() {
        assertTrue(StatsParser.isHardwareEncoder("HWEncoder")!!)
        assertTrue(StatsParser.isHardwareEncoder("MediaCodec")!!)
        assertFalse(StatsParser.isHardwareEncoder("OpenH264")!!)
        assertFalse(StatsParser.isHardwareEncoder("libvpx")!!)
        assertNull(StatsParser.isHardwareEncoder("SomethingNew"))
        assertNull(StatsParser.isHardwareEncoder(null))
        assertTrue("powerEfficient wins", StatsParser.isHardwareEncoder("OpenH264", true)!!)
    }
}
