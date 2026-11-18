package app.streambird.android.clock

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A rectangle in frame pixels, ORIGIN TOP-LEFT (the compositor flips y for GL). */
data class ClockRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    fun intersects(o: ClockRect) = x < o.right && o.x < right && y < o.bottom && o.y < bottom
}

/** Pure geometry of the date-and-time overlay (a pill with white text). All JVM-tested; the GL code only draws what this says. */
object ClockLayout {
    /** The logo's margin (FrameCompositor.MARGIN, web drawLogo): the clock keeps the same safe margin to the frame edge. */
    const val MARGIN = 16

    /** Gap between the clock and the logo when it is moved clear of it. */
    const val STACK_GAP = 8

    /** Pill fill: black at 65 percent, like the ticker bar. */
    const val PILL_ARGB = 0xA6000000.toInt()

    /** Font height in pixels: a share of the CANVAS HEIGHT (small / medium / large = 2.2 / 3 / 4.2 percent), at least 8 px so it stays legible on a tiny canvas. */
    fun fontPx(canvasH: Int, size: ClockSize): Float = max(8.0, canvasH * size.heightShare).toFloat()

    /** Pill padding around the text: 0.55 em sideways, 0.3 em up and down. */
    fun padX(fontPx: Float) = (fontPx * 0.55f).roundToInt()
    fun padY(fontPx: Float) = (fontPx * 0.3f).roundToInt()

    /** Size of the pill for a text of [textW] x [textH] px. */
    fun pillSize(textW: Int, textH: Int, fontPx: Float): Pair<Int, Int> = (textW + 2 * padX(fontPx)) to (textH + 2 * padY(fontPx))

    /**
     * Where the pill goes. [position] corner with [MARGIN] to the frame edge; at the bottom it sits above the ticker bar ([tickerBarHeight] px, 0 = no
     * ticker). When it would overlap the logo ([logoRect], null = no logo) it is moved clear of it: a top clock goes just below the logo, a bottom one
     * just above it. The result is always inside the canvas; on a canvas too small for the pill the pill is cut down to the canvas.
     */
    fun clockRect(
        canvasW: Int, canvasH: Int, position: ClockPosition, size: ClockSize,
        logoRect: ClockRect?, tickerBarHeight: Int, textW: Int, textH: Int,
        style: ClockStyle = ClockStyle.PILL, poweredRect: ClockRect? = null,
    ): ClockRect {
        if (style == ClockStyle.TEXT) return textRect(canvasW, canvasH, position, logoRect, tickerBarHeight, textW, textH, poweredRect)
        val font = fontPx(canvasH, size)
        val (pw, ph) = pillSize(max(0, textW), max(0, textH), font)
        val w = min(max(1, pw), max(1, canvasW))
        val h = min(max(1, ph), max(1, canvasH))
        val bar = max(0, tickerBarHeight)
        val x = if (position.isLeft) MARGIN else canvasW - MARGIN - w
        var y = if (position.isTop) MARGIN else canvasH - bar - MARGIN - h
        if (logoRect != null && ClockRect(x.coerceIn(0, max(0, canvasW - w)), y, w, h).intersects(logoRect)) {
            y = if (position.isTop) logoRect.bottom + STACK_GAP else logoRect.y - STACK_GAP - h
        }
        // Inside the canvas; a bottom clock also stays above the ticker bar when the canvas leaves room for both.
        val maxY = max(0, canvasH - h - if (position.isTop) 0 else bar)
        return ClockRect(x.coerceIn(0, max(0, canvasW - w)), y.coerceIn(0, maxY), w, h)
    }

    /** Font height of the compact TEXT style: a share of the SHORT side of the canvas (small / medium / large = 2.0 / 2.8 / 3.8 percent), at least 8 px. */
    fun textFontPx(canvasW: Int, canvasH: Int, size: ClockSize): Float = max(8.0, min(canvasW, canvasH) * size.textShare).toFloat()

    /** Gap between the logo and the text under it: 0.6 x the text height. */
    fun textGap(textH: Int): Int = (textH * 0.6).roundToInt()

    /**
     * The compact TEXT style, no box. [textW] x [textH] is the text bitmap (shadow room included).
     *  - Same corner as the logo, or any top corner the logo would touch (a frame-wide logo): DIRECTLY UNDER the logo, horizontally centred on the logo's centre,
     *    [textGap] below it, then clamped into the safe margins.
     *  - Another corner: aligned to the corner (right-aligned in the right ones) with [MARGIN]. Bottom corners stay above the ticker bar and, when given,
     *    above the Free-plan "Powered by" mark ([poweredRect]) they would touch.
     * A bottom text that would still touch the logo (tiny canvas) goes just above it. Always inside the canvas.
     */
    private fun textRect(canvasW: Int, canvasH: Int, position: ClockPosition, logoRect: ClockRect?, tickerBarHeight: Int, textW: Int, textH: Int, poweredRect: ClockRect?): ClockRect {
        val w = min(max(1, textW), max(1, canvasW))
        val h = min(max(1, textH), max(1, canvasH))
        val bar = max(0, tickerBarHeight)
        val cornerX = if (position.isLeft) MARGIN else canvasW - MARGIN - w
        var x = cornerX
        var y = if (position.isTop) MARGIN else canvasH - bar - MARGIN - h
        val maxX = max(0, canvasW - w)
        if (logoRect != null && position.isTop) {
            val logoLeft = logoRect.x + logoRect.w / 2 < canvasW / 2 // the logo's side (a frame-wide logo is centred: either corner clashes with it)
            val sameCorner = logoLeft == position.isLeft
            if (sameCorner || ClockRect(cornerX.coerceIn(0, maxX), y, w, h).intersects(logoRect)) {
                x = logoRect.x + (logoRect.w - w) / 2
                y = logoRect.bottom + textGap(h)
            }
        }
        if (poweredRect != null && !position.isTop && ClockRect(x.coerceIn(0, maxX), y, w, h).intersects(poweredRect)) y = poweredRect.y - STACK_GAP - h
        if (logoRect != null && !position.isTop && ClockRect(x.coerceIn(0, maxX), y, w, h).intersects(logoRect)) y = logoRect.y - STACK_GAP - h
        val loX = if (canvasW >= w + 2 * MARGIN) MARGIN else 0
        val hiX = if (canvasW >= w + 2 * MARGIN) canvasW - MARGIN - w else maxX
        val maxY = max(0, canvasH - h - if (position.isTop) 0 else bar)
        val hiY = if (position.isTop && canvasH >= h + 2 * MARGIN) canvasH - MARGIN - h else maxY
        return ClockRect(x.coerceIn(loX, max(loX, hiX)), y.coerceIn(0, max(0, hiY)), w, h)
    }

    /** The logo's rectangle exactly as FrameCompositor.drawLogo places it (height clamped 8..canvas, width clamped to the frame minus two margins, [MARGIN] from the top). */
    fun logoRect(canvasW: Int, canvasH: Int, bitmapW: Int, bitmapH: Int, heightPx: Int, onRight: Boolean): ClockRect {
        val h = heightPx.coerceIn(8, canvasH)
        val w = (bitmapW.toFloat() / max(1, bitmapH) * h).toInt().coerceAtMost(canvasW - 2 * MARGIN)
        return ClockRect(if (onRight) canvasW - MARGIN - w else MARGIN, MARGIN, w, h)
    }
}
