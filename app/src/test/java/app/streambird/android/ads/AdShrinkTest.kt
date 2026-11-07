package app.streambird.android.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Geometry of "the picture shrinks while an ad shows": same vectors as the web's ads.ts (shrinkEase / adReserve / sceneRect / sceneRectFor). */
class AdShrinkTest {
    private fun near(e: Double, a: Double, eps: Double = 1e-6) = assertTrue("expected $e but was $a", abs(e - a) < eps)
    private fun assertRect(x: Double, y: Double, w: Double, h: Double, a: AdRect, eps: Double = 1e-3) { near(x, a.x, eps); near(y, a.y, eps); near(w, a.w, eps); near(h, a.h, eps) }
    private val full1280 = AdRect(0.0, 0.0, 1280.0, 720.0)

    @Test fun `shrinkEase is a smoothstep clamped to 0 and 1`() {
        near(0.0, shrinkEase(0.0)); near(1.0, shrinkEase(1.0)); near(0.5, shrinkEase(0.5)); near(0.15625, shrinkEase(0.25)); near(0.84375, shrinkEase(0.75))
        near(0.0, shrinkEase(-3.0)); near(1.0, shrinkEase(7.0))
        assertTrue(shrinkEase(0.1) < 0.1 && shrinkEase(0.9) > 0.9) // gentle at both ends
    }

    @Test fun `adReserve per placement`() {
        // lower third: from the top of the area (moved up by the ticker) to the bottom edge
        assertEquals(SceneInsets(0.0, 240.0), adReserve(AdPlacement.LOWER_THIRD, 1280, 720))
        assertEquals(SceneInsets(0.0, 280.0), adReserve(AdPlacement.LOWER_THIRD, 1280, 720, tickerBarHeight = 40))
        // left third: round(W/3)
        assertEquals(SceneInsets(427.0, 0.0), adReserve(AdPlacement.SIDE_THIRD, 1280, 720))
        assertEquals(SceneInsets(240.0, 0.0), adReserve(AdPlacement.SIDE_THIRD, 720, 1280))
        // full screen reserves nothing
        assertEquals(SceneInsets(0.0, 0.0), adReserve(AdPlacement.FULL_SCREEN, 1280, 720))
        // the DRAWN picture decides: a short banner inside the area reserves only up to its own top
        val banner = fitAd(AdPlacement.LOWER_THIRD, 1000, 100, adRect(AdPlacement.LOWER_THIRD, 1280, 720)) // 1280 x 128 at the bottom of the area
        assertEquals(SceneInsets(0.0, 128.0), adReserve(AdPlacement.LOWER_THIRD, 1280, 720, 0, banner))
        // a fitted rect with no height falls back to the whole area
        assertEquals(SceneInsets(0.0, 240.0), adReserve(AdPlacement.LOWER_THIRD, 1280, 720, 0, AdRect(0.0, 0.0, 0.0, 0.0)))
    }

    @Test fun `sceneRect contains and centres the scene`() {
        assertRect(0.0, 0.0, 1280.0, 720.0, sceneRect(1280, 720, SceneInsets(0.0, 0.0)))
        // bottom 240: scale 480/720, centred horizontally, at the top vertically
        assertRect(1280 / 6.0, 0.0, 1280 * 2 / 3.0, 480.0, sceneRect(1280, 720, SceneInsets(0.0, 240.0)))
        // left 427: scale 853/1280, right two thirds, centred vertically
        val s = 853.0 / 1280
        assertRect(427.0, (720 - 720 * s) / 2, 853.0, 720 * s, sceneRect(1280, 720, SceneInsets(427.0, 0.0)))
        // never larger than the canvas, even with negative insets
        assertRect(0.0, 0.0, 1280.0, 720.0, sceneRect(1280, 720, SceneInsets(-50.0, -50.0)))
        // absurd insets keep a positive size
        val tiny = sceneRect(1280, 720, SceneInsets(5000.0, 5000.0))
        assertTrue(tiny.w > 0 && tiny.h > 0)
    }

