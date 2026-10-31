package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendQualityTest {
    private fun s(w: Int, h: Int, fps: Double = 30.0, bps: Double? = 3_000_000.0, reason: String? = null) = SendSample(w, h, fps, bps, reason)

    @Test fun `full size and no limitation is ok`() {
        val q = classifySendQuality(s(720, 1280), 720, 1280)
        assertFalse(q.limited); assertEquals(null, q.message)
        assertEquals("Sending 720x1280 - 30 fps - 3.0 Mbps", q.summary)
    }

    @Test fun `bandwidth and cpu limitation warn whatever the size`() {
        assertTrue(classifySendQuality(s(720, 1280, reason = "bandwidth"), 720, 1280).limited)
        assertTrue(classifySendQuality(s(720, 1280, reason = "cpu"), 720, 1280).message!!.contains("phone"))
    }

    @Test fun `picture shorter than 75 percent of the chosen tier warns (short side, so portrait works)`() {
        // chosen 1080x1920; sent 540x960 -> 540 < 0.75 * 1080
        assertTrue(classifySendQuality(s(540, 960), 1080, 1920).limited)
        // 810 is exactly 75 percent: not below
        assertFalse(classifySendQuality(s(810, 1440), 1080, 1920).limited)
        // 800 is below 810
        assertTrue(classifySendQuality(s(800, 1422), 1080, 1920).limited)
    }

    @Test fun `warming up suppresses only the size rule`() {
        assertFalse(classifySendQuality(s(360, 640), 1080, 1920, warmingUp = true).limited)
        assertTrue(classifySendQuality(s(360, 640, reason = "bandwidth"), 1080, 1920, warmingUp = true).limited)
    }

    @Test fun `formatBitrate matches the web helper`() {
        assertEquals("-- Mbps", formatBitrate(null))
        assertEquals("-- Mbps", formatBitrate(-1.0))
        assertEquals("850 kbps", formatBitrate(850_000.0))
        assertEquals("3.1 Mbps", formatBitrate(3_100_000.0))
    }

    @Test fun `reason other is not flagged by itself (web rule)`() {
        assertFalse(classifySendQuality(s(720, 1280, reason = "other"), 720, 1280).limited)
    }

    @Test fun `messages follow the web texts adapted for a phone`() {
        assertEquals("Your connection is limiting quality. Try a lower quality or a better connection.", classifySendQuality(s(720, 1280, reason = "bandwidth"), 720, 1280).message)
        assertEquals("Your phone is struggling. Try a lower quality.", classifySendQuality(s(720, 1280, reason = "cpu"), 720, 1280).message)
        assertEquals(
            "The picture is being sent smaller than the 1080p you chose. Try a lower quality or a better connection.",
            classifySendQuality(s(540, 960), 1080, 1920).message,
        )
    }

    @Test fun `chip state mapping`() {
        val waiting = qualityChip(null)
        assertFalse(waiting.limited); assertEquals(null, waiting.reason)
        val ok = qualityChip(classifySendQuality(s(720, 1280, fps = 29.6, bps = 2_400_000.0), 720, 1280))
        assertEquals("Sending 720x1280 - 30 fps - 2.4 Mbps", ok.text); assertFalse(ok.limited); assertEquals(null, ok.reason)
        val amber = qualityChip(classifySendQuality(s(720, 1280, reason = "cpu"), 720, 1280))
        assertTrue(amber.limited); assertEquals(MSG_CPU, amber.reason)
        assertEquals(6L, WARMUP_SECONDS)
    }
}
