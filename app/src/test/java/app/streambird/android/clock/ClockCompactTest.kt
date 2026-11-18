package app.streambird.android.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The compact clock: the 5 s / 3 s alternation and its cross-fade, the date and time strings, and the placement under the logo. */
class ClockCompactTest {
    private val M = ClockLayout.MARGIN
    private val utc = ZoneId.of("UTC")
    private fun fmt(i: String, h: ClockHours = ClockHours.H12, date: Boolean = true, sec: Boolean = false) =
        formatClock(Instant.parse(i), "UTC", h, date, sec, Locale.US, utc)

    // ---- phase ----

    @Test fun `time for 5 s then date for 3 s, repeating`() {
        fun p(ms: Long) = clockPhase(ms, true, true)
        assertEquals(ClockPhase.TIME, p(0)); assertEquals(ClockPhase.TIME, p(4_999))
        assertEquals(ClockPhase.DATE, p(5_000)); assertEquals(ClockPhase.DATE, p(7_999))
        assertEquals(ClockPhase.TIME, p(8_000)); assertEquals(ClockPhase.TIME, p(12_999))
        assertEquals(ClockPhase.DATE, p(13_000))
        assertEquals(ClockPhase.DATE, p(8_000L * 1_000_000 + 5_500)) // far from zero
    }

    @Test fun `phase follows the wall clock also for negative epochs`() {
        assertEquals(ClockPhase.DATE, clockPhase(-1, true, true))      // 7_999 into the cycle
        assertEquals(ClockPhase.DATE, clockPhase(-3_000, true, true))  // 5_000 into the cycle
        assertEquals(ClockPhase.TIME, clockPhase(-3_001, true, true))  // 4_999
    }

    @Test fun `no date or no alternation is always the time`() {
        listOf(0L, 5_000L, 6_000L, 7_999L).forEach {
            assertEquals(ClockPhase.TIME, clockPhase(it, true, false))
            assertEquals(ClockPhase.TIME, clockPhase(it, false, true))
            assertEquals(1f, clockTimeAlpha(it, false, true), 0f); assertEquals(0f, clockDateAlpha(it, false, true), 0f)
            assertEquals(1f, clockTimeAlpha(it, true, false), 0f); assertEquals(0f, clockDateAlpha(it, true, false), 0f)
        }
    }

    @Test fun `cross-fade lasts 200 ms at each boundary and the two alphas add up to one`() {
        fun t(ms: Long) = clockTimeAlpha(ms, true, true)
        fun d(ms: Long) = clockDateAlpha(ms, true, true)
        assertEquals(0f, t(8_000), 0f); assertEquals(0.5f, t(8_100), 1e-6f); assertEquals(1f, t(8_200), 0f) // date -> time
        assertEquals(1f, t(2_000), 0f); assertEquals(1f, t(5_000), 0f); assertEquals(0.5f, t(5_100), 1e-6f); assertEquals(0f, t(5_200), 0f) // time -> date
        assertEquals(0f, t(6_500), 0f); assertEquals(1f, d(6_500), 0f); assertEquals(1f, d(7_999), 0f)
        for (ms in 0L until 16_000L step 7) { assertEquals(1f, t(ms) + d(ms), 1e-6f); assertTrue(t(ms) in 0f..1f) }
    }

    // ---- strings ----

    @Test fun `alternating date is day, short month, year`() {
        assertEquals("18 Nov, 2026", fmt("2026-11-18T15:04:09Z").shortDate)
        assertEquals("5 Jan, 2027", fmt("2027-01-05T15:04:09Z").shortDate)
        assertNull(fmt("2026-11-18T15:04:09Z", date = false).shortDate)
    }

    @Test fun `compact time has a two digit 12 hour hour and the 24 hour time is unchanged`() {
        assertEquals("02:56 PM", fmt("2026-11-18T14:56:00Z").compactTime)
        assertEquals("03:04 AM", fmt("2026-11-18T03:04:00Z").compactTime)
        assertEquals("12:00 AM", fmt("2026-11-18T00:00:00Z").compactTime)
        assertEquals("02:56:07 PM", fmt("2026-11-18T14:56:07Z", sec = true).compactTime)
        assertEquals("14:56", fmt("2026-11-18T14:56:00Z", h = ClockHours.H24).compactTime)
        assertEquals("2:56 PM", fmt("2026-11-18T14:56:00Z").time) // the pill keeps its single digit
        assertEquals("Wed 18 Nov  2:56 PM", fmt("2026-11-18T14:56:00Z").line)
    }

