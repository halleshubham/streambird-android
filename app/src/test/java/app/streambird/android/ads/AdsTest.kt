package app.streambird.android.ads

import app.streambird.android.media.Ticker
import app.streambird.android.media.TickerSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Ported from the web's test/ui/ads.spec.ts (the same vectors), plus Android-specific cases. */
class AdsTest {
    private val sizes = listOf(1280 to 720, 1920 to 1080, 720 to 1280, 1080 to 1920)
    private fun r(x: Double, y: Double, w: Double, h: Double) = AdRect(x, y, w, h)
    private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue("$a != $b", abs(a - b) < eps)
    private fun assertRect(e: AdRect, a: AdRect, eps: Double = 1e-3) { near(e.x, a.x, eps); near(e.y, a.y, eps); near(e.w, a.w, eps); near(e.h, a.h, eps) }

    @Test fun `placement areas in both orientations`() {
        assertEquals(r(0.0, 480.0, 1280.0, 240.0), adRect(AdPlacement.LOWER_THIRD, 1280, 720))
        assertEquals(r(0.0, 0.0, 427.0, 720.0), adRect(AdPlacement.SIDE_THIRD, 1280, 720))
        assertEquals(r(0.0, 0.0, 1280.0, 720.0), adRect(AdPlacement.FULL_SCREEN, 1280, 720))
        assertEquals(r(0.0, 853.0, 720.0, 427.0), adRect(AdPlacement.LOWER_THIRD, 720, 1280))
        assertEquals(r(0.0, 0.0, 240.0, 1280.0), adRect(AdPlacement.SIDE_THIRD, 720, 1280))
        assertEquals(r(0.0, 0.0, 720.0, 1280.0), adRect(AdPlacement.FULL_SCREEN, 720, 1280))
    }

    @Test fun `the lower third sits above the ticker bar, the other two ignore it`() {
        for ((w, h) in sizes) {
            val bar = Ticker.metrics(TickerSize.S, w, h).barPx                   // the ticker bar the app really draws
            val plain = adRect(AdPlacement.LOWER_THIRD, w, h, 0); val lifted = adRect(AdPlacement.LOWER_THIRD, w, h, bar)
            assertEquals(plain.h, lifted.h, 0.0); assertEquals((h - bar).toDouble(), lifted.y + lifted.h, 0.0); assertEquals(h.toDouble(), plain.y + plain.h, 0.0)
            assertEquals(adRect(AdPlacement.SIDE_THIRD, w, h, 0), adRect(AdPlacement.SIDE_THIRD, w, h, bar))
            assertEquals(adRect(AdPlacement.FULL_SCREEN, w, h, 0), adRect(AdPlacement.FULL_SCREEN, w, h, bar))
        }
    }

    @Test fun `every area stays inside the frame, also with a big ticker`() {
        for ((w, h) in sizes) for (p in AdPlacement.entries) {
            val a = adRect(p, w, h, Ticker.metrics(TickerSize.L, w, h).barPx)
            assertTrue(a.x >= 0 && a.y >= 0 && a.x + a.w <= w && a.y + a.h <= h)
        }
    }

    @Test fun `a picture is fitted inside its area keeping its shape`() {
        val lower = adRect(AdPlacement.LOWER_THIRD, 1280, 720)
        assertRect(r(0.0, 480.0, 1280.0, 240.0), fitAd(AdPlacement.LOWER_THIRD, 1280, 240, lower))
        assertRect(r(520.0, 480.0, 240.0, 240.0), fitAd(AdPlacement.LOWER_THIRD, 100, 100, lower))
        assertRect(r(0.0, 480.0 + 240 - 64, 1280.0, 64.0), fitAd(AdPlacement.LOWER_THIRD, 2000, 100, lower))   // bottom of the area, not floating
        val side = adRect(AdPlacement.SIDE_THIRD, 1280, 720)
        assertRect(r(0.0, 0.0, 427.0, 720.0), fitAd(AdPlacement.SIDE_THIRD, 427, 720, side))
        assertRect(r(0.0, 146.5, 427.0, 427.0), fitAd(AdPlacement.SIDE_THIRD, 100, 100, side))
        val full = adRect(AdPlacement.FULL_SCREEN, 1280, 720)
        assertRect(r(280.0, 0.0, 720.0, 720.0), fitAd(AdPlacement.FULL_SCREEN, 100, 100, full))
        assertRect(r(0.0, 0.0, 1280.0, 720.0), fitAd(AdPlacement.FULL_SCREEN, 640, 360, full))               // upscaled
        for ((w, h) in sizes) for (p in AdPlacement.entries) {
            val area = adRect(p, w, h)
            for ((iw, ih) in listOf(1280 to 240, 427 to 720, 1280 to 720, 50 to 50, 4000 to 300, 300 to 4000)) {
                val f = fitAd(p, iw, ih, area)
                near(f.w / f.h, iw.toDouble() / ih, 1e-5)
                assertTrue(f.x >= area.x - 1e-9 && f.y >= area.y - 1e-9 && f.x + f.w <= area.x + area.w + 1e-9 && f.y + f.h <= area.y + area.h + 1e-9)
            }
        }
        assertEquals(0.0, fitAd(AdPlacement.FULL_SCREEN, 0, 10, adRect(AdPlacement.FULL_SCREEN, 100, 100)).w, 0.0)
    }

