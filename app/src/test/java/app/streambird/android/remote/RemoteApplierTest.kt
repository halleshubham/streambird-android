package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdSource
import app.streambird.android.clock.ClockHours
import app.streambird.android.clock.ClockPosition
import app.streambird.android.clock.ClockSettings
import app.streambird.android.media.NameBannerState
import app.streambird.android.stream.LiveAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake of the live controls, a fake clock, and the applier / dispatcher / guards on top of them. */
class RemoteApplierTest {
    class FakeControls : RemoteControls {
        @Volatile override var isLive = true
        val ads = mutableSetOf(AdPlacement.LOWER_THIRD, AdPlacement.L_SHAPE)
        var ticker = "old"
        var clockNow = ClockSettings()
        val calls = mutableListOf<String>()
        override fun hasAd(p: AdPlacement) = p in ads
        override fun tickerText() = ticker
        override fun clock() = clockNow
        override fun applyAdManual(p: AdPlacement, m: AdManual?) { calls.add("manual ${p.wire} $m") }
        override fun applyAdEnabled(p: AdPlacement, on: Boolean) { calls.add("enabled ${p.wire} $on") }
        override fun applyAdsShrink(on: Boolean) { calls.add("shrink $on") }
        override fun applyAdSchedule(p: AdPlacement, s: AdSchedule) { calls.add("schedule ${p.wire} $s") }
        override fun applyTicker(text: String) { ticker = text; calls.add("ticker '$text'") }
        override fun applyClock(c: ClockSettings) { clockNow = c; calls.add("clock") }
        override var freePlan = false
        var logoNow: LogoInfo? = LogoInfo(true, false, 90)
        var bannerNow = NameBannerState()
        var kitResult: ApplyResult = ApplyResult.PENDING
        var kitLate: ((ApplyResult) -> Unit)? = null
        override fun logo() = logoNow
        override fun applyLogo(onRight: Boolean?, size: Int?) { logoNow = logoNow?.let { LogoInfo(true, onRight ?: it.onRight, size ?: it.size) }; calls.add("logo $onRight $size") }
        override fun banner() = bannerNow
        override fun applyBanner(s: NameBannerState) { bannerNow = s; calls.add("banner ${s.show}") }
        override fun applyKit(kitId: String, done: (ApplyResult) -> Unit): ApplyResult { calls.add("kit $kitId"); kitLate = done; return kitResult }
    }

    private var now = 1_000_000L
    private val controls = FakeControls()
    private val host = HostChangeTracker { now }
    private val applier = RemoteApplier(controls, host)
    private fun c(a: RemoteAction, seq: Long = 1) = RemoteCommand(seq, "id$seq", "t", 0, a)

