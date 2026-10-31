package app.streambird.android.ads

import kotlin.math.roundToInt

/**
 * Geometry of the small "Powered by StreamBird" mark of Free-plan streams: a port of web-app/src/studio/poweredBy.ts.
 * Bottom-right corner, above the ticker bar ([reserveBottom]) and, on a vertical stream, clear of the bottom edge.
 */
data class PoweredLayout(val unit: Double, val pillW: Int, val pillH: Int, val padX: Int, val gap: Int, val fontPx: Int, val iconH: Int, val iconW: Double, val x: Int, val y: Int)

const val POWERED_BY_TEXT = "Powered by StreamBird"
/** rgba(17,14,34,0.78) */
const val POWERED_PILL_ARGB = 0xC7110E22.toInt()

fun poweredByLayout(w: Int, h: Int, textW: Double, logoAspect: Double?, reserveBottom: Int): PoweredLayout {
    val portrait = h > w
    val unit = (if (portrait) w else h) / 720.0
    val margin = (20 * unit).roundToInt()
    val pillH = (40 * unit).roundToInt()
    val padX = (12 * unit).roundToInt()
    val gap = (8 * unit).roundToInt()
    val fontPx = (18 * unit).roundToInt()
    val iconH = (28 * unit).roundToInt()
    val iconW = if (logoAspect != null && logoAspect > 0) logoAspect * iconH else 0.0
    val pillW = (padX * 2 + (if (iconW > 0) iconW + gap else 0.0) + textW).roundToInt()
    val bottomGap = if (portrait) (h * 0.06).roundToInt() else margin
    return PoweredLayout(unit, pillW, pillH, padX, gap, fontPx, iconH, iconW, w - margin - pillW, h - reserveBottom - bottomGap - pillH)
}
