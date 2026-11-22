package app.streambird.android.ads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The ad-sequence scheduler: golden cases copied from the web studio's test/ui/ad-sequences-pure.spec.ts (the same vectors, so the two scheduler ports cannot drift),
 * plus the Android-specific helpers (picture budget, scene blend).
 */
class AdSequenceTest {
    private fun on(sec: Int) = SeqAd(true, sec)
    private fun off(sec: Int) = SeqAd(false, sec)
    private fun interval(every: Int): SeqSchedule = SeqSchedule.Interval(every)
    private val cont: SeqSchedule = SeqSchedule.Continuous
    private fun at(t: Double, ads: List<SeqAd>, sch: SeqSchedule) = adSequenceState(t, ads, sch, ads.size, SeqState())
    private fun near(e: Double, a: Double, eps: Double = 1e-6) = assertTrue("$a != $e", abs(e - a) < eps)

    // ---- one ad is exactly the old schedule ----

    @Test fun `interval - one ad equals the single-ad schedule at every moment of three cycles`() {
        val ads = listOf(on(15))
        var wrong = 0
        var t = 0.0
        while (t < 600) {
            val s = at(t, ads, interval(180))
            val old = adVisibility(t, AdSchedule.Interval(15, 180))
            if (s.visible != old.visible || abs(s.alpha - old.alpha) > 1e-9 || s.index != 0 || s.from != -1) wrong++
            t += 0.05
        }
        assertEquals(0, wrong)
    }

    @Test fun `continuous - one ad is always on at alpha 1, whatever the clock`() {
        for (t in listOf(-5.0, 0.0, 0.1, 19.9, 20.0, 1234.5)) {
            val s = at(t, listOf(on(20)), cont)
            assertEquals(0, s.index); assertEquals(1.0, s.alpha, 0.0); assertTrue(s.visible); assertEquals(-1, s.from)
        }
    }

    // ---- now and then (round-robin) ----

    private val three = listOf(on(10), on(20), on(30))   // every 100 s: cycles 110 / 120 / 130, one round = 360 s
    private fun s3(t: Double) = at(t, three, interval(100))

    @Test fun `interval - each time the show window comes round the NEXT ad shows, and the round repeats`() {
        s3(50.0).let { assertEquals(0, it.index); assertFalse(it.visible); assertEquals(0.0, it.alpha, 0.0) }   // hidden part of ad 1's cycle: ad 1 shows next
        s3(105.0).let { assertEquals(0, it.index); assertTrue(it.visible); assertEquals(1.0, it.alpha, 0.0) }
        s3(112.0).let { assertEquals(1, it.index); assertFalse(it.visible) }                                  // ad 2's cycle began at 110
        s3(215.0).let { assertEquals(1, it.index); assertTrue(it.visible); assertEquals(1.0, it.alpha, 0.0) }
        s3(235.0).let { assertEquals(2, it.index); assertFalse(it.visible) }
        s3(335.0).let { assertEquals(2, it.index); assertTrue(it.visible); assertEquals(1.0, it.alpha, 0.0) }
        assertEquals(2, s3(359.9).index)
        s3(360.0).let { assertEquals(0, it.index); assertFalse(it.visible) }                                  // round 2 starts with ad 1 again
        s3(360.0 + 105).let { assertEquals(0, it.index); assertTrue(it.visible) }
        s3(360.0 + 215).let { assertEquals(1, it.index); assertTrue(it.visible) }
    }

    @Test fun `interval - every ad has its own show time and fades like a single ad`() {
        near(0.5, s3(100.15).alpha); near(0.5, s3(109.85).alpha)         // ad 1: half-way through its fade in / out
        assertEquals(0.0, s3(205.0).alpha, 0.0)                           // ad 2: its hidden part (110..210)
        near(0.5, s3(210.15).alpha); near(0.5, s3(229.85).alpha)         // its 20 s show window (210..230)
        assertEquals(1.0, s3(230.0 + 100 + 15).alpha, 0.0)                // ad 3 holds at 1 for its 30 s
    }

