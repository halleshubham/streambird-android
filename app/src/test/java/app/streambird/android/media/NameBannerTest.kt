package app.streambird.android.media

import app.streambird.android.clock.ClockRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text rules, state patching, fades and the pure placement / draw plan of the name banner. */
class NameBannerTest {
    // ---- text ----

    @Test fun `clean strips control and bidi characters and collapses whitespace`() {
        assertEquals("Jane Doe", NameBanner.clean("  Jane \n\t Doe  ", 80))
        assertEquals("Jane Doe", NameBanner.clean("Jane\u0000 \u0007Doe", 80))
        assertEquals("Jane Doe", NameBanner.clean("‮Jane‬ ⁦Doe⁩‎‏", 80))   // bidi override / isolate / marks
        assertEquals("JaneDoe", NameBanner.clean("Jane​‍﻿Doe", 80))                          // zero width and byte order mark
        assertEquals("a b", NameBanner.clean("a  b", 80))
        assertEquals("", NameBanner.clean("‮‬ \n", 80))
        assertEquals("", NameBanner.clean(null, 80))
    }

    @Test fun `clean caps the length and never splits a surrogate pair`() {
        assertEquals("a".repeat(80), NameBanner.cleanName("a".repeat(200)))
        val emojis = "😀".repeat(60)                       // 120 chars
        val cut = NameBanner.clean(emojis, 5)                         // 5th char would be half of the third emoji
        assertEquals(4, cut.length); assertFalse(Character.isHighSurrogate(cut.last()))
        assertEquals("a".repeat(NameBanner.TITLE_MAX), NameBanner.cleanTitle("a".repeat(500)))
        assertNull(NameBanner.cleanTitle("  ‮ "))
        assertNull(NameBanner.cleanTitle(null))
    }

    // ---- state ----

    @Test fun `patched changes only what is given`() {
        val s = NameBannerState()
        val a = s.patched("Jane", true, "Host", null)
        assertEquals(NameBannerState("Jane", "Host", false), a)
        assertFalse(a.visible)                                         // a name alone does not show it
        val b = a.patched(null, false, null, true)
        assertEquals(NameBannerState("Jane", "Host", true), b); assertTrue(b.visible)
        val c = b.patched(null, true, null, null)                      // title: null = remove it
        assertNull(c.title); assertEquals("Jane", c.name)
        val d = c.patched("  ‮ ", false, null, null)             // a name that cleans to nothing clears it: nothing to show
        assertEquals("", d.name); assertFalse(d.visible)
    }

    @Test fun `spec keeps its version while the text is unchanged so the bitmap is rebuilt only for new text`() {
        val a = BannerSpec.next(null, NameBannerState("Jane", "Host", true))!!
        assertEquals(1L, a.version)
        val hidden = BannerSpec.next(a, NameBannerState("Jane", "Host", false))!!
        assertEquals(1L, hidden.version); assertFalse(hidden.visible)
        val shownAgain = BannerSpec.next(hidden, NameBannerState("Jane", "Host", true))!!
        assertEquals(1L, shownAgain.version)
        assertEquals(2L, BannerSpec.next(shownAgain, NameBannerState("Jane", "Guest", true))!!.version)
        assertEquals(2L, BannerSpec.next(shownAgain, NameBannerState("John", "Host", true))!!.version)
        assertNull(BannerSpec.next(shownAgain, NameBannerState("", null, true)))      // no name = nothing to draw
    }

    // ---- fade ----

    @Test fun `fade takes about 250 ms in and out and never overshoots`() {
        var a = 0f
        a = NameBanner.fadeStep(a, 1f, 100); assertEquals(0.4f, a, 1e-4f)
        a = NameBanner.fadeStep(a, 1f, 100); assertEquals(0.8f, a, 1e-4f)
        a = NameBanner.fadeStep(a, 1f, 100); assertEquals(1f, a, 0f)
        a = NameBanner.fadeStep(a, 0f, 125); assertEquals(0.5f, a, 1e-4f)
        a = NameBanner.fadeStep(a, 0f, 1000); assertEquals(0f, a, 0f)
        assertEquals(0.3f, NameBanner.fadeStep(0.3f, 1f, -50), 0f)    // a negative step changes nothing
    }

    // ---- placement ----

    private val minMargin = NameBannerLayout.MARGIN

    @Test fun `landscape without a ticker sits bottom left above the bottom gap`() {
        val r = NameBannerLayout.rect(1280, 720, 0, 400, 80)
        assertEquals(minMargin, r.x)
        assertEquals(720 - NameBannerLayout.bottomGap(1280, 720) - 80, r.y)
        assertEquals(400, r.w); assertEquals(80, r.h)
        assertTrue(r.bottom <= 720 - minMargin)
    }

    @Test fun `a ticker bar pushes it up so the two never overlap`() {
        val bar = 36
        val r = NameBannerLayout.rect(1280, 720, bar, 400, 80)
        assertEquals(720 - bar - minMargin - 80, r.y)
        assertTrue(r.bottom <= 720 - bar)
        val none = NameBannerLayout.rect(1280, 720, 0, 400, 80)
        assertTrue(r.y < none.y)
        // for every ticker size the bar stays below the banner
        for (b in listOf(1, 20, 36, 70, 120)) assertTrue(NameBannerLayout.rect(1280, 720, b, 400, 80).bottom <= 720 - b)
    }

