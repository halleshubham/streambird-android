package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TickerTest {
    @Test fun `sanitize collapses whitespace, trims and caps the length`() {
        assertEquals("Breaking: storm warning", Ticker.sanitize("  Breaking:\n\n  storm\twarning \r\n"))
        assertEquals("", Ticker.sanitize(" \n\t "))
        val long = Ticker.sanitize("a".repeat(500))
        assertEquals(Ticker.MAX_LENGTH, long.length)
        assertEquals("ab", Ticker.sanitize("ab" + " ".repeat(300) + "cd".repeat(1)).take(2))
    }

    @Test fun `web bar height rule and size mapping stay inside the web range`() {
        assertEquals(36, Ticker.webBarHeight(18))     // unchanged at the default font, like compose.ts
        assertEquals(53, Ticker.webBarHeight(28))
        assertEquals(84, Ticker.webBarHeight(44))
        TickerSize.entries.forEach { assertTrue(it.webFontPx in Ticker.MIN_FONT..Ticker.MAX_FONT) }
        assertEquals(12, Ticker.clampFont(3)); assertEquals(120, Ticker.clampFont(500))
    }

    @Test fun `metrics scale with the short side in both orientations`() {
        val land = Ticker.metrics(TickerSize.S, 1280, 720)
        val port = Ticker.metrics(TickerSize.S, 720, 1280)
        assertEquals(18f, land.fontPx, 0.001f); assertEquals(36, land.barPx)
        assertEquals(land, port)
        val hd = Ticker.metrics(TickerSize.S, 1920, 1080)       // 1.5x
        assertEquals(27f, hd.fontPx, 0.001f); assertEquals(54, hd.barPx)
        val small = Ticker.metrics(TickerSize.M, 960, 540)       // 0.75x
        assertEquals(21f, small.fontPx, 0.001f); assertEquals(40, small.barPx)
    }

    @Test fun `scroll starts at the right edge, moves 80 px per second at 1280 wide, and loops`() {
        assertEquals(1280f, Ticker.scrollX(0, 1280, 500f), 0.01f)
        assertEquals(1200f, Ticker.scrollX(1000, 1280, 500f), 0.01f)
        assertEquals(640f, Ticker.scrollX(8000, 1280, 500f), 0.01f)
        // speed is a share of the frame width: 720 wide -> 45 px/s
        assertEquals(720f - 45f, Ticker.scrollX(1000, 720, 300f), 0.01f)
        // fully left the frame (x + textW < 0) at (w + textW) / speed = 22.25 s, then it restarts at w
        val period = (1280 + 500) / 80.0 * 1000
        assertEquals(-500f, Ticker.scrollX((period - 1).toLong(), 1280, 500f), 0.2f)
        assertEquals(1280f, Ticker.scrollX(period.toLong(), 1280, 500f), 0.2f)
        assertEquals(Ticker.scrollX(3000, 1280, 500f), Ticker.scrollX(3000 + period.toLong(), 1280, 500f), 0.2f)
        // negative elapsed (clock hiccup) does not move backwards past the start
        assertEquals(1280f, Ticker.scrollX(-50, 1280, 500f), 0.01f)
    }

    @Test fun `entrance slides up over 400 ms with ease-out`() {
        assertEquals(36f, Ticker.slideOffsetPx(0, 36), 0.01f)
        assertEquals(0f, Ticker.slideOffsetPx(400, 36), 0.01f)
        assertEquals(0f, Ticker.slideOffsetPx(2000, 36), 0.01f)
        assertEquals(0f, Ticker.slideOffsetPx(null, 36), 0f)
        val mid = Ticker.slideOffsetPx(200, 36)               // easeOutCubic(0.5) = 0.875 -> 12.5% left
        assertEquals(4.5f, mid, 0.01f)
        assertTrue(Ticker.slideOffsetPx(100, 36) > mid)
    }

    @Test fun `spec entrance only when the ticker first appears and version bumps on text or size change`() {
        assertNull(TickerSpec.next(null, "   ", TickerSize.S, 10))
        val a = TickerSpec.next(null, "Hello  world", TickerSize.S, 1000)!!
        assertEquals("Hello world", a.text); assertEquals(1000L, a.entranceAtMs); assertEquals(1000L, a.startedAtMs)
        assertSame(a, TickerSpec.next(a, "Hello world", TickerSize.S, 5000))          // nothing changed: no re-render, no scroll reset
        val b = TickerSpec.next(a, "Other text", TickerSize.S, 5000)!!
        assertNull(b.entranceAtMs); assertEquals(5000L, b.startedAtMs); assertTrue(b.version > a.version)
        val c = TickerSpec.next(b, "Other text", TickerSize.L, 6000)!!
        assertNotNull(c); assertTrue(c.version > b.version)
        assertNull(TickerSpec.next(c, "", TickerSize.L, 7000))                          // hidden
        assertEquals(7500L, TickerSpec.next(null, "again", TickerSize.S, 7500)!!.entranceAtMs) // plays again after being hidden
    }
}