    @Test fun `interval - seconds left count down to the next flip of the current ad`() {
        assertEquals(60, s3(40.0).secondsLeft)      // hidden until 100
        assertEquals(6, s3(104.0).secondsLeft)      // shown until 110
        assertEquals(95, s3(115.0).secondsLeft)     // ad 2 hidden until 210
    }

    @Test fun `interval - disabled ads are skipped, all disabled = nothing is on, one enabled among several is a single ad`() {
        val skip = listOf(on(10), off(20), on(30))                        // cycles 110 / 130, round 240
        assertEquals(2, at(120.0, skip, interval(100)).index)
        assertEquals(0, at(240.0 + 5, skip, interval(100)).index)
        at(5.0, listOf(off(10), off(20)), interval(100)).let { assertEquals(-1, it.index); assertEquals(0.0, it.alpha, 0.0); assertFalse(it.visible) }
        assertEquals(-1, at(5.0, emptyList(), cont).index)
        var t = 0.0
        while (t < 400) {
            val one = at(t, listOf(off(10), on(15), off(5)), interval(180))
            val old = adVisibility(t, AdSchedule.Interval(15, 180))
            assertEquals(1, one.index); assertEquals(old.visible, one.visible); near(old.alpha, one.alpha, 1e-9)
            t += 0.5
        }
    }

    // ---- always on (dwell + cross-fade) ----

    private val twenty = listOf(on(20), on(20), on(20))
    private fun c3(t: Double) = at(t, twenty, cont)

    @Test fun `continuous - each ad is shown for its own seconds, then the next, and the round repeats`() {
        c3(10.0).let { assertEquals(0, it.index); assertEquals(1.0, it.alpha, 0.0); assertEquals(-1, it.from) }
        assertEquals(1, c3(30.0).index); assertEquals(2, c3(50.0).index); assertEquals(0, c3(70.0).index)
        val uneven = listOf(on(5), on(300), on(7))
        assertEquals(0, at(4.0, uneven, cont).index); assertEquals(1, at(6.0, uneven, cont).index)
        assertEquals(2, at(305.0, uneven, cont).index); assertEquals(0, at(312.0, uneven, cont).index)
    }

    @Test fun `continuous - a change is a cross-fade, the alphas sum to 1`() {
        for (dt in listOf(0.03, 0.15, 0.27)) {
            val st = c3(20 + dt)
            assertEquals(1, st.index); assertEquals(0, st.from)
            near(1.0, st.alpha + st.fromAlpha, 1e-9); near(dt / 0.3, st.alpha)
        }
        near(0.5, c3(20.15).alpha)
        c3(20.3).let { assertEquals(1, it.index); assertEquals(1.0, it.alpha, 0.0); assertEquals(-1, it.from) }
        val w = c3(60.0 + 0.15)                                            // the wrap from the last ad to the first fades the same way
        assertEquals(0, w.index); assertEquals(2, w.from); near(0.5, w.alpha)
    }

    @Test fun `continuous - the start of the stream shows the first ad at once`() {
        for (t in listOf(0.0, 0.1, -3.0)) c3(t).let { assertEquals(0, it.index); assertEquals(1.0, it.alpha, 0.0); assertEquals(-1, it.from) }
    }

    @Test fun `continuous - disabled ads are skipped, a missing or broken time counts as 20 s`() {
        val skip = listOf(on(20), off(20), on(20))
        assertEquals(2, at(25.0, skip, cont).index); assertEquals(0, at(45.0, skip, cont).index)
        val broken = listOf(on(0), on(-4))
        assertEquals(0, at(10.0, broken, cont).index); assertEquals(1, at(25.0, broken, cont).index)
        assertEquals(20, AD_DWELL_DEFAULT); assertEquals(5, AD_DWELL_MIN); assertEquals(300, AD_DWELL_MAX); assertEquals(5, MAX_ADS_PER_PLACEMENT); assertEquals(16, MAX_MORE_ADS)
    }

