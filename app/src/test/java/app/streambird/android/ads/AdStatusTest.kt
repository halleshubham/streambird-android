package app.streambird.android.ads

import app.streambird.android.brand.BrandOrientation
import app.streambird.android.data.ApiException
import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdStatusTest {
    private val interval = AdSchedule.Interval(showSec = 15, everySec = 180)   // cycle 195 s: hidden 0..180, shown 180..195
    private fun slot(p: AdPlacement = AdPlacement.LOWER_THIRD, enabled: Boolean = true, s: AdSchedule = interval, m: AdManual? = null) = AdSlot(p, enabled, s, m)
    private fun live(e: Double, s: AdSlot = slot(), source: AdSource = AdSource.KIT) = adStatusText(e, s, source)

    @Test fun `formats`() {
        assertEquals("2:41", formatCountdown(161)); assertEquals("0:09", formatCountdown(9)); assertEquals("1:02:05", formatCountdown(3725)); assertEquals("0:00", formatCountdown(-4))
        assertEquals("15 s", formatDuration(15)); assertEquals("3 min", formatDuration(180)); assertEquals("1 min 30 s", formatDuration(90)); assertEquals("2 h", formatDuration(7200)); assertEquals("1 h 30 min", formatDuration(5400))
    }

    @Test fun `continuous ad is always on, with its source`() {
        assertEquals("From the brand kit. Always on", adStatusText(12.0, slot(s = AdSchedule.Continuous), AdSource.KIT))
        assertEquals("Picture from this phone (this stream only). Always on", adStatusText(null, slot(s = AdSchedule.Continuous), AdSource.PHONE))
    }

    @Test fun `interval ad counts down while hidden`() {
        assertEquals("From the brand kit. Next shows in 3:00 for 15 s", live(0.0))           // hidden FIRST: the clock starts hidden
        assertEquals("From the brand kit. Next shows in 2:41 for 15 s", live(19.0))
        assertEquals("From the brand kit. Next shows in 0:01", live(179.5).substringBefore(" for"))
    }

    @Test fun `interval ad says showing now and when it ends, then hides again and repeats`() {
        assertEquals("From the brand kit. Showing now (ends in 15 s)", live(180.0))
        assertEquals("From the brand kit. Showing now (ends in 9 s)", live(186.5))
        assertEquals("From the brand kit. Showing now (ends in 1 s)", live(194.9))
        assertEquals("From the brand kit. Next shows in 3:00 for 15 s", live(195.0))          // next cycle starts hidden
        assertEquals("From the brand kit. Showing now (ends in 15 s)", live(195.0 + 180.0))
    }

    @Test fun `the fade windows count as showing`() {
        // 300 ms fade in at the start of the shown window, fade out at its end (alpha < 1, still on the picture)
        assertTrue(scheduleAlpha(180.1, interval) in 0.0..1.0 && scheduleAlpha(180.1, interval) < 1.0)
        assertTrue(live(180.1).contains("Showing now")); assertTrue(live(194.8).contains("Showing now"))
    }

    @Test fun `the text agrees with the real schedule at every second`() {
        for (t in 0..800) {
            val shown = scheduleAlpha(t + 0.5, interval) >= 0
            assertEquals("t=$t", shown, live(t + 0.5).contains("Showing now"))
        }
    }

    @Test fun `manual Show and Hide are named and win over the schedule`() {
        assertEquals("From the brand kit. Shown by you. Pick Follow schedule to go back.", live(5.0, slot(m = AdManual.SHOWN)))
        assertEquals("From the brand kit. Hidden by you. Pick Follow schedule to go back.", live(185.0, slot(m = AdManual.HIDDEN)))
    }

    @Test fun `disabled ad says Off`() {
        assertEquals("From the brand kit. Off", live(185.0, slot(enabled = false)))
        assertEquals("From the brand kit. Off", adStatusText(null, slot(enabled = false), AdSource.KIT))
    }

    @Test fun `L-shaped frame needs Shrink picture, even when shown by hand`() {
        val f = slot(AdPlacement.L_SHAPE, m = AdManual.SHOWN)
        assertEquals("From the brand kit. Needs Shrink picture while ads show", adStatusText(10.0, f, AdSource.KIT, shrinkOn = false))
        assertEquals("From the brand kit. Needs Shrink picture while ads show", adStatusText(null, slot(AdPlacement.L_SHAPE), AdSource.KIT, shrinkOn = false))
        // a lower third does not care about the switch
        assertEquals("From the brand kit. Always on", adStatusText(10.0, slot(AdPlacement.LOWER_THIRD, s = AdSchedule.Continuous), AdSource.KIT, shrinkOn = false))
        // a disabled frame says Off before anything else
        assertEquals("From the brand kit. Off", adStatusText(10.0, slot(AdPlacement.L_SHAPE, enabled = false), AdSource.KIT, shrinkOn = false))
    }

    @Test fun `a frame without a clear window says it covers the picture`() {
        val s = adStatusText(185.0, slot(AdPlacement.L_SHAPE), AdSource.KIT, hasWindow = false)
        assertTrue(s, s.startsWith("From the brand kit. Showing now (ends in 10 s)") && s.contains("No clear window"))
        assertTrue(adStatusText(null, slot(AdPlacement.L_SHAPE, s = AdSchedule.Continuous), AdSource.KIT, hasWindow = false).contains("No clear window"))
        assertTrue(!adStatusText(185.0, slot(AdPlacement.L_SHAPE), AdSource.KIT, hasWindow = true).contains("window"))
        assertTrue(!adStatusText(185.0, slot(AdPlacement.L_SHAPE), AdSource.KIT, hasWindow = null).contains("window"))
        assertTrue(!adStatusText(185.0, slot(AdPlacement.LOWER_THIRD), AdSource.KIT, hasWindow = false).contains("window"))   // only the frame has one
    }

    @Test fun `free plan says the banner shows instead, a failed picture says why`() {
        assertEquals("Not included in this account's plan. The StreamBird banner shows instead.", adStatusText(10.0, slot(), AdSource.KIT, freePlan = true))
        assertEquals("From the brand kit. Not on the picture: File not found", adStatusText(10.0, slot(), AdSource.KIT, problem = "File not found"))
    }

    @Test fun `before going live (Setup) the schedule is described as it will run, starting hidden`() {
        assertEquals("From the brand kit. Starts hidden: first shows 3 min after you go live, for 15 s, then repeats.", adStatusText(null, slot(), AdSource.KIT))
        // a manual state never applies before the stream exists
        assertEquals("From the brand kit. Starts hidden: first shows 3 min after you go live, for 15 s, then repeats.", adStatusText(null, slot(m = AdManual.SHOWN), AdSource.KIT))
    }

    @Test fun `an invalid interval never shows`() {
        assertTrue(adStatusText(5.0, slot(s = AdSchedule.Interval(0, 10)), AdSource.KIT).contains("Never shows"))
    }

    @Test fun `clock helper reads 0 until the clock started`() {
        assertEquals(0.0, adElapsedSec(5000, 0), 0.0); assertEquals(3.5, adElapsedSec(10500, 7000), 1e-9); assertEquals(0.0, adElapsedSec(1000, 2000), 0.0)
    }

    // ---- the kit-level notices ----

    private val frameAd = KitAd("l_shape", "a", true, "interval", 15, 180)
    private fun kit(ads: List<KitAd> = emptyList(), portrait: List<KitAd> = emptyList()) = BrandKit(id = "k", name = "Acme", ads = ads, adsPortrait = portrait)

    @Test fun `kit with ads for the other shape only is flagged, a kit with none or with the right set is not`() {
        assertEquals("The brand kit 'Acme' has no ads for 9:16 streams, only for 16:9. Add them under Brand kits.", kitAdsMissingNote(kit(ads = listOf(frameAd)), BrandOrientation.PORTRAIT))
        assertEquals("The brand kit 'Acme' has no ads for 16:9 streams, only for 9:16. Add them under Brand kits.", kitAdsMissingNote(kit(portrait = listOf(frameAd)), BrandOrientation.LANDSCAPE))
        assertNull(kitAdsMissingNote(kit(portrait = listOf(frameAd)), BrandOrientation.PORTRAIT)); assertNull(kitAdsMissingNote(kit(), BrandOrientation.PORTRAIT)); assertNull(kitAdsMissingNote(null, BrandOrientation.PORTRAIT))
    }

    @Test fun `setup notice explains every reason the rows may be empty`() {
        val p = BrandOrientation.PORTRAIT
        fun n(logo: Boolean = true, loading: Boolean = false, loaded: Boolean = true, err: String? = null, blocked: Boolean = false, k: BrandKit? = kit(portrait = listOf(frameAd))) =
            kitAdsSetupNotice(logo, "No logo", loading, loaded, err, blocked, k, p)
        assertTrue(n(logo = false)!!.warn && n(logo = false)!!.text.contains("\"No logo\""))
        assertEquals(KitAdsNotice("Loading the brand kit...", false), n(loading = true, loaded = false, k = null))
        assertTrue(n(err = "offline", k = null)!!.text.contains("offline") && n(err = "offline", k = null)!!.warn)
        assertTrue(n(k = null)!!.text.contains("No brand kit applies"))
        assertNull(n(blocked = true, k = null))
        assertTrue(n(k = kit(ads = listOf(frameAd)))!!.text.contains("no ads for 9:16"))
        assertEquals(KitAdsNotice("Brand kit: Acme", false), n())
        assertNotNull(n())
    }

    @Test fun `only temporary download errors are retried`() {
        assertTrue(isTransientAdError(java.io.IOException("timeout"))); assertTrue(isTransientAdError(ApiException(503, "x")))
        assertTrue(isTransientAdError(ApiException(429, "x")))
        assertTrue(!isTransientAdError(ApiException(404, "x"))); assertTrue(!isTransientAdError(ApiException(-1, "too large")))
        assertTrue(!isTransientAdError(IllegalStateException("x")))
    }

    @Test fun `failure sentences name the kit and the frame`() {
        assertEquals("Could not load the L-shaped frame of kit 'Acme' (File not found).", describeAdFailure(AdLoadFailure(AdPlacement.L_SHAPE, "File not found.", "Acme")))
        assertEquals("Could not load the Left third ad (x).", describeAdFailure(AdLoadFailure(AdPlacement.SIDE_THIRD, "x")))
        assertTrue(describeKitFetchFailure("offline").startsWith("Could not load the brand kit's ads (offline)."))
    }
}
