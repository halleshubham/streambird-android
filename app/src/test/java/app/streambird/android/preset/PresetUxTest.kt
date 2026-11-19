package app.streambird.android.preset

import app.streambird.android.data.PlatformConnection
import app.streambird.android.data.Preset
import app.streambird.android.data.PresetConfig
import app.streambird.android.media.StreamOrientation
import app.streambird.android.media.StreamTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class PresetUxTest {
    private val yt = PlatformConnection("c1", "youtube", label = "My channel")
    private val fb = PlatformConnection("c2", "facebook", label = "My page")
    private val conns = listOf(yt, fb)
    private fun lim(max: Int = 3, tiers: List<StreamTier> = StreamTier.entries, brand: Boolean = true, known: Set<String>? = setOf("k1")) =
        SetupLimits(setOf("c1", "c2"), max, tiers, brand, known)
    private fun cur(sel: Set<String> = linkedSetOf("c1"), tier: StreamTier = StreamTier.P720, o: StreamOrientation = StreamOrientation.PORTRAIT, kit: String? = null, ticker: String = "", vis: String = PUBLIC) =
        SetupChoices(sel, tier, o, kit, ticker, vis)
    private fun p(id: String, name: String) = Preset(id, name)

    // ---- limit ----

    @Test fun `limit counter and limit`() {
        assertEquals("3 of 20 presets", presetCountText(3))
        assertFalse(presetLimitReached(19)); assertTrue(presetLimitReached(20)); assertTrue(presetLimitReached(21))
        assertTrue(LIMIT_REACHED_TEXT.contains("20") && LIMIT_REACHED_TEXT.contains("Delete one"))
    }

    // ---- names ----

    @Test fun `blank name blocks with a reason, a duplicate only warns, own name is not a duplicate`() {
        val all = listOf(p("a", "Friday show"), p("b", "Weekend"))
        assertEquals("Give the preset a name to save it.", checkPresetName("   ", all).blocking)
        assertFalse(checkPresetName("", all).ok)
        val dup = checkPresetName("  friday SHOW ", all)
        assertTrue(dup.ok); assertNull(dup.blocking); assertTrue(dup.warning!!.contains("Friday show".lowercase()) || dup.warning!!.contains("friday SHOW"))
        assertNull(checkPresetName("Friday show", all, editingId = "a").warning)
        assertTrue(checkPresetName("Friday show", all, editingId = "b").warning != null)
        val fresh = checkPresetName("Brand new", all); assertTrue(fresh.ok && fresh.warning == null)
    }

    @Test fun `duplicate name is name copy, unique, and fits the cap`() {
        assertEquals("Show copy", duplicatePresetName("Show", listOf("Show")))
        assertEquals("Show copy 2", duplicatePresetName("Show", listOf("Show", "show COPY")))
        assertEquals("Show copy 3", duplicatePresetName("  Show ", listOf("Show copy", "Show copy 2")))
        val long = "x".repeat(80)
        val d = duplicatePresetName(long, listOf(long))
        assertEquals(80, d.length); assertTrue(d.endsWith(" copy"))
        val d2 = duplicatePresetName(long, listOf(long, d))
        assertEquals(80, d2.length); assertTrue(d2.endsWith(" copy 2")); assertTrue(d2 != d)
    }

    // ---- error words ----

    @Test fun `write errors name the operation, 422 and 429 explain, 5xx keeps the server words`() {
        assertEquals("Could not save the preset: boom", presetWriteError("create", 500, "boom"))
        assertEquals("Could not delete the preset: boom", presetWriteError("delete", 500, "boom"))
        assertEquals("Could not rename the preset: boom", presetWriteError("rename", 400, "boom"))
        assertEquals("Could not duplicate the preset: boom", presetWriteError("duplicate", 502, "boom"))
        assertTrue(presetWriteError("update", 404, "x").contains("no longer exists"))
        assertTrue(presetWriteError("rename", 404, "x").contains("no longer exists"))
        assertTrue(presetWriteError("delete", 404, "x").contains("no longer exists"))
        assertTrue(presetWriteError("create", 401, "x").contains("session expired"))
        val e422 = presetWriteError("duplicate", 422, "brandKitId does not belong to this account")
        assertTrue(e422.contains("brandKitId does not belong to this account") && e422.contains("check them"))
        assertTrue(presetWriteError("create", 429, "x").contains("Wait a minute"))
        assertEquals("Deleted \"A\"", deletedText("A"))
    }

    // ---- last used ----

    @Test fun `last used wording`() {
        val now = Instant.parse("2026-11-19T12:00:00Z")
        fun l(iso: String?) = lastUsedLabel(iso, now, ZoneOffset.UTC)
        assertEquals("Never used", l(null)); assertEquals("Never used", l(" "))
        assertEquals("Used today", l("2026-11-19T00:01:00Z"))
        assertEquals("Used yesterday", l("2026-11-18T23:59:00Z"))
        assertEquals("Used 5 days ago", l("2026-11-14T10:00:00Z"))
        assertEquals("Used 3 weeks ago", l("2026-10-29T10:00:00Z"))
        assertEquals("Used 5 months ago", l("2026-06-19T10:00:00Z"))
        assertEquals("Used over a year ago", l("2024-01-01T00:00:00Z"))
        assertEquals("Used today", l("2026-11-25T00:00:00Z")) // a clock a little ahead is still "today"
        assertNull(l("not a date"))
    }

    // ---- summary parts ----

    @Test fun `summary parts split channels and details and count missing channels`() {
        val kits = mapOf("k1" to "Acme")
        val cfg = PresetConfig("landscape", listOf("c1", "gone", "c2", "c1"), "k1", "fhd", true, "News", "unlisted")
        val parts = presetSummaryParts(cfg, conns, kits::get, true)
        assertEquals(listOf("YouTube", "Facebook"), parts.channels)
        assertEquals(listOf("Acme brand", "Unlisted", "Ticker on", "1080p", "16:9"), parts.details)
        assertEquals(1, parts.missingChannels)
        assertEquals(listOf("Brand kit"), presetSummaryParts(PresetConfig(brandKitId = "deleted"), conns, kits::get, true).details.take(1))
        assertEquals(emptyList<String>(), presetSummaryParts(PresetConfig(), conns, kits::get, false).channels)
        assertEquals(listOf("Public", "Ticker off"), presetSummaryParts(PresetConfig(), conns, kits::get, false).details)
        // the one-line summary is still the joined parts
        assertEquals("YouTube + Facebook · Acme brand · Unlisted · Ticker on · 1080p · 16:9", presetSummary(cfg, conns, kits::get, true))
    }

    // ---- warnings (before the host taps) ----

    @Test fun `card warnings are the plan and connection notes`() {
        assertTrue(presetWarnings(PresetConfig(destinationIds = listOf("c1"), quality = "hd"), lim()).isEmpty())
        val w = presetWarnings(PresetConfig(destinationIds = listOf("c1", "zzz"), quality = "fhd", brandKitId = "gone"), lim(tiers = listOf(StreamTier.P540, StreamTier.P720)))
        assertTrue(w.any { it.contains("no longer connected") }); assertTrue(w.any { it.contains("above your plan") }); assertTrue(w.any { it.contains("deleted") })
        assertTrue(presetWarnings(PresetConfig(destinationIds = listOf("c1", "c2")), lim(max = 1)).any { it.contains("allows 1 destination") })
    }

    // ---- edited detection ----

    @Test fun `nothing differs right after a preset is applied, including the Public fallback and lost channels`() {
        val cfg = PresetConfig("portrait", listOf("c1", "c2"), null, "hd", true, "News", "unlisted")
        val applied = applyPresetForGoLive(cfg, cur(), lim()).choices
        assertTrue(presetChanges(applied, cfg, lim()).isEmpty())
        assertEquals("Matches the saved preset", changesText(emptyList()))
        // a preset without visibility goes Public: not an edit
        val old = PresetConfig(destinationIds = listOf("c1"), quality = "hd")
        assertTrue(presetChanges(applyPresetForGoLive(old, cur(), lim()).choices, old, lim()).isEmpty())
        // a channel the account lost, a quality above the plan: not edits either
        val lost = PresetConfig("portrait", listOf("c1", "zzz"), "gone", "fhd", false)
        val l = lim(tiers = listOf(StreamTier.P540, StreamTier.P720))
        assertTrue(presetChanges(applyPresetForGoLive(lost, cur(), l).choices, lost, l).isEmpty())
    }

    @Test fun `each edited part is named`() {
        val cfg = PresetConfig("portrait", listOf("c1"), null, "hd", false, null, "public")
        val base = applyPresetForGoLive(cfg, cur(), lim()).choices
        assertEquals(listOf("Channels"), presetChanges(base.copy(selected = linkedSetOf("c1", "c2")), cfg, lim()))
        assertEquals(listOf("Channels"), presetChanges(base.copy(selected = linkedSetOf("c2")), cfg, lim()))
        assertEquals(listOf("Quality"), presetChanges(base.copy(tier = StreamTier.P1080), cfg, lim()))
        assertEquals(listOf("Shape"), presetChanges(base.copy(orientation = StreamOrientation.LANDSCAPE), cfg, lim()))
        assertEquals(listOf("Brand kit"), presetChanges(base.copy(brandKitId = "k1"), cfg, lim()))
        assertEquals(listOf("Ticker"), presetChanges(base.copy(tickerText = "Hello"), cfg, lim()))
        assertEquals(listOf("Visibility"), presetChanges(base.copy(visibility = PRIVATE), cfg, lim()))
        assertEquals(listOf("Quality", "Ticker"), presetChanges(base.copy(tier = StreamTier.P1080, tickerText = "x"), cfg, lim()))
        assertEquals("Unsaved changes: Quality, Ticker", changesText(listOf("Quality", "Ticker")))
    }

    @Test fun `channel order and a whitespace-only ticker are not edits`() {
        val cfg = PresetConfig("portrait", listOf("c1", "c2"), null, "hd", false, null, "public")
        val base = applyPresetForGoLive(cfg, cur(), lim()).choices
        assertTrue(presetChanges(base.copy(selected = linkedSetOf("c2", "c1"), tickerText = "   "), cfg, lim()).isEmpty())
    }

    @Test fun `texts exist for the empty state and the old server`() {
        assertTrue(PRESETS_EMPTY_BODY.contains("Customize") && PRESETS_EMPTY_BODY.contains("Save as preset"))
        assertTrue(QUICK_START_OLD_SERVER_TEXT.contains("Quick start"))
        assertTrue(PRESET_NOT_STORED_TEXT.contains("title") && PRESET_STORES_TEXT.contains("visibility"))
    }
}
