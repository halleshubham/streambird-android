package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TiersTest {
    @Test fun `tiers follow the web RESOLUTIONS bitrates and are 9 by 16`() {
        assertEquals(listOf(1_000_000, 3_000_000, 6_000_000), StreamTier.entries.map { it.maxBitrateBps })
        StreamTier.entries.forEach { assertEquals(it.width * 16, it.height * 9) }
        assertEquals(1920, StreamTier.P1080.height)
    }

    @Test fun `plan max resolution limits tiers`() {
        assertEquals(listOf(StreamTier.P540), StreamTier.allowed("sd"))
        assertEquals(listOf(StreamTier.P540, StreamTier.P720), StreamTier.allowed("hd"))
        assertEquals(StreamTier.entries.toList(), StreamTier.allowed("fhd"))
        assertEquals(StreamTier.entries.toList(), StreamTier.allowed("qhd"))
        assertEquals(StreamTier.P540, StreamTier.best("nonsense"))
        assertEquals(StreamTier.P720, StreamTier.best("hd"))
    }

    @Test fun `destination limit`() {
        assertTrue(canSelectMore(0, 1)); assertFalse(canSelectMore(1, 1)); assertTrue(canSelectMore(2, 3))
    }

    private data class Kit(val id: String, val default: Boolean, val conns: List<String>)

    @Test fun `brand kit choice - assigned kit for a single channel, else default, else first`() {
        val kits = listOf(Kit("a", false, listOf("c1")), Kit("b", true, emptyList()))
        fun pick(sel: Set<String>) = pickBrandKit(kits, sel, { it.default }, { it.conns })?.id
        assertEquals("a", pick(setOf("c1")))
        assertEquals("b", pick(setOf("c2")))
        assertEquals("a", pick(setOf("c1", "c2")))          // first ticked destination that has a kit
        assertEquals("a", pick(linkedSetOf("c2", "c1")))     // c2 has none, c1 does
        assertEquals("b", pick(setOf("c2", "c3")))
        assertEquals("a", pickBrandKit(listOf(Kit("a", false, emptyList())), emptySet(), { it.default }, { it.conns })?.id)
        assertNull(pickBrandKit(emptyList<Kit>(), setOf("c1"), { it.default }, { it.conns }))
    }

    @Test fun `aspect fill crop`() {
        // landscape source into a portrait output keeps a narrow strip of the width
        val (wx, wy) = FrameCompositor.cropScale(1920, 1080, 1080, 1920)
        assertEquals(0.3164f, wx, 0.0001f); assertEquals(1f, wy, 0f)
        assertEquals(Pair(1f, 1f), FrameCompositor.cropScale(1080, 1920, 540, 960))
        val (sx, sy) = FrameCompositor.cropScale(1080, 1920, 1080, 1080)
        assertEquals(1f, sx, 0f); assertEquals(0.5625f, sy, 0.0001f)
    }
}