    private val show10every30 = AdSchedule.Interval(10, 30)       // cycle 40

    @Test fun `continuous is always visible at full opacity`() {
        for (t in listOf(0.0, 0.1, 59.0, 3600.0, 1e6)) assertEquals(AdVisibility(true, 1.0), adVisibility(t, AdSchedule.Continuous))
    }

    @Test fun `interval hidden first then shown, repeating, with exact boundaries`() {
        assertEquals(AdVisibility(false, 0.0), adVisibility(0.0, show10every30))
        assertEquals(AdVisibility(false, 0.0), adVisibility(29.999, show10every30))
        assertEquals(AdVisibility(true, 0.0), adVisibility(30.0, show10every30))         // the window opens at exactly everySec
        assertEquals(AdVisibility(true, 1.0), adVisibility(35.0, show10every30))
        assertTrue(adVisibility(39.999, show10every30).visible)
        assertEquals(AdVisibility(false, 0.0), adVisibility(40.0, show10every30))        // next cycle starts hidden
        assertFalse(adVisibility(69.9, show10every30).visible); assertTrue(adVisibility(70.0, show10every30).visible)
        assertEquals(1.0, adVisibility(75.0, show10every30).alpha, 0.0)
        assertEquals(1.0, adVisibility(40.0 * 1000 + 35, show10every30).alpha, 0.0)
        assertEquals(AdVisibility(false, 0.0), adVisibility(-5.0, show10every30))        // before go-live counts as 0
        assertFalse(adVisibility(Double.NaN, show10every30).visible)
    }

    @Test fun `300 ms fades, never a pop`() {
        near(0.5, adVisibility(30.15, show10every30).alpha); assertEquals(1.0, adVisibility(30.3, show10every30).alpha, 0.0)
        near(1.0, adVisibility(39.7, show10every30).alpha); near(0.5, adVisibility(39.85, show10every30).alpha)
        assertTrue(adVisibility(39.99, show10every30).alpha < 0.05)
        var prev = adVisibility(0.0, show10every30).alpha; var t = 0.01
        while (t < 120) { val a = adVisibility(t, show10every30).alpha; assertTrue(abs(a - prev) < 0.05 + 1e-9); prev = a; t += 0.01 }
    }

    @Test fun `a very short showing fades in half the time each way`() {
        // showSec is an integer on the server (min 1): 1 s shows with 0.3 s fades; the web's 0.4 s case is covered by the formula on the double path
        val oneSec = AdSchedule.Interval(1, 1)                                           // the smallest allowed numbers, cycle 2
        assertEquals(1.0, adVisibility(1.5, oneSec).alpha, 0.0); assertFalse(adVisibility(0.5, oneSec).visible)
        assertFalse(adVisibility(2.5, oneSec).visible); assertEquals(1.0, adVisibility(3.5, oneSec).alpha, 0.0)
        near(0.5, adVisibility(1.15, oneSec).alpha)
    }

    @Test fun `the Free plan banner is 15 s shown every 180 s, hidden first`() {
        assertEquals(AdSchedule.Interval(15, 180), FREE_PLAN_AD_SCHEDULE)
        assertFalse(adVisibility(0.0, FREE_PLAN_AD_SCHEDULE).visible); assertFalse(adVisibility(179.9, FREE_PLAN_AD_SCHEDULE).visible)
        assertEquals(1.0, adVisibility(187.0, FREE_PLAN_AD_SCHEDULE).alpha, 0.0)
        assertTrue(adVisibility(194.9, FREE_PLAN_AD_SCHEDULE).alpha < 0.5)
        assertFalse(adVisibility(195.0, FREE_PLAN_AD_SCHEDULE).visible)
        assertEquals(1.0, adVisibility(375.0 + 7, FREE_PLAN_AD_SCHEDULE).alpha, 0.0)     // second showing
    }

    private fun slot(p: AdPlacement, s: AdSchedule, enabled: Boolean = true, manual: AdManual? = null) = AdSlot(p, enabled, s, manual)
    private val cont = AdSchedule.Continuous

