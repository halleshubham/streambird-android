package app.streambird.android.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Window detection of L-shaped frame ads: same rules and vectors as the web's adWindow.ts, on synthetic pixel arrays. */
class AdWindowTest {
    private val BLUE = 0xFF1E40AF.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val TRANSPARENT = 0x00000000
    private fun near(e: Double, a: Double, eps: Double = 1e-9) = assertTrue("expected $e but was $a", abs(e - a) < eps)

    /** A w x h picture filled with [bg], then each rect (x0, y0, x1, y1 exclusive, colour) painted in order. */
    private fun picture(w: Int, h: Int, bg: Int, vararg rects: Pair<IntArray, Int>): PixelImage {
        val px = IntArray(w * h) { bg }
        for ((r, c) in rects) for (y in r[1] until r[3]) for (x in r[0] until r[2]) px[y * w + x] = c
        return PixelImage(w, h, px)
    }
    private fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y1)

    /**
     * THE SAMPLE REAL-WORLD AD: 1280 x 720, a coloured column at the left (20 % of the width = 256 px) and a coloured band at the bottom (20 % of the
     * height = 144 px), and a plain white window x 256..1280, y 0..576 (16:9).
     */
    private fun sampleAd(): PixelImage {
        val w = 1280; val h = 720
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (x < 256 || y >= 576) BLUE else WHITE
        return PixelImage(w, h, px)
    }

    @Test fun `clear pixel - transparent, or opaque near white`() {
        assertTrue(isClearPixel(0, 0, 0, 0)); assertTrue(isClearPixel(30, 200, 90, 15))           // alpha < 16
        assertFalse(isClearPixel(0, 0, 0, 16))                                                   // opaque-ish dark
        assertTrue(isClearPixel(255, 255, 255, 255)); assertTrue(isClearPixel(246, 246, 246, 200))
        assertFalse(isClearPixel(245, 255, 255, 255)); assertFalse(isClearPixel(255, 255, 255, 199)) // just under the thresholds
        assertFalse(isClearPixel(255, 255, 200, 255))                                            // cream is not white
    }

    @Test fun `the sample real-world ad - window x 256-1280, y 0-576`() {
        val w = detectWindow(sampleAd())!!
        near(256.0 / 1280, w.x); near(0.0, w.y); near(1024.0 / 1280, w.w); near(576.0 / 720, w.h)
        near(0.2, w.x); near(0.8, w.w); near(0.8, w.h)
        // on a 1280 x 720 canvas the window is 1024 x 576 (16:9): the scene (1280 x 720) fits it exactly at 0.8
        val onCanvas = windowOnCanvas(AdRect(0.0, 0.0, 1280.0, 720.0), w)
        val scene = lShapeSceneRect(1280, 720, onCanvas)
        near(256.0, scene.x); near(0.0, scene.y); near(1024.0, scene.w); near(576.0, scene.h)
    }

    @Test fun `the sample ad with a different grid size and at other resolutions finds the same window`() {
        val ref = detectWindow(sampleAd())!!
        for (cell in listOf(1, 2, 3, 5, 8)) {
            val w = detectWindow(sampleAd(), cell)!!
            near(ref.x, w.x, 0.01); near(ref.y, w.y, 0.01); near(ref.w, w.w, 0.01); near(ref.h, w.h, 0.01)
        }
        // 1920 x 1080 and a vertical 1080 x 1920 version of the same idea
        for ((pw, ph) in listOf(1920 to 1080)) {
            val img = picture(pw, ph, WHITE, rect(0, 0, pw / 5, ph) to BLUE, rect(0, ph * 4 / 5, pw, ph) to BLUE)
            val w = detectWindow(img)!!
            near(0.2, w.x, 0.01); near(0.0, w.y, 0.01); near(0.8, w.w, 0.01); near(0.8, w.h, 0.01)
        }
        val tall = picture(1080, 1920, WHITE, rect(0, 0, 1080, 288) to BLUE, rect(0, 1632, 1080, 1920) to BLUE)  // bands top and bottom
        val tw = detectWindow(tall)!!
        near(0.0, tw.x, 0.01); near(0.15, tw.y, 0.01); near(1.0, tw.w, 0.01); near(0.7, tw.h, 0.01)
    }

    @Test fun `a transparent window works like a white one, including partial alpha`() {
        val img = picture(800, 450, BLUE, rect(160, 0, 800, 360) to TRANSPARENT)
        val w = detectWindow(img)!!
        near(0.2, w.x, 0.005); near(0.0, w.y, 0.005); near(0.8, w.w, 0.005); near(0.8, w.h, 0.005)
        // alpha 10 (< 16) counts as transparent whatever the colour underneath
        val faint = picture(800, 450, BLUE, rect(160, 0, 800, 360) to 0x0A112233)
        assertNotNull(detectWindow(faint))
    }

    @Test fun `a frame on all four sides - the window is the middle`() {
        val img = picture(1000, 600, BLUE, rect(100, 60, 900, 540) to WHITE)
        val w = detectWindow(img)!!
        near(0.1, w.x, 0.005); near(0.1, w.y, 0.005); near(0.8, w.w, 0.005); near(0.8, w.h, 0.005)
    }

    @Test fun `text or a logo in the middle of the window does not hide it - the seed moves to a clear cell`() {
        val img = picture(1000, 600, BLUE, rect(100, 60, 900, 540) to WHITE, rect(470, 280, 530, 320) to BLUE)
        val w = detectWindow(img)!!
        near(0.1, w.x, 0.005); near(0.8, w.w, 0.005)
        // a mostly-filled centre with a small clear window nearby is outside the quarter-of-the-short-side search
        val far = picture(1000, 600, BLUE, rect(0, 0, 150, 150) to WHITE)
        assertNull(detectWindow(far))
    }

    @Test fun `no clear window - null`() {
        assertNull(detectWindow(picture(1280, 720, BLUE)))                                             // fully opaque colour
        assertNull(detectWindow(picture(1280, 720, 0xFFF0F0F0.toInt())))                                // light grey is not white
        assertNull(detectWindow(PixelImage(4, 4, IntArray(16))))                                       // too small to judge
        assertNull(detectWindow(PixelImage(100, 100, IntArray(10))))                                   // pixel array shorter than the size says
    }

    @Test fun `a picture that is entirely clear has no frame - null`() {
        assertNull(detectWindow(picture(1280, 720, WHITE)))
        assertNull(detectWindow(picture(1280, 720, TRANSPARENT)))
        // a hairline border still counts as "(nearly) the whole picture" on both axes (> 98 %)
        assertNull(detectWindow(picture(1000, 600, BLUE, rect(5, 5, 995, 595) to WHITE)))
    }

    @Test fun `too small a window is refused - under 20 percent of an axis or under 25 percent of the area`() {
        // 15 % wide window
        assertNull(detectWindow(picture(1000, 600, BLUE, rect(400, 100, 550, 500) to WHITE)))
        // 90 % x 25 % = 22.5 % of the area (each axis is fine) is refused, 90 % x 30 % = 27 % passes
        assertNull(detectWindow(picture(1000, 600, BLUE, rect(50, 200, 950, 350) to WHITE)))
        assertNotNull(detectWindow(picture(1000, 600, BLUE, rect(50, 200, 950, 380) to WHITE)))
    }

    @Test fun `an L-shaped clear area is not a window - the region must fill 70 percent of its box`() {
        // clear L: a full-height column plus a full-width band; the box is the whole picture but only ~36 % of it is clear
        val l = picture(1000, 600, BLUE, rect(0, 0, 300, 600) to WHITE, rect(0, 400, 1000, 600) to WHITE)
        assertNull(detectWindow(l))
    }

    @Test fun `only the region connected to the centre counts`() {
        // a window in the middle and a separate clear strip at the far edge that must not stretch the box
        val img = picture(1000, 600, BLUE, rect(100, 60, 700, 540) to WHITE, rect(900, 60, 980, 540) to WHITE)
        val w = detectWindow(img)!!
        near(0.1, w.x, 0.005); near(0.6, w.w, 0.005)
    }

    @Test fun `huge bitmaps use bigger cells so the work stays bounded`() {
        // 4000 x 2250: cell = ceil(4000 / 640) = 7 px, at most 640 x 321 cells
        val w = 4000; val h = 2250
        val px = IntArray(w * h) { i -> if (i % w < 800 || i / w >= 1800) BLUE else WHITE }
        val r = detectWindow(PixelImage(w, h, px))!!
        near(0.2, r.x, 0.01); near(0.0, r.y, 0.01); near(0.8, r.w, 0.01); near(0.8, r.h, 0.01)
    }

    @Test fun `result is normalised to 0 to 1`() {
        val r = detectWindow(sampleAd())!!
        assertTrue(r.x >= 0 && r.y >= 0 && r.w > 0 && r.h > 0 && r.x + r.w <= 1.0 + 1e-9 && r.y + r.h <= 1.0 + 1e-9)
    }

    @Test fun `editor texts`() {
        assertNull(frameWindowText(FrameWindowState.Checking))
        assertTrue(frameWindowText(FrameWindowState.NoWindow)!!.startsWith("No clear window found in this graphic"))
        assertTrue(frameWindowText(FrameWindowState.Unreadable("HTTP 500"))!!.contains("HTTP 500"))
        assertEquals("Window found: 80% x 80% of the picture. The live picture is placed inside it.", frameWindowText(FrameWindowState.Found(NormRect(0.2, 0.0, 0.8, 0.8))))
        assertTrue(FRAME_NEEDS_SHRINK.startsWith("L-shaped ads always resize the picture"))
    }
}