    @Test fun `compact line for both at once`() {
        assertEquals("Wed 18 Nov · 02:56 PM", fmt("2026-11-18T14:56:00Z").compactLine)
        assertEquals("02:56 PM", fmt("2026-11-18T14:56:00Z", date = false).compactLine)
    }

    // ---- placement ----

    private fun text(w: Int, h: Int, p: ClockPosition, logo: ClockRect? = null, bar: Int = 0, tw: Int = 120, th: Int = 28, powered: ClockRect? = null) =
        ClockLayout.clockRect(w, h, p, ClockSize.SMALL, logo, bar, tw, th, ClockStyle.TEXT, powered)

    private fun inside(r: ClockRect, w: Int, h: Int) = r.x >= 0 && r.y >= 0 && r.right <= w && r.bottom <= h

    @Test fun `text size is a share of the short side`() {
        assertEquals(720 * 0.020, ClockLayout.textFontPx(1280, 720, ClockSize.SMALL).toDouble(), 0.01)
        assertEquals(720 * 0.028, ClockLayout.textFontPx(1280, 720, ClockSize.MEDIUM).toDouble(), 0.01)
        assertEquals(720 * 0.038, ClockLayout.textFontPx(1280, 720, ClockSize.LARGE).toDouble(), 0.01)
        assertEquals(720 * 0.028, ClockLayout.textFontPx(720, 1280, ClockSize.MEDIUM).toDouble(), 0.01) // portrait: still the short side
        assertEquals(8f, ClockLayout.textFontPx(100, 80, ClockSize.SMALL), 0f)
    }