    @Test fun `vertical 9 16 keeps a larger safe gap at the bottom`() {
        val r = NameBannerLayout.rect(720, 1280, 0, 500, 100)
        assertEquals(Math.round(1280 * 0.06).toInt(), NameBannerLayout.bottomGap(720, 1280))
        assertEquals(1280 - 77 - 100, r.y)
        assertEquals(minMargin, r.x)
        assertTrue(NameBannerLayout.bottomGap(720, 1280) > NameBannerLayout.bottomGap(1280, 720))
        val withTicker = NameBannerLayout.rect(720, 1280, 38, 500, 100)
        assertEquals(1280 - 38 - minMargin - 100, withTicker.y)
    }

    @Test fun `a clock in the same corner pushes it up and a clock elsewhere does not`() {
        val clock = ClockRect(minMargin, 720 - 36 - minMargin - 30, 120, 30)
        val r = NameBannerLayout.rect(1280, 720, 36, 400, 80, clock = clock)
        assertEquals(clock.y - NameBannerLayout.STACK_GAP - 80, r.y)
        assertFalse(r.intersects(clock))
        val farRight = ClockRect(1280 - minMargin - 120, 720 - 36 - minMargin - 30, 120, 30)
        assertEquals(NameBannerLayout.rect(1280, 720, 36, 400, 80), NameBannerLayout.rect(1280, 720, 36, 400, 80, clock = farRight))
    }

    @Test fun `the Powered by mark limits the width and lifts the bar when they still touch`() {
        val powered = ClockRect(1000, 650, 240, 40)
        assertEquals(1000 - NameBannerLayout.STACK_GAP - minMargin, NameBannerLayout.maxBarWidth(1280, powered))
        assertEquals(1280 - 2 * minMargin, NameBannerLayout.maxBarWidth(1280, null))
        val wide = NameBannerLayout.rect(1280, 720, 0, 1200, 80, powered = powered)    // too wide for the room: lifted above the mark
        assertFalse(wide.intersects(powered))
        val narrow = NameBannerLayout.rect(1280, 720, 0, 500, 80, powered = powered)
        assertFalse(narrow.intersects(powered))
        assertEquals(1, NameBannerLayout.maxBarWidth(100, ClockRect(10, 0, 50, 10)))   // never below 1
    }

    @Test fun `bar size follows the text and is cut down to the room`() {
        val (w, h) = NameBannerLayout.barSize(1280, 720, 200, 36, 150, 24, 1248)
        assertEquals(NameBannerLayout.accentPx(1280, 720) + 2 * NameBannerLayout.padX(1280, 720) + 200, w)
        assertEquals(2 * NameBannerLayout.padY(1280, 720) + 36 + NameBannerLayout.lineGap(1280, 720) + 24, h)
        val (w2, h2) = NameBannerLayout.barSize(1280, 720, 200, 36, null, null, 1248)      // no title: one line
        assertEquals(w, w2); assertEquals(2 * NameBannerLayout.padY(1280, 720) + 36, h2)
        assertEquals(300, NameBannerLayout.barSize(1280, 720, 5000, 36, null, null, 300).first)
        assertEquals(1280, NameBannerLayout.barSize(1280, 720, 5000, 36, null, null, 9000).first)   // never wider than the canvas
    }

    @Test fun `fonts scale with the short side and have a floor`() {
        assertEquals(30f, NameBannerLayout.nameFontPx(1280, 720), 0.01f)
        assertEquals(30f, NameBannerLayout.nameFontPx(720, 1280), 0.01f)           // the short side counts, not the orientation
        assertEquals(45f, NameBannerLayout.nameFontPx(1920, 1080), 0.01f)
        assertEquals(20f, NameBannerLayout.titleFontPx(1280, 720), 0.01f)
        assertTrue(NameBannerLayout.nameFontPx(64, 36) >= 9f)
        assertTrue(NameBannerLayout.titleFontPx(64, 36) >= 7f)
    }

    @Test fun `tiny and degenerate canvases always give a rectangle inside the canvas`() {
        for ((cw, ch) in listOf(64 to 36, 36 to 64, 20 to 20, 1 to 1, 10 to 200, 300 to 8)) {
            for (bar in listOf(0, 4, 50)) {
                val r = NameBannerLayout.rect(cw, ch, bar, 500, 300, clock = ClockRect(0, 0, cw, ch))
                assertTrue("$cw x $ch bar $bar -> $r", r.x >= 0 && r.y >= 0 && r.right <= cw && r.bottom <= ch && r.w >= 1 && r.h >= 1)
            }
        }
        val r = NameBannerLayout.rect(100, 100, 0, 0, 0)
        assertTrue(r.w >= 1 && r.h >= 1)
    }

    // ---- draw plan ----

    @Test fun `draw plan draws nothing when faded out or empty and otherwise places the bar with the alpha`() {
        assertNull(NameBannerLayout.drawPlan(1280, 720, 0, 400, 80, 0f))
        assertNull(NameBannerLayout.drawPlan(1280, 720, 0, 400, 80, 0.001f))
        assertNull(NameBannerLayout.drawPlan(1280, 720, 0, 0, 80, 1f))
        assertNull(NameBannerLayout.drawPlan(1280, 720, 0, 400, 0, 1f))
        val p = NameBannerLayout.drawPlan(1280, 720, 36, 400, 80, 0.5f)
        assertNotNull(p)
        assertEquals(0.5f, p!!.alpha, 0f)
        assertEquals(NameBannerLayout.rect(1280, 720, 36, 400, 80), p.rect)
        assertEquals(1f, NameBannerLayout.drawPlan(1280, 720, 36, 400, 80, 3f)!!.alpha, 0f)
    }

    @Test fun `the plan is the same for equal inputs so the compositor can cache it`() {
        assertEquals(NameBannerLayout.rect(1280, 720, 36, 400, 80), NameBannerLayout.rect(1280, 720, 36, 400, 80))
    }
}