    @Test fun `ads commands map onto the controls`() {
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.AdsManual(AdPlacement.LOWER_THIRD, AdManual.SHOWN))))
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.AdsManual(AdPlacement.LOWER_THIRD, null))))
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.AdsEnabled(AdPlacement.L_SHAPE, false))))
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.AdsShrink(false))))
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.AdsSchedule(AdPlacement.LOWER_THIRD, AdSchedule.Interval(10, 60)))))
        assertEquals(
            listOf("manual lower_third SHOWN", "manual lower_third null", "enabled l_shape false", "shrink false", "schedule lower_third Interval(showSec=10, everySec=60)"),
            controls.calls,
        )
    }

    @Test fun `an ad that is not on this stream is an error and changes nothing`() {
        val r = applier.apply(c(RemoteAction.AdsManual(AdPlacement.FULL_SCREEN, AdManual.SHOWN)))
        assertFalse(r.ok); assertEquals("no full_screen ad on this stream", r.error)
        assertTrue(controls.calls.isEmpty())
    }

    @Test fun `ticker on sets the sanitized text off clears it and null text keeps the current one`() {
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.TickerSet("  Breaking \n news  ", true))))
        assertEquals("Breaking news", controls.ticker)
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.TickerSet(null, false))))
        assertEquals("", controls.ticker)
        controls.ticker = "kept"
        assertEquals(ApplyResult.OK, applier.apply(c(RemoteAction.TickerSet(null, true))))
        assertEquals("kept", controls.ticker)
    }

    @Test fun `ticker on without any text is an error`() {
        controls.ticker = ""
        assertEquals("no ticker text", applier.apply(c(RemoteAction.TickerSet("   ", true))).error)
        assertEquals("no ticker text", applier.apply(c(RemoteAction.TickerSet(null, true))).error)
        assertTrue(controls.calls.isEmpty())
    }

    @Test fun `clock command merges onto the current settings`() {
        controls.clockNow = ClockSettings(enabled = false, position = ClockPosition.TOP_LEFT, timezone = "UTC")
        val r = applier.apply(c(RemoteAction.ClockSet(ClockPatch(enabled = true, hours = ClockHours.H24))))
        assertTrue(r.ok)
        assertEquals(ClockSettings(enabled = true, position = ClockPosition.TOP_LEFT, hours = ClockHours.H24, timezone = "UTC"), controls.clockNow)
    }

    @Test fun `unsupported commands are an error with the fixed text and never touch the controls`() {
        val r = applier.apply(c(RemoteAction.Unsupported()))
        assertFalse(r.ok); assertEquals("not supported on the phone app", r.error)
        assertTrue(controls.calls.isEmpty())
    }

    @Test fun `nothing is applied when the stream is not live`() {
        controls.isLive = false
        val r = applier.apply(c(RemoteAction.AdsShrink(true)))
        assertEquals("stream not live", r.error); assertTrue(controls.calls.isEmpty())
    }

    @Test fun `a setting the host changed within 3 seconds is refused then allowed after the window`() {
        host.mark(RemoteKeys.ADS_SHRINK)
        now += 2_999
        assertEquals("host changed it just now", applier.apply(c(RemoteAction.AdsShrink(false))).error)
        assertTrue(controls.calls.isEmpty())
        now += 1                                                    // exactly 3 s: the window is over
        assertTrue(applier.apply(c(RemoteAction.AdsShrink(false))).ok)
    }

    @Test fun `the conflict window is per setting key`() {
        host.mark(RemoteKeys.adManual(AdPlacement.LOWER_THIRD))
        now += 500
        assertEquals("host changed it just now", applier.apply(c(RemoteAction.AdsManual(AdPlacement.LOWER_THIRD, AdManual.HIDDEN))).error)
        assertTrue(applier.apply(c(RemoteAction.AdsManual(AdPlacement.L_SHAPE, AdManual.HIDDEN))).ok)  // another ad
        assertTrue(applier.apply(c(RemoteAction.AdsShrink(true))).ok)                                   // another setting
        host.mark(RemoteKeys.TICKER)
        assertEquals("host changed it just now", applier.apply(c(RemoteAction.TickerSet("x", true))).error)
        host.mark(RemoteKeys.CLOCK)
        assertEquals("host changed it just now", applier.apply(c(RemoteAction.ClockSet(ClockPatch(enabled = true)))).error)
    }

    @Test fun `a later host change overrides a remote one with no locking`() {
        assertTrue(applier.apply(c(RemoteAction.AdsShrink(false))).ok)   // support changed it
        now += 10
        host.mark(RemoteKeys.ADS_SHRINK)                                  // the host changes it right after: allowed, nothing to check
        now += 100
        assertFalse(applier.apply(c(RemoteAction.AdsShrink(false), seq = 2)).ok)  // support must wait 3 s
        now += 3_000
        assertTrue(applier.apply(c(RemoteAction.AdsShrink(false), seq = 3)).ok)
    }

    @Test fun `host tracker and rate limiter basics`() {
        assertFalse(host.recent("never"))
        var t = 0L
        val rate = RateLimiter(20, 10_000) { t }
        repeat(20) { assertTrue(rate.tryAcquire()) }
        assertFalse(rate.tryAcquire())
        t = 9_999; assertFalse(rate.tryAcquire())
        t = 10_000; assertTrue(rate.tryAcquire())
        host.clear(); assertNull(null)
    }

    @Test fun `snapshot builder lists every placement with present flags and the compact settings`() {
        val ads = listOf(
            LiveAd(AdPlacement.LOWER_THIRD, manual = AdManual.SHOWN, source = AdSource.PHONE, enabled = true, schedule = AdSchedule.Interval(15, 180)),
            LiveAd(AdPlacement.L_SHAPE, enabled = false),
            LiveAd(AdPlacement.SIDE_THIRD, problem = "not found"),
        )
        val s = RemoteState.build(RemoteStateInput(ads, true, "Hello", ClockSettings(enabled = true, timezone = "Asia/Kolkata"), "portrait", "1.0.14", false)).toString()
        val o = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        val a = o["ads"] as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("lower_third", "side_third", "full_screen", "l_shape"), a.keys)
        assertEquals("""{"present":true,"enabled":true,"manual":"shown","mode":"interval","showSec":15,"everySec":180,"source":"phone"}""", a["lower_third"].toString())
        assertEquals("""{"present":true,"enabled":false,"manual":null,"mode":"continuous","source":"kit"}""", a["l_shape"].toString())
        assertEquals("""{"present":false,"enabled":false,"manual":null,"mode":"continuous","source":"kit"}""", a["side_third"].toString())
        assertEquals("""{"present":false}""", a["full_screen"].toString())
        assertEquals("""{"text":"Hello","on":true}""", o["ticker"].toString())
        assertEquals("true", o["adsShrink"].toString())
        assertEquals("\"portrait\"", o["orientation"].toString())
        assertEquals("\"1.0.14\"", o["appVersion"].toString())
        assertTrue(o["clock"].toString().contains(""""timezone":"Asia/Kolkata""""))
        assertEquals(setOf("v", "platform", "appVersion", "orientation", "freePlan", "ads", "adsShrink", "ticker", "clock", "kit", "logo", "lowerThird"), o.keys)
    }

    @Test fun `snapshot has no ticker text when the ticker is off and never any secret looking key`() {
        val s = RemoteState.build(RemoteStateInput(emptyList(), false, "", ClockSettings(), "landscape", "1.0.14", true)).toString()
        assertTrue(s.contains(""""ticker":{"text":null,"on":false}"""))
        for (bad in listOf("token", "cookie", "secret", "password", "key", "url", "session", "email", "authorization")) assertFalse(bad, s.lowercase().contains(bad))
    }
}