    @Test fun `sceneRectFor with nothing showing is the whole canvas`() {
        assertEquals(full1280, sceneRectFor(1280, 720, emptyMap()))
        assertEquals(full1280, sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(0.0))))
        assertEquals(full1280, sceneRectFor(1280, 720, mapOf(AdPlacement.FULL_SCREEN to AdShrinkInput(1.0)))) // full screen covers, never shrinks
    }

    @Test fun `lower third only, side third only, both`() {
        assertRect(1280 / 6.0, 0.0, 1280 * 2 / 3.0, 480.0, sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0))))
        val s = 853.0 / 1280
        assertRect(427.0, (720 - 720 * s) / 2, 853.0, 720 * s, sceneRectFor(1280, 720, mapOf(AdPlacement.SIDE_THIRD to AdShrinkInput(1.0))))
        // both: the intersection (right two thirds, above the lower third): scale = min(853/1280, 480/720) = 853/1280
        val both = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0), AdPlacement.SIDE_THIRD to AdShrinkInput(1.0)))
        assertRect(427.0, (480 - 720 * s) / 2, 853.0, 720 * s, both)
        assertTrue(both.x + both.w <= 1280.0 + 1e-9 && both.y >= 0 && both.y + both.h <= 480.0 + 1e-9)
    }

    @Test fun `progress follows the smoothstep of the opacity`() {
        // alpha 0.5 -> eased 0.5 -> bottom inset 120 -> free height 600 -> scale 600/720
        val half = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(0.5)))
        assertRect(1280 * (1 - 600 / 720.0) / 2, 0.0, 1280 * 600 / 720.0, 600.0, half)
        // alpha 0.25 -> eased 0.15625 -> inset 37.5
        val q = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(0.25)))
        assertRect(1280 * (1 - 682.5 / 720) / 2, 0.0, 1280 * 682.5 / 720, 682.5, q)
        // monotonic: more opacity never makes the picture bigger
        var last = Double.MAX_VALUE
        for (i in 0..20) {
            val r = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(i / 20.0)))
            assertTrue(r.h <= last + 1e-9); last = r.h
        }
        // alpha 1 reaches the full inset
        assertRect(1280 / 6.0, 0.0, 1280 * 2 / 3.0, 480.0, sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0))))
    }

    @Test fun `the ticker lifts the lower third and so the inset`() {
        // no fitted rect: the area is above the ticker bar (40): top 440, inset 280, free height 440
        val r = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0)), tickerBarHeight = 40)
        assertRect(1280 * (1 - 440 / 720.0) / 2, 0.0, 1280 * 440 / 720.0, 440.0, r)
        // with the drawn picture given, its own top wins (already above the ticker)
        val fitted = fitAd(AdPlacement.LOWER_THIRD, 1280, 240, adRect(AdPlacement.LOWER_THIRD, 1280, 720, 40))
        val r2 = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0, fitted)), tickerBarHeight = 40)
        assertRect(1280 * (1 - 440 / 720.0) / 2, 0.0, 1280 * 440 / 720.0, 440.0, r2)
    }

    @Test fun `portrait canvas`() {
        // 720 x 1280: lower third is round(1280/3) = 427 tall -> inset 427, free height 853, scale 853/1280
        val s = 853.0 / 1280
        assertRect(720 * (1 - s) / 2, 0.0, 720 * s, 853.0, sceneRectFor(720, 1280, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0))))
        // left third is 240 wide -> scale 480/720 = 2/3, centred vertically
        val t = 480.0 / 720
        assertRect(240.0, (1280 - 1280 * t) / 2, 480.0, 1280 * t, sceneRectFor(720, 1280, mapOf(AdPlacement.SIDE_THIRD to AdShrinkInput(1.0))))
    }

    @Test fun `scene stays inside the canvas for every size, ticker and opacity`() {
        for ((w, h) in listOf(960 to 540, 1280 to 720, 1920 to 1080, 540 to 960, 720 to 1280, 1080 to 1920)) for (bar in listOf(0, 36, 80)) for (a in listOf(0.01, 0.3, 0.7, 1.0)) {
            val r = sceneRectFor(w, h, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(a), AdPlacement.SIDE_THIRD to AdShrinkInput(a)), bar)
            assertTrue("$w x $h bar $bar a $a: $r", r.x >= -1e-9 && r.y >= -1e-9 && r.x + r.w <= w + 1e-6 && r.y + r.h <= h + 1e-6 && r.w > 0 && r.h > 0)
            // aspect ratio of the scene is kept
            near(w.toDouble() / h, r.w / r.h, 1e-9)
        }
    }

    // ---- L-shaped frame (web ads.ts lShapeSceneRect / sceneRectFor with `window`) ----

    @Test fun `L-shaped frame puts the scene into the window, aspect kept and centred`() {
        // the sample ad: window x 256..1280, y 0..576 of a 1280 x 720 picture = 16:9, so the scene fills it exactly
        assertRect(256.0, 0.0, 1024.0, 576.0, lShapeSceneRect(1280, 720, AdRect(256.0, 0.0, 1024.0, 576.0)))
        // a window of another shape: the scene is contained and centred in it (portrait canvas, tall-ish window)
        assertRect(180.0, 128.0, 360.0, 640.0, lShapeSceneRect(720, 1280, AdRect(72.0, 128.0, 576.0, 640.0)))
        // never larger than the canvas, whatever the window
        assertRect(0.0, 0.0, 1280.0, 720.0, lShapeSceneRect(1280, 720, AdRect(-100.0, -100.0, 5000.0, 5000.0)).let { AdRect(0.0, 0.0, it.w, it.h) })
        // a broken window leaves the whole canvas
        assertRect(0.0, 0.0, 1280.0, 720.0, lShapeSceneRect(1280, 720, AdRect(10.0, 10.0, 0.0, 50.0)))
    }

    @Test fun `L-shaped frame eases the scene to the window with the smoothstep of its opacity`() {
        val win = windowOnCanvas(AdRect(0.0, 0.0, 1280.0, 720.0), NormRect(0.2, 0.0, 0.8, 0.8))   // 256, 0, 1024, 576
        fun at(a: Double) = sceneRectFor(1280, 720, mapOf(AdPlacement.L_SHAPE to AdShrinkInput(a, AdRect(0.0, 0.0, 1280.0, 720.0), win)))
        assertRect(0.0, 0.0, 1280.0, 720.0, at(0.0))
        assertRect(256.0, 0.0, 1024.0, 576.0, at(1.0))
        // half opacity: smoothstep(0.5) = 0.5, halfway between the full canvas and the window
        assertRect(128.0, 0.0, 1152.0, 648.0, at(0.5))
        // a quarter: smoothstep(0.25) = 0.15625
        val k = 0.15625
        assertRect(256.0 * k, 0.0, 1280 + (1024 - 1280) * k, 720 + (576 - 720) * k, at(0.25))
    }

    @Test fun `L-shaped frame without a window does not shrink the scene`() {
        assertRect(0.0, 0.0, 1280.0, 720.0, sceneRectFor(1280, 720, mapOf(AdPlacement.L_SHAPE to AdShrinkInput(1.0, AdRect(0.0, 0.0, 1280.0, 720.0), null))))
        assertEquals(SceneInsets(0.0, 0.0), adReserve(AdPlacement.L_SHAPE, 1280, 720))
    }

    @Test fun `L-shaped frame wins over a lower third at full opacity and eases from the thirds' scene otherwise`() {
        val win = AdRect(256.0, 0.0, 1024.0, 576.0)
        val both = mapOf(
            AdPlacement.LOWER_THIRD to AdShrinkInput(1.0), AdPlacement.L_SHAPE to AdShrinkInput(1.0, AdRect(0.0, 0.0, 1280.0, 720.0), win),
        )
        assertRect(256.0, 0.0, 1024.0, 576.0, sceneRectFor(1280, 720, both))
        // the frame at 0.5 starts from where the lower third left the scene (a 480 x 480... 1280*(480/720) wide, centred) and moves halfway to the window
        val base = sceneRectFor(1280, 720, mapOf(AdPlacement.LOWER_THIRD to AdShrinkInput(1.0)))
        val half = sceneRectFor(1280, 720, both + (AdPlacement.L_SHAPE to AdShrinkInput(0.5, AdRect(0.0, 0.0, 1280.0, 720.0), win)))
        assertRect((base.x + 256.0) / 2, (base.y + 0.0) / 2, (base.w + 1024.0) / 2, (base.h + 576.0) / 2, half)
    }

    @Test fun `L-shaped scene stays inside the canvas for every size and opacity`() {
        for ((w, h) in listOf(960 to 540, 1280 to 720, 1920 to 1080, 540 to 960, 720 to 1280, 1080 to 1920)) for (a in listOf(0.01, 0.3, 0.7, 1.0)) {
            val fitted = fitAd(AdPlacement.L_SHAPE, 1920, 1080, adRect(AdPlacement.L_SHAPE, w, h))
            val win = windowOnCanvas(fitted, NormRect(0.2, 0.05, 0.75, 0.7))
            val r = sceneRectFor(w, h, mapOf(AdPlacement.L_SHAPE to AdShrinkInput(a, fitted, win)))
            assertTrue("$w x $h a $a: $r", r.x >= -1e-9 && r.y >= -1e-9 && r.x + r.w <= w + 1e-6 && r.y + r.h <= h + 1e-6 && r.w > 0 && r.h > 0)
            near(w.toDouble() / h, r.w / r.h, 1e-9)
        }
    }
}