    @Test fun `continuous - a very short dwell still cross-fades without gaps`() {
        val quick = listOf(on(1), on(1))                                  // (the web test uses 0.4 s; the Android scheduler takes whole seconds: fade = half the dwell, 0.3 at most)
        val st = at(1.15, quick, cont)
        assertEquals(1, st.index); near(1.0, st.alpha + st.fromAlpha, 1e-9)
    }

    // ---- targets: manual Show / Hide act on the placement ----

    private val out = DoubleArray(5)
    private fun targets(t: Double, sch: SeqSchedule, manual: AdManual?, list: List<SeqAd> = three): Pair<Double, List<Double>> {
        val sum = adSequenceTargets(t, list, list.size, sch, manual, out, SeqState())
        return sum to out.take(list.size)
    }

    @Test fun `targets - following the schedule only the current ad has an alpha, the placement alpha is min 1 sum`() {
        assertEquals(0.0 to listOf(0.0, 0.0, 0.0), targets(50.0, interval(100), null))
        assertEquals(1.0 to listOf(1.0, 0.0, 0.0), targets(105.0, interval(100), null))
        val x = targets(20.15, cont, null, listOf(on(20), on(20)))
        near(0.5, x.second[0]); near(0.5, x.second[1]); near(1.0, x.first)
    }

    @Test fun `targets - Hide hides the whole placement, Show now shows the current or next ad also while the schedule is hidden`() {
        assertEquals(0.0 to listOf(0.0, 0.0, 0.0), targets(105.0, interval(100), AdManual.HIDDEN))
        assertEquals(1.0 to listOf(1.0, 0.0, 0.0), targets(50.0, interval(100), AdManual.SHOWN))    // ad 1's hidden part: ad 1 is the next
        assertEquals(1.0 to listOf(0.0, 1.0, 0.0), targets(150.0, interval(100), AdManual.SHOWN))   // ad 2's hidden part: ad 2
        assertEquals(0.0, targets(50.0, cont, AdManual.HIDDEN).first, 0.0)
        assertEquals(listOf(1.0, 0.0, 0.0), targets(5.0, cont, AdManual.SHOWN).second)              // always on: manual shown is the rotation as it is
    }

    @Test fun `targets - no enabled ad shows nothing, manual or not`() {
        assertEquals(0.0, targets(5.0, cont, AdManual.SHOWN, listOf(off(20))).first, 0.0)
        assertEquals(listOf(0.0, 0.0), targets(5.0, interval(10), null, listOf(off(20), off(20))).second)
    }

    // ---- priority ----

