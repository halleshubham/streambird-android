package app.streambird.android.media

import app.streambird.android.clock.ClockRect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The NAME BANNER (1.0.15): a name and a small title line on the picture, drawn like a lower third (bottom left, above the ticker bar, a dark
 * semi-transparent rounded bar with a coloured edge). The text rules live here; the geometry is [NameBannerLayout]; the drawing is
 * [FrameCompositor] + [NameBannerBitmap]. Pure Kotlin, JVM-tested.
 */
object NameBanner {
    const val NAME_MAX = 80
    const val TITLE_MAX = 120
    /** Fade in / out. */
    const val FADE_MS = 250L

    /**
     * Text from the host's keyboard or from a remote command: line breaks / tabs become spaces, control characters and every "format" character
     * (bidi overrides and isolates, zero-width characters, the byte order mark) are removed, runs of spaces collapse, the result is trimmed and cut
     * to [max] characters (never in the middle of a surrogate pair). Null = empty.
     */
    fun clean(raw: String?, max: Int): String {
        if (raw == null) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000b' || ch == '\u000c' || ch == '\u0085' || ch == ' ' || ch == ' ' || ch == ' ' -> sb.append(' ')
                blocked(ch) -> {}
                else -> sb.append(ch)
            }
        }
        var s = sb.toString().replace(Regex(" {2,}"), " ").trim()
        if (s.length > max) {
            s = s.substring(0, max)
            if (s.isNotEmpty() && Character.isHighSurrogate(s.last())) s = s.dropLast(1)
            s = s.trimEnd()
        }
        return s
    }

    fun cleanName(raw: String?) = clean(raw, NAME_MAX)
    fun cleanTitle(raw: String?): String? = clean(raw, TITLE_MAX).takeIf { it.isNotEmpty() }

    private fun blocked(ch: Char): Boolean =
        Character.isISOControl(ch) || Character.getType(ch) == Character.FORMAT.toInt() || ch == '‎' || ch == '‏' ||
            ch in '‪'..'‮' || ch in '⁦'..'⁩' || ch == '؜'

    /** Moves the opacity [current] toward [target] (0 or 1) by [dtMs] of a [FADE_MS] fade. */
    fun fadeStep(current: Float, target: Float, dtMs: Long): Float {
        val step = max(0L, dtMs).toFloat() / FADE_MS
        return if (target > current) min(target, current + step) else max(target, current - step)
    }
}

/** What the host or a support session set: the texts and whether the banner is on. [name] and [title] are already cleaned. */
data class NameBannerState(val name: String = "", val title: String? = null, val show: Boolean = false) {
    /** Shown on air: switched on AND there is a name to show. */
    val visible: Boolean get() = show && name.isNotEmpty()

    /** [name] / [show] null = unchanged; [titleGiven] false = title unchanged, true = [title] replaces it (null or blank = none). Texts are cleaned here. */
    fun patched(name: String?, titleGiven: Boolean, title: String?, show: Boolean?): NameBannerState = copy(
        name = if (name != null) NameBanner.cleanName(name) else this.name,
        title = if (titleGiven) NameBanner.cleanTitle(title) else this.title,
        show = show ?: this.show,
    )
}

/** What the compositor draws: [version] changes with the text (re-renders the bitmap). [show] false = fading out / hidden. */
data class BannerSpec(val name: String, val title: String?, val show: Boolean, val version: Long) {
    val visible: Boolean get() = show && name.isNotEmpty()

    companion object {
        /** New spec after a change; unchanged texts keep the version, so the bitmap is rebuilt only when the text changes. Null = nothing to draw (no name). */
        fun next(previous: BannerSpec?, s: NameBannerState): BannerSpec? {
            if (s.name.isEmpty()) return null
            val sameText = previous != null && previous.name == s.name && previous.title == s.title
            return BannerSpec(s.name, s.title, s.show, if (sameText) previous!!.version else (previous?.version ?: 0L) + 1)
        }
    }
}

/** What to draw for one frame: the bar rectangle (origin TOP-LEFT, frame pixels) and its opacity. */
data class BannerDraw(val rect: ClockRect, val alpha: Float)

/** Pure geometry of the name banner. All numbers scale with the SHORT side of the canvas (720 = the web's reference), like the ticker. */
object NameBannerLayout {
    /** Same safe margin as the logo, the clock and the ticker. */
    const val MARGIN = 16
    const val STACK_GAP = 8
    /** Below this the banner counts as invisible and nothing is drawn. */
    const val MIN_ALPHA = 0.002f
    /** Bar fill (black, 70 percent) and the coloured edge (the StreamBird accent). */
    const val BAR_ARGB = 0xB3000000.toInt()
    const val ACCENT_ARGB = 0xFF7C3AED.toInt()

