package app.streambird.android.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the clock goes: four corners, landscape and portrait, the logo, the ticker bar and canvases too small for it. */
class ClockLayoutTest {
    private val M = ClockLayout.MARGIN
    private fun rect(w: Int, h: Int, p: ClockPosition, size: ClockSize = ClockSize.MEDIUM, logo: ClockRect? = null, bar: Int = 0, tw: Int = 200, th: Int = 24) =
        ClockLayout.clockRect(w, h, p, size, logo, bar, tw, th)

    private fun inside(r: ClockRect, w: Int, h: Int) = r.x >= 0 && r.y >= 0 && r.right <= w && r.bottom <= h

    @Test fun `font is a share of the canvas height`() {
        assertEquals(1280 * 0.022, ClockLayout.fontPx(1280, ClockSize.SMALL).toDouble(), 0.01)
        assertEquals(1280 * 0.03, ClockLayout.fontPx(1280, ClockSize.MEDIUM).toDouble(), 0.01)
        assertEquals(1280 * 0.042, ClockLayout.fontPx(1280, ClockSize.LARGE).toDouble(), 0.01)
        assertEquals(720 * 0.03, ClockLayout.fontPx(720, ClockSize.MEDIUM).toDouble(), 0.01)
        assertEquals(8f, ClockLayout.fontPx(100, ClockSize.SMALL), 0f)
    }

    @Test fun `four corners in landscape`() {
        val w = 1280; val h = 720
        val tl = rect(w, h, ClockPosition.TOP_LEFT); val tr = rect(w, h, ClockPosition.TOP_RIGHT)
        val bl = rect(w, h, ClockPosition.BOTTOM_LEFT); val br = rect(w, h, ClockPosition.BOTTOM_RIGHT)
        assertEquals(M, tl.x); assertEquals(M, tl.y)
        assertEquals(w - M, tr.right); assertEquals(M, tr.y)
        assertEquals(M, bl.x); assertEquals(h - M, bl.bottom)
        assertEquals(w - M, br.right); assertEquals(h - M, br.bottom)
        listOf(tl, tr, bl, br).forEach { assertTrue(inside(it, w, h)) }
        assertEquals(tl.w, br.w); assertEquals(tl.h, br.h)
    }

    @Test fun `four corners in portrait`() {
        val w = 720; val h = 1280
        assertEquals(ClockRect(M, M, rect(w, h, ClockPosition.TOP_LEFT).w, rect(w, h, ClockPosition.TOP_LEFT).h), rect(w, h, ClockPosition.TOP_LEFT))
        val br = rect(w, h, ClockPosition.BOTTOM_RIGHT)
        assertEquals(w - M, br.right); assertEquals(h - M, br.bottom)
        val tr = rect(w, h, ClockPosition.TOP_RIGHT)
        assertEquals(w - M, tr.right); assertEquals(M, tr.y)
    }

    @Test fun `pill is text plus padding`() {
        val f = ClockLayout.fontPx(720, ClockSize.MEDIUM)
        val r = rect(1280, 720, ClockPosition.TOP_LEFT, tw = 200, th = 24)
        assertEquals(200 + 2 * ClockLayout.padX(f), r.w)
        assertEquals(24 + 2 * ClockLayout.padY(f), r.h)
    }

    @Test fun `a bigger size gives a bigger pill padding`() {
        assertTrue(ClockLayout.padX(ClockLayout.fontPx(720, ClockSize.LARGE)) > ClockLayout.padX(ClockLayout.fontPx(720, ClockSize.SMALL)))
    }

    @Test fun `bottom corners sit above the ticker bar`() {
        val bar = 36
        val bl = rect(1280, 720, ClockPosition.BOTTOM_LEFT, bar = bar)
        val br = rect(1280, 720, ClockPosition.BOTTOM_RIGHT, bar = bar)
        assertEquals(720 - bar - M, bl.bottom); assertEquals(720 - bar - M, br.bottom)
        assertFalse(bl.bottom > 720 - bar)
        // top corners ignore the bar
        assertEquals(M, rect(1280, 720, ClockPosition.TOP_RIGHT, bar = bar).y)
    }