    @Test fun `directly under the logo, centred on it, in the logo's corner`() {
        val logo = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = false) // top left, 180 wide
        val r = text(1280, 720, ClockPosition.TOP_LEFT, logo)
        assertEquals(logo.bottom + ClockLayout.textGap(28), r.y)
        assertEquals(logo.x + logo.w / 2, r.x + r.w / 2)
        assertFalse(r.intersects(logo))
        val lr = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = true)
        val rr = text(1280, 720, ClockPosition.TOP_RIGHT, lr)
        assertEquals(lr.x + lr.w / 2, rr.x + rr.w / 2); assertEquals(lr.bottom + ClockLayout.textGap(28), rr.y)
    }

    @Test fun `gap is about 0_6 of the text height`() {
        assertEquals(17, ClockLayout.textGap(28)); assertEquals(6, ClockLayout.textGap(10))
    }

    @Test fun `text wider than a narrow logo is clamped into the margins and stays under it`() {
        val logo = ClockLayout.logoRect(1280, 720, 100, 100, 40, onRight = false) // 40 wide at x=16
        val r = text(1280, 720, ClockPosition.TOP_LEFT, logo, tw = 200)
        assertEquals(M, r.x) // centred would be left of the margin
        assertFalse(r.intersects(logo)); assertTrue(r.y >= logo.bottom)
        val lr = ClockLayout.logoRect(1280, 720, 100, 100, 40, onRight = true)
        val rr = text(1280, 720, ClockPosition.TOP_RIGHT, lr, tw = 200)
        assertEquals(1280 - M, rr.right)
    }

    @Test fun `both phases share the logo's centre line, also in portrait`() {
        val logo = ClockLayout.logoRect(720, 1280, 400, 200, 100, onRight = true)
        val a = text(720, 1280, ClockPosition.TOP_RIGHT, logo, tw = 90)
        val b = text(720, 1280, ClockPosition.TOP_RIGHT, logo, tw = 130)
        assertEquals((a.x + a.w / 2).toDouble(), (b.x + b.w / 2).toDouble(), 1.0)
        assertEquals(a.y, b.y); assertFalse(a.intersects(logo)); assertFalse(b.intersects(logo))
    }

    @Test fun `a frame wide logo pushes either top corner's text below it, centred`() {
        val logo = ClockLayout.logoRect(720, 1280, 3000, 100, 80, onRight = false)
        listOf(ClockPosition.TOP_LEFT, ClockPosition.TOP_RIGHT).forEach { p ->
            val r = text(720, 1280, p, logo)
            assertFalse(r.intersects(logo)); assertEquals(logo.bottom + ClockLayout.textGap(28), r.y)
            assertEquals(720 / 2, r.x + r.w / 2)
        }
    }

    @Test fun `the other top corner than the logo aligns to its corner`() {
        val logo = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = false)
        val r = text(1280, 720, ClockPosition.TOP_RIGHT, logo)
        assertEquals(1280 - M, r.right); assertEquals(M, r.y); assertFalse(r.intersects(logo))
    }

    @Test fun `no logo means the plain corner, right aligned on the right`() {
        assertEquals(ClockRect(M, M, 120, 28), text(1280, 720, ClockPosition.TOP_LEFT))
        assertEquals(ClockRect(1280 - M - 120, M, 120, 28), text(1280, 720, ClockPosition.TOP_RIGHT))
        assertEquals(1280 - M, text(1280, 720, ClockPosition.TOP_RIGHT, tw = 170).right) // a wider text keeps its right edge
    }

    @Test fun `bottom corners keep clear of the ticker bar and the Powered by mark`() {
        assertEquals(720 - 40 - M, text(1280, 720, ClockPosition.BOTTOM_LEFT, bar = 40).bottom)
        val powered = ClockRect(1280 - 20 - 180, 720 - 20 - 40, 180, 40)
        val r = text(1280, 720, ClockPosition.BOTTOM_RIGHT, powered = powered)
        assertFalse(r.intersects(powered)); assertEquals(powered.y - ClockLayout.STACK_GAP, r.bottom)
        assertEquals(720 - M, text(1280, 720, ClockPosition.BOTTOM_LEFT, powered = powered).bottom) // not near the mark: stays in its corner
    }

    @Test fun `text under a tall logo on a small canvas stays inside`() {
        val logo = ClockLayout.logoRect(300, 200, 100, 100, 150, onRight = false)
        assertTrue(inside(text(300, 200, ClockPosition.TOP_LEFT, logo), 300, 200))
    }

    @Test fun `tiny and degenerate canvases stay inside`() {
        ClockPosition.entries.forEach { p ->
            val r = ClockLayout.clockRect(60, 40, p, ClockSize.LARGE, null, 10, 500, 30, ClockStyle.TEXT)
            assertTrue("$p $r", inside(r, 60, 40)); assertTrue(r.w >= 1 && r.h >= 1)
        }
        assertEquals(ClockRect(0, 0, 1, 1), ClockLayout.clockRect(1, 1, ClockPosition.BOTTOM_RIGHT, ClockSize.SMALL, null, 0, 100, 10, ClockStyle.TEXT))
        val logo = ClockLayout.logoRect(100, 60, 50, 50, 40, onRight = true)
        ClockPosition.entries.forEach { p -> assertTrue(inside(ClockLayout.clockRect(100, 60, p, ClockSize.MEDIUM, logo, 8, 90, 10, ClockStyle.TEXT), 100, 60)) }
        assertTrue(inside(ClockLayout.clockRect(1280, 720, ClockPosition.TOP_LEFT, ClockSize.SMALL, null, 0, 0, 0, ClockStyle.TEXT), 1280, 720))
    }

    @Test fun `PILL placement is exactly what it was`() {
        val logo = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = true)
        val old = ClockLayout.clockRect(1280, 720, ClockPosition.TOP_RIGHT, ClockSize.MEDIUM, logo, 0, 200, 24)
        val viaStyle = ClockLayout.clockRect(1280, 720, ClockPosition.TOP_RIGHT, ClockSize.MEDIUM, logo, 0, 200, 24, ClockStyle.PILL, ClockRect(0, 0, 5000, 5000))
        assertEquals(old, viaStyle)
        assertEquals(logo.bottom + ClockLayout.STACK_GAP, old.y); assertEquals(1280 - M, old.right) // stacked below the logo, right aligned
        assertNotEquals(old, text(1280, 720, ClockPosition.TOP_RIGHT, logo, tw = 200, th = 24))
    }
}