    @Test fun `full screen hides the other two while visible, cross-fading at its edges`() {
        val slots = listOf(slot(AdPlacement.LOWER_THIRD, cont), slot(AdPlacement.SIDE_THIRD, cont), slot(AdPlacement.FULL_SCREEN, show10every30))
        assertEquals(listOf(1.0, 1.0, 0.0), resolveAdAlphas(10.0, slots).toList())
        assertEquals(listOf(0.0, 0.0, 1.0), resolveAdAlphas(35.0, slots).toList())
        val mid = resolveAdAlphas(30.15, slots); near(0.5, mid[2]); near(0.5, mid[0]); near(0.5, mid[1])
        assertEquals(listOf(1.0, 1.0, 0.0), resolveAdAlphas(45.0, slots).toList())
        assertEquals(listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD, AdPlacement.FULL_SCREEN), AD_DRAW_ORDER)
    }

    @Test fun `missing or off is 0, manual beats the schedule but not the switch`() {
        assertEquals(listOf(0.0, 0.0, 0.0), resolveAdAlphas(0.0, emptyList()).toList())
        assertEquals(0.0, adTargetAlpha(0.0, slot(AdPlacement.LOWER_THIRD, show10every30)), 0.0)
        assertEquals(1.0, adTargetAlpha(0.0, slot(AdPlacement.LOWER_THIRD, show10every30, manual = AdManual.SHOWN)), 0.0)
        assertEquals(0.0, adTargetAlpha(35.0, slot(AdPlacement.LOWER_THIRD, show10every30, manual = AdManual.HIDDEN)), 0.0)
        assertEquals(1.0, adTargetAlpha(35.0, slot(AdPlacement.LOWER_THIRD, cont, manual = null)), 0.0)
        assertEquals(0.0, adTargetAlpha(35.0, slot(AdPlacement.LOWER_THIRD, cont, enabled = false, manual = AdManual.SHOWN)), 0.0)
        val a = resolveAdAlphas(0.0, listOf(slot(AdPlacement.LOWER_THIRD, cont), slot(AdPlacement.FULL_SCREEN, show10every30, manual = AdManual.SHOWN)))
        assertEquals(listOf(0.0, 0.0, 1.0), a.toList())
    }

    @Test fun `stepAlpha eases manual changes over 300 ms`() {
        near(0.5, stepAlpha(0.0, 1.0, 0.15)); assertEquals(1.0, stepAlpha(0.5, 1.0, 0.15), 0.0); assertEquals(0.0, stepAlpha(1.0, 0.0, 0.3), 0.0)
        near(0.9, stepAlpha(1.0, 0.0, 0.03)); assertEquals(0.4, stepAlpha(0.4, 0.4, 1.0), 0.0); assertEquals(0.0, stepAlpha(0.0, 1.0, -1.0), 0.0)
    }

    @Test fun `schedule helpers, wording and the web's validation messages`() {
        assertEquals(UnitValue(30, SecUnit.SEC), splitSeconds(30)); assertEquals(UnitValue(1, SecUnit.MIN), splitSeconds(60))
        assertEquals(UnitValue(90, SecUnit.SEC), splitSeconds(90)); assertEquals(UnitValue(3, SecUnit.MIN), splitSeconds(180))
        assertEquals(180, toSeconds(3.0, SecUnit.MIN)); assertEquals(15, toSeconds(15.0, SecUnit.SEC)); assertEquals(90, toSeconds(1.5, SecUnit.MIN))
        assertEquals("Always on", describeSchedule(cont)); assertEquals("Shown for 15 sec, then hidden for 3 min", describeSchedule(FREE_PLAN_AD_SCHEDULE))
        assertNull(scheduleError(cont)); assertNull(scheduleError(AdSchedule.Interval(1, 1))); assertNull(scheduleError(AdSchedule.Interval(3600, 86400)))
        assertTrue(scheduleError(AdSchedule.Interval(0, 10))!!.startsWith("Show time must be between 1 second and 60 minutes"))
        assertTrue(scheduleError(AdSchedule.Interval(3601, 10))!!.contains("Show time"))
        assertTrue(scheduleError(AdSchedule.Interval(10, 0))!!.contains("between showings")); assertTrue(scheduleError(AdSchedule.Interval(10, 86401))!!.contains("between showings"))
    }

    @Test fun `seconds until an ad becomes visible (to decode a GIF just in time)`() {
        assertEquals(0.0, secondsUntilVisible(5.0, cont), 0.0)
        near(25.0, secondsUntilVisible(5.0, show10every30)); assertEquals(0.0, secondsUntilVisible(35.0, show10every30), 0.0); near(25.0, secondsUntilVisible(45.0, show10every30))
    }

    @Test fun `powered-by mark geometry (port of poweredBy ts)`() {
        // landscape 1280x720: unit 1, margin 20, pill 40 high, bottom gap = margin
        val l = poweredByLayout(1280, 720, 180.0, 1.0, 0)
        assertEquals(40, l.pillH); assertEquals(28, l.iconH); assertEquals(12 * 2 + 28 + 8 + 180, l.pillW)
        assertEquals(1280 - 20 - l.pillW, l.x); assertEquals(720 - 20 - 40, l.y)
        // above the ticker bar
        assertEquals(720 - 36 - 20 - 40, poweredByLayout(1280, 720, 180.0, 1.0, 36).y)
        // portrait: unit = w / 720, bottom gap = 6 % of the height
        val p = poweredByLayout(720, 1280, 180.0, null, 0)
        assertEquals(1280 - Math.round(1280 * 0.06).toInt() - 40, p.y); assertEquals(24 + 180, p.pillW)
    }
}