    @Test fun `no ticker means no reserved space`() {
        assertEquals(720 - M, rect(1280, 720, ClockPosition.BOTTOM_LEFT, bar = 0).bottom)
        assertEquals(720 - M, rect(1280, 720, ClockPosition.BOTTOM_LEFT, bar = -5).bottom)
    }

    @Test fun `top clock in the logo's corner goes below the logo`() {
        val logo = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = true) // top right
        val r = rect(1280, 720, ClockPosition.TOP_RIGHT, logo = logo)
        assertFalse(r.intersects(logo))
        assertEquals(logo.bottom + ClockLayout.STACK_GAP, r.y)
        assertEquals(1280 - M, r.right)
    }

    @Test fun `top clock in the other corner than the logo stays in place`() {
        val logo = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = false) // top left
        val r = rect(1280, 720, ClockPosition.TOP_RIGHT, logo = logo)
        assertEquals(M, r.y)
        assertFalse(r.intersects(logo))
    }

    @Test fun `a logo as wide as the frame pushes either top corner below it`() {
        val logo = ClockLayout.logoRect(720, 1280, 3000, 100, 80, onRight = false) // clamped to the frame width
        assertEquals(720 - 2 * M, logo.w)
        listOf(ClockPosition.TOP_LEFT, ClockPosition.TOP_RIGHT).forEach { p ->
            val r = rect(720, 1280, p, logo = logo)
            assertFalse(r.intersects(logo)); assertEquals(logo.bottom + ClockLayout.STACK_GAP, r.y)
        }
    }

    @Test fun `bottom clock is not moved by a top logo`() {
        val logo = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = true)
        assertEquals(720 - M, rect(1280, 720, ClockPosition.BOTTOM_RIGHT, logo = logo).bottom)
    }

    @Test fun `logo rect follows drawLogo`() {
        val l = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = false)
        assertEquals(ClockRect(M, M, 180, 90), l)
        val r = ClockLayout.logoRect(1280, 720, 400, 200, 90, onRight = true)
        assertEquals(1280 - M - 180, r.x)
        assertEquals(8, ClockLayout.logoRect(1280, 720, 100, 100, 1, true).h) // height clamped to at least 8
    }

    @Test fun `tiny canvas keeps the pill inside`() {
        listOf(ClockPosition.entries).flatten().forEach { p ->
            val r = ClockLayout.clockRect(60, 40, p, ClockSize.LARGE, null, 10, 500, 30)
            assertTrue("$p $r", inside(r, 60, 40))
            assertTrue(r.w >= 1 && r.h >= 1)
        }
        val z = ClockLayout.clockRect(1, 1, ClockPosition.BOTTOM_RIGHT, ClockSize.SMALL, null, 0, 100, 10)
        assertEquals(ClockRect(0, 0, 1, 1), z)
    }

    @Test fun `tiny canvas with logo and ticker still stays inside`() {
        val logo = ClockLayout.logoRect(100, 60, 50, 50, 40, onRight = true)
        ClockPosition.entries.forEach { p ->
            val r = ClockLayout.clockRect(100, 60, p, ClockSize.MEDIUM, logo, 8, 90, 10)
            assertTrue("$p $r", inside(r, 100, 60))
        }
    }

    @Test fun `zero sized text does not break`() {
        val r = ClockLayout.clockRect(1280, 720, ClockPosition.TOP_LEFT, ClockSize.SMALL, null, 0, 0, 0)
        assertTrue(inside(r, 1280, 720))
    }

    @Test fun `rect intersection`() {
        val a = ClockRect(0, 0, 10, 10)
        assertTrue(a.intersects(ClockRect(5, 5, 10, 10)))
        assertFalse(a.intersects(ClockRect(10, 0, 5, 5)))
        assertFalse(a.intersects(ClockRect(0, 10, 5, 5)))
    }
}
