package app.streambird.android.preset

import app.streambird.android.data.PlatformConnection
import app.streambird.android.data.Preset
import app.streambird.android.data.PresetConfig
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetLogicTest {
    private val yt = PlatformConnection("c1", "youtube", label = "My channel")
    private val fb = PlatformConnection("c2", "facebook", label = "My page")
    private val tw = PlatformConnection("c3", "twitch", label = "me")
    private val conns = listOf(yt, fb, tw)
    private val kits = mapOf("k1" to "Acme")
    private val dot = " · "

    private fun cur(sel: Set<String> = emptySet(), tier: StreamTier = StreamTier.P720, o: StreamOrientation = StreamOrientation.PORTRAIT, kit: String? = null, ticker: String = "") =
        SetupChoices(sel, tier, o, kit, ticker)

    private fun lim(max: Int = 3, tiers: List<StreamTier> = StreamTier.entries, brand: Boolean = true, known: Set<String>? = setOf("k1")) =
        SetupLimits(setOf("c1", "c2", "c3"), max, tiers, brand, known)

    // ---- summary text ----

    @Test fun `summary has destinations, brand, ticker and quality`() {
        val cfg = PresetConfig("portrait", listOf("c1", "c2"), "k1", "hd", true, "News")
        assertEquals("YouTube + Facebook${dot}Acme brand${dot}Ticker on${dot}720p", presetSummary(cfg, conns, kits::get, true))
    }

    @Test fun `summary variations`() {
        assertEquals("YouTube${dot}Auto brand${dot}Ticker off${dot}1080p${dot}16:9", presetSummary(PresetConfig("landscape", listOf("c1"), null, "fhd", false), conns, kits::get, true))
        assertEquals("YouTube${dot}Brand kit brand${dot}Ticker off", presetSummary(PresetConfig(destinationIds = listOf("c1"), brandKitId = "gone"), conns, kits::get, true))
        assertEquals("YouTube${dot}Ticker off${dot}540p", presetSummary(PresetConfig(destinationIds = listOf("c1"), quality = "sd"), conns, kits::get, false))
        assertEquals("No destination${dot}Auto brand${dot}Ticker off", presetSummary(PresetConfig(), conns, kits::get, true))
        // a disconnected channel is skipped
        assertEquals("Facebook${dot}Ticker off", presetSummary(PresetConfig(destinationIds = listOf("zzz", "c2")), conns, kits::get, false))
    }

    @Test fun `two channels of one platform are told apart by label`() {
        val a = PlatformConnection("a", "youtube", label = "Main"); val b = PlatformConnection("b", "youtube", label = "Second")
        assertEquals("YouTube (Main) + YouTube (Second)${dot}Ticker off", presetSummary(PresetConfig(destinationIds = listOf("a", "b")), listOf(a, b), kits::get, false))
        assertEquals("YouTube", connectionName(yt, conns))
    }

    @Test fun `setup line`() {
        assertEquals("Acme brand${dot}720p${dot}Ticker off", setupLine("Acme", StreamTier.P720, false))
        assertEquals("1080p${dot}Ticker on", setupLine(null, StreamTier.P1080, true))
    }

    // ---- quality / orientation wire ----

    @Test fun `quality maps both ways and tolerates other spellings`() {
        StreamTier.entries.forEach { assertEquals(it, tierFromWire(tierToWire(it))) }
        assertEquals(StreamTier.P720, tierFromWire(" 720P ")); assertEquals(StreamTier.P1080, tierFromWire("qhd")); assertEquals(StreamTier.P540, tierFromWire("P540"))
        assertNull(tierFromWire("ultra")); assertNull(tierFromWire(null))
        assertEquals(StreamOrientation.LANDSCAPE, orientationFromWire("Landscape")); assertNull(orientationFromWire("square"))
    }

    // ---- preset -> setup ----

    @Test fun `a full preset sets every part of the setup`() {
        val a = applyPresetConfig(PresetConfig("landscape", listOf("c2", "c1"), "k1", "fhd", true, "  Live \n now "), cur(sel = setOf("c3")), lim())
        assertEquals(listOf("c2", "c1"), a.choices.selected.toList())
        assertEquals(StreamTier.P1080, a.choices.tier); assertEquals(StreamOrientation.LANDSCAPE, a.choices.orientation)
        assertEquals("k1", a.choices.brandKitId); assertEquals("Live now", a.choices.tickerText)
        assertTrue(a.notes.isEmpty())
    }

    @Test fun `ticker off clears the text, an empty preset changes nothing`() {
        assertEquals("", applyPresetConfig(PresetConfig(tickerEnabled = false, tickerText = "kept in preset"), cur(ticker = "old"), lim()).choices.tickerText)
        val c = cur(setOf("c1"), StreamTier.P540, StreamOrientation.LANDSCAPE, "k1", "keep")
        val a = applyPresetConfig(PresetConfig(), c, lim())
        assertEquals(c, a.choices); assertTrue(a.notes.isEmpty())
    }

    @Test fun `disconnected channels, plan limits and deleted kits are dropped with a note`() {
        val a = applyPresetConfig(PresetConfig(destinationIds = listOf("gone", "c1", "c2"), brandKitId = "deadKit", quality = "fhd"), cur(), lim(max = 1, tiers = listOf(StreamTier.P540, StreamTier.P720)))
        assertEquals(listOf("c1"), a.choices.selected.toList())
        assertEquals(StreamTier.P720, a.choices.tier)
        assertNull(a.choices.brandKitId)
        assertEquals(4, a.notes.size)
        assertTrue(a.notes.any { "no longer connected" in it }); assertTrue(a.notes.any { "1 destination " in it }); assertTrue(a.notes.any { "1080p is above" in it }); assertTrue(a.notes.any { "deleted" in it })
    }

    @Test fun `a free plan never gets a brand kit and unloaded kits are not checked`() {
        assertNull(applyPresetConfig(PresetConfig(brandKitId = "k1"), cur(), lim(brand = false)).choices.brandKitId)
        assertEquals("k1", applyPresetConfig(PresetConfig(brandKitId = "k1"), cur(), lim(known = null)).choices.brandKitId)
        assertNull(applyPresetConfig(PresetConfig(destinationIds = listOf("c1")), cur(kit = "k1"), lim()).choices.brandKitId) // a preset without a kit = automatic
    }

    @Test fun `quality above the plan falls to the best allowed one, unknown quality keeps the current`() {
        assertEquals(StreamTier.P540, applyPresetConfig(PresetConfig(quality = "hd"), cur(), lim(tiers = listOf(StreamTier.P540))).choices.tier)
        assertEquals(StreamTier.P720, applyPresetConfig(PresetConfig(quality = "mystery"), cur(), lim()).choices.tier)
    }

    // ---- setup -> preset ----

    @Test fun `saving writes the current setup`() {
        val c = choicesToConfig(cur(linkedSetOf("c2", "c1"), StreamTier.P1080, StreamOrientation.LANDSCAPE, "k1", " Hi\nthere "))
        assertEquals(PresetConfig("landscape", listOf("c2", "c1"), "k1", "fhd", true, "Hi there"), c)
        val off = choicesToConfig(cur(setOf("c1")))
        assertEquals(false, off.tickerEnabled); assertNull(off.tickerText); assertNull(off.brandKitId)
    }

    @Test fun `save then apply gives the same setup back`() {
        val start = cur(linkedSetOf("c3", "c1"), StreamTier.P540, StreamOrientation.LANDSCAPE, "k1", "News")
        assertEquals(start, applyPresetConfig(choicesToConfig(start), cur(), lim()).choices)
    }

    // ---- default card / order ----

    @Test fun `implicit default card uses the ticked channels, else the first ones the plan allows`() {
        val ticked = implicitDefaultPreset(cur(setOf("c2")), conns, 3, true)
        assertTrue(ticked.isImplicit); assertEquals(IMPLICIT_NAME, ticked.name); assertEquals(listOf("c2"), ticked.config.destinationIds)
        assertEquals(listOf("c1"), implicitDefaultPreset(cur(), conns, 1, true).config.destinationIds)
        assertEquals(listOf("c1", "c2"), implicitDefaultPreset(cur(setOf("stale")), conns, 2, true).config.destinationIds)
        assertEquals(emptyList<String>(), implicitDefaultPreset(cur(), emptyList(), 2, true).config.destinationIds)
        assertNull(implicitDefaultPreset(cur(kit = "k1"), conns, 2, false).config.brandKitId)
        assertEquals("hd", implicitDefaultPreset(cur(), conns, 1, true).config.quality)
    }

    @Test fun `home order is most recently used first, never used last, server order kept for ties`() {
        val a = Preset("a", "A", lastUsedAt = null); val b = Preset("b", "B", lastUsedAt = "2026-10-02T00:00:00Z")
        val c = Preset("c", "C", lastUsedAt = "2026-10-05T00:00:00Z"); val d = Preset("d", "D", lastUsedAt = null)
        assertEquals(listOf("c", "b", "a", "d"), orderForHome(listOf(a, b, c, d)).map { it.id })
        assertTrue(orderForHome(emptyList()).isEmpty())
    }
}
