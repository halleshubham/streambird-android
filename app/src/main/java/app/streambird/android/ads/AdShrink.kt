package app.streambird.android.ads

import kotlin.math.max
import kotlin.math.min

// ---- Reserving space: the picture shrinks while an ad shows (1:1 port of web-app/src/studio/ads.ts "SHRINK RULE") ----
//
// While a lower_third or side_third ad is visible, the SCENE (camera, logo) is scaled down, aspect kept, into the area the ad leaves
// free and centred there. The background stays full frame behind it. The ticker stays on the bottom edge, the Free "Powered by" mark is on top.
//   lower_third: free area = y 0 .. top of the DRAWN ad picture (fitAd result, already above the ticker)
//   side_third:  free area = the right two thirds, x round(W/3) .. W, full height
//   both: the intersection. full_screen reserves nothing.
// Progress: the ad's current opacity through [shrinkEase] (smoothstep), so the picture eases in step with the 300 ms fade.

/** Smoothstep of an ad's opacity: 0 -> 0, 1 -> 1, gentle at both ends. */
fun shrinkEase(alpha: Double): Double {
    val t = if (alpha < 0) 0.0 else if (alpha > 1) 1.0 else alpha
    return t * t * (3 - 2 * t)
}

/** [left] = pixels taken from the left edge, [bottom] = pixels taken from the bottom edge (includes the ticker bar the ad stands on). */
data class SceneInsets(val left: Double, val bottom: Double)

/** The room an ad takes at full progress. [fitted] = where the ad picture is actually drawn; without it the placement's whole area is used. */
fun adReserve(placement: AdPlacement, w: Int, h: Int, tickerBarHeight: Int = 0, fitted: AdRect? = null): SceneInsets = when (placement) {
    AdPlacement.LOWER_THIRD -> {
        val top = if (fitted != null && fitted.h > 0) fitted.y else adRect(AdPlacement.LOWER_THIRD, w, h, tickerBarHeight).y
        SceneInsets(0.0, max(0.0, h - top))
    }
    AdPlacement.SIDE_THIRD -> SceneInsets(adRect(AdPlacement.SIDE_THIRD, w, h).w, 0.0)
    AdPlacement.FULL_SCREEN -> SceneInsets(0.0, 0.0)
}

/** Contain-fits the W x H scene into what is left after the insets, centred both ways. Never larger than the canvas. */
fun sceneRect(w: Int, h: Int, insets: SceneInsets): AdRect {
    val areaX = min(max(0.0, insets.left), w - 1.0)
    val areaW = w - areaX
    val areaH = max(1.0, h - max(0.0, insets.bottom))
    val scale = min(1.0, min(areaW / w, areaH / h))
    val sw = w * scale
    val sh = h * scale
    return AdRect(areaX + (areaW - sw) / 2, (areaH - sh) / 2, sw, sh)
}

/** [alpha] = the ad's current opacity 0..1 (after priority); [fitted] = where its picture is drawn, null = the placement's whole area. */
data class AdShrinkInput(val alpha: Double, val fitted: AdRect? = null)

/** Where the scene is drawn while the given ads show (missing = not showing). The whole canvas when nothing shrinks it. */
fun sceneRectFor(w: Int, h: Int, ads: Map<AdPlacement, AdShrinkInput>, tickerBarHeight: Int = 0): AdRect {
    var left = 0.0
    var bottom = 0.0
    for (p in AdPlacement.entries) {
        val a = ads[p] ?: continue
        if (!(a.alpha > 0)) continue
        val full = adReserve(p, w, h, tickerBarHeight, a.fitted)
        val k = shrinkEase(a.alpha)
        left = max(left, full.left * k)
        bottom = max(bottom, full.bottom * k)
    }
    return if (left == 0.0 && bottom == 0.0) AdRect(0.0, 0.0, w.toDouble(), h.toDouble()) else sceneRect(w, h, SceneInsets(left, bottom))
}
