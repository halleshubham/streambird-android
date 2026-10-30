package app.streambird.android.whip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTest {
    private val offer = listOf(
        "v=0", "o=- 1 2 IN IP4 127.0.0.1", "s=-", "t=0 0",
        "m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=rtpmap:111 opus/48000/2",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 104 105",
        "a=rtpmap:96 VP8/90000", "a=rtpmap:97 rtx/90000", "a=fmtp:97 apt=96",
        "a=rtpmap:102 H264/90000", "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "a=rtpmap:103 rtx/90000", "a=fmtp:103 apt=102",
        "a=rtpmap:104 H264/90000", "a=fmtp:104 packetization-mode=1;profile-level-id=640c1f",
        "a=rtpmap:105 rtx/90000", "a=fmtp:105 apt=104", "",
    ).joinToString("\r\n")

    @Test fun `H264 and its rtx move to the front of the video m line`() {
        val out = Sdp.preferH264(offer)
        assertTrue(out.contains("m=video 9 UDP/TLS/RTP/SAVPF 102 104 103 105 96 97\r\n"))
        assertTrue("audio untouched", out.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertEquals("all other lines are unchanged", offer.lines().filterNot { it.startsWith("m=video") }, out.lines().filterNot { it.startsWith("m=video") })
    }

    @Test fun `offer without H264 is returned unchanged`() {
        val noH264 = offer.lines().filterNot { it.contains("H264") || it.contains("profile-level-id") }.joinToString("\r\n")
        assertFalse(Sdp.hasH264(noH264))
        assertEquals(noH264, Sdp.preferH264(noH264))
    }

    @Test fun `hasH264 and payload listing`() {
        assertTrue(Sdp.hasH264(offer))
        assertEquals(listOf("VP8", "rtx", "H264", "rtx", "H264", "rtx"), Sdp.videoPayloads(offer).map { it.codec })
    }

    @Test fun `no video section is a no-op`() {
        val a = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"
        assertEquals(a, Sdp.preferH264(a))
    }
}