    @Test fun `priority factors give exactly what resolveAdAlphas does`() {
        val combos = listOf(
            emptyList(), listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD), listOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD, AdPlacement.L_SHAPE),
            AdPlacement.entries, listOf(AdPlacement.FULL_SCREEN, AdPlacement.L_SHAPE),
        )
        for (onSet in combos) {
            val own = DoubleArray(4) { if (AdPlacement.entries[it] in onSet) 1.0 else 0.0 }
            val f = adPriorityFactors(own)
            val resolved = resolveAdAlphas(0.0, onSet.map { AdSlot(it, true, AdSchedule.Continuous) })
            for (p in AdPlacement.entries) assertEquals(own[p.ordinal] * f[p.ordinal], resolved[p.ordinal], 0.0)
        }
        val f = adPriorityFactors(doubleArrayOf(1.0, 1.0, 0.5, 1.0))   // lower, side, full, l_shape
        assertArrayEquals(doubleArrayOf(0.5 * 0.5, 0.5 * 0.5, 1.0, 0.5), f, 1e-12)
    }

    // ---- picture budget (GPU memory) ----

    private fun wanted(t: Double, list: List<SeqAd>, sch: SeqSchedule): List<Int> {
        val st = adSequenceState(t, list, sch, list.size, SeqState())
        val w = BooleanArray(5)
        wantedAds(st, list, list.size, sch, w)
        return w.take(list.size).withIndex().filter { it.value }.map { it.index }
    }

    @Test fun `only the current and the next ad keep a picture on the GPU, however long the sequence`() {
        val five = List(5) { on(20) }
        assertEquals(listOf(0, 1), wanted(5.0, five, cont))
        assertEquals(listOf(2, 3), wanted(45.0, five, cont))
        assertEquals(listOf(0, 4), wanted(85.0, five, cont))           // the last ad: the next is the first again
        for (t in 0..200) assertTrue("at $t", wanted(t.toDouble(), five, cont).size <= 3)
        // During a cross-fade the ad fading out is kept too (3 pictures at most).
        assertEquals(listOf(0, 1, 2), wanted(20.15, five, cont))
        // Interval: the current (hidden part: the one that shows next) and the one after it.
        assertEquals(listOf(0, 1), wanted(10.0, five, interval(100)))
        assertEquals(listOf(1, 2), wanted(130.0, five, interval(100)))
        // Disabled ads are never wanted; a single ad wants only itself; nothing on wants nothing.
        assertEquals(listOf(0, 2), wanted(5.0, listOf(on(20), off(20), on(20)), cont))
        assertEquals(listOf(0), wanted(5.0, listOf(on(20)), cont))
        assertEquals(emptyList<Int>(), wanted(5.0, listOf(off(20), off(20)), cont))
    }

    // ---- the scene follows a sequence ----

    private val r1 = AdRect(0.0, 480.0, 1280.0, 240.0)
    private val r2 = AdRect(0.0, 360.0, 1280.0, 360.0)

    @Test fun `a single showing ad returns its own alpha and rectangle untouched, like before sequences`() {
        val c = combinePlacement(listOf(AdBlendIn(0.6, r1, null), AdBlendIn(0.0, r2, null)), windowed = false)
        assertEquals(0.6, c.alpha, 0.0); assertEquals(r1, c.rect)
        val none = combinePlacement(listOf(AdBlendIn(0.0, r1, null)), windowed = false)
        assertEquals(0.0, none.alpha, 0.0); assertNull(none.rect)
    }

    @Test fun `during a cross-fade the rectangle eases between the two ads and the alpha is capped at 1`() {
        val half = combinePlacement(listOf(AdBlendIn(0.5, r1, null), AdBlendIn(0.5, r2, null)), windowed = false)
        assertEquals(1.0, half.alpha, 1e-12)
        near(0.5 * (r1.y + r2.y), half.rect!!.y)                         // equal shares: the middle
        val mostlyNew = combinePlacement(listOf(AdBlendIn(0.1, r1, null), AdBlendIn(0.9, r2, null)), windowed = false)
        assertTrue(abs(mostlyNew.rect!!.y - r2.y) < abs(mostlyNew.rect!!.y - r1.y))   // closer to the ad that is nearly fully in (smoothstep weighted)
        val a = shrinkEase(0.1 / 1.0); val b = shrinkEase(0.9 / 1.0)
        near((r1.y * a + r2.y * b) / (a + b), mostlyNew.rect!!.y)
    }

    @Test fun `frames - only ads with a window count, and the scene window is the blend of their windows`() {
        val w1 = AdRect(100.0, 50.0, 800.0, 450.0); val w2 = AdRect(300.0, 50.0, 600.0, 340.0)
        val c = combinePlacement(listOf(AdBlendIn(0.5, r1, w1), AdBlendIn(0.5, r2, w2), AdBlendIn(0.4, r1, null)), windowed = true)
        assertNotNull(c.win)
        near(1.0, c.alpha)                                              // the window-less frame does not add to the sum (it is an overlay)
        near(200.0, c.win!!.x)
        val noWin = combinePlacement(listOf(AdBlendIn(1.0, r1, null)), windowed = true)
        assertEquals(0.0, noWin.alpha, 0.0); assertNull(noWin.win)
    }
}