    private fun unit(w: Int, h: Int) = min(w, h) / 720f

    fun nameFontPx(w: Int, h: Int): Float = max(9f, 30f * unit(w, h))
    fun titleFontPx(w: Int, h: Int): Float = max(7f, 20f * unit(w, h))
    fun accentPx(w: Int, h: Int): Int = max(3, (6 * unit(w, h)).roundToInt())
    fun padX(w: Int, h: Int): Int = max(4, (16 * unit(w, h)).roundToInt())
    fun padY(w: Int, h: Int): Int = max(3, (10 * unit(w, h)).roundToInt())
    fun lineGap(w: Int, h: Int): Int = max(1, (3 * unit(w, h)).roundToInt())
    fun cornerPx(w: Int, h: Int): Int = max(3, (8 * unit(w, h)).roundToInt())

    /** Space between the bar and the bottom edge when no ticker is showing: more room on a vertical canvas (phone apps cover the bottom of 9:16). */
    fun bottomGap(w: Int, h: Int): Int = max(MARGIN, if (h > w) (h * 0.06).roundToInt() else (20 * unit(w, h)).roundToInt())

    /** Widest the bar may be: inside the margins, and left of the Free-plan "Powered by" mark ([powered], null = none) when there is one. At least 1. */
    fun maxBarWidth(canvasW: Int, powered: ClockRect?): Int {
        val base = max(1, canvasW - 2 * MARGIN)
        if (powered == null) return base
        return max(1, min(base, powered.x - STACK_GAP - MARGIN))
    }

    /** Size of the bar for lines of [nameW] x [nameH] and, when there is a title, [titleW] x [titleH] px (text already squeezed to fit [maxW] by the caller if needed). */
    fun barSize(canvasW: Int, canvasH: Int, nameW: Int, nameH: Int, titleW: Int?, titleH: Int?, maxW: Int): Pair<Int, Int> {
        val textW = max(nameW, titleW ?: 0)
        val w = accentPx(canvasW, canvasH) + 2 * padX(canvasW, canvasH) + textW
        val h = 2 * padY(canvasW, canvasH) + nameH + if (titleW != null && titleH != null) lineGap(canvasW, canvasH) + titleH else 0
        return min(max(1, w), max(1, min(maxW, canvasW))) to min(max(1, h), max(1, canvasH))
    }

    /**
     * Where the bar goes: bottom left, [MARGIN] from the left edge, ABOVE the ticker bar ([tickerBar] px, 0 = none; the gap below is [MARGIN] with a
     * ticker, [bottomGap] without). A date-and-time overlay in the same corner ([clock], null = none) or the "Powered by" mark ([powered]) it would
     * touch pushes it up above them. Always inside the canvas, and above the ticker bar whenever the canvas leaves room for both.
     */
    fun rect(canvasW: Int, canvasH: Int, tickerBar: Int, barW: Int, barH: Int, clock: ClockRect? = null, powered: ClockRect? = null): ClockRect {
        val w = min(max(1, barW), max(1, canvasW))
        val h = min(max(1, barH), max(1, canvasH))
        val bar = max(0, tickerBar)
        val x = (if (canvasW >= w + 2 * MARGIN) MARGIN else 0).coerceIn(0, max(0, canvasW - w))
        var y = canvasH - bar - (if (bar > 0) MARGIN else bottomGap(canvasW, canvasH)) - h
        for (o in listOfNotNull(clock, powered)) {
            if (ClockRect(x, y, w, h).intersects(o)) y = o.y - STACK_GAP - h
        }
        val maxY = max(0, canvasH - h - bar)
        return ClockRect(x, y.coerceIn(0, maxY), w, h)
    }

    /** The whole per-frame decision: null = draw nothing (faded out), else where and how opaque. */
    fun drawPlan(canvasW: Int, canvasH: Int, tickerBar: Int, barW: Int, barH: Int, alpha: Float, clock: ClockRect? = null, powered: ClockRect? = null): BannerDraw? =
        if (alpha <= MIN_ALPHA || barW <= 0 || barH <= 0) null else BannerDraw(rect(canvasW, canvasH, tickerBar, barW, barH, clock, powered), alpha.coerceAtMost(1f))
}
