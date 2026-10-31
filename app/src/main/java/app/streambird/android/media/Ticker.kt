package app.streambird.android.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Ticker text sizes offered in the app, mapped onto the web's font range (BRANDING_RANGES.newsFontSize 12..120, default 18, at a 720 px short side). */
enum class TickerSize(val label: String, val webFontPx: Int) { S("S", 18), M("M", 28), L("L", 44) }

/**
 * Pure maths of the news ticker, a port of the web studio (web-app/src/studio/compose.ts `newsBarHeight`,
 * useHostStudio.ts `drawNewsline`, `setNewsText`). The web draws at absolute canvas pixels (a 720 px short side at HD);
 * the phone scales everything by `shortSide / 720` so the same proportions hold at 540p / 720p / 1080p, portrait or landscape.
 */
object Ticker {
    /** The web has no limit (a text input); the phone caps it so the text bitmap stays a sane size. */
    const val MAX_LENGTH = 200
    const val MIN_FONT = 12
    const val MAX_FONT = 120
    /** useHostStudio.ts ENTRANCE_MS */
    const val ENTRANCE_MS = 400L
    /** Web scroll speed: 0.08 px/ms on a 1280 px wide canvas = 80 px/s -> a fixed share of the frame width per second. */
    const val SPEED_PER_S_OF_WIDTH = 80.0 / 1280.0
    /** Widest text bitmap (GL_MAX_TEXTURE_SIZE is at least 4096 on every phone this runs on); wider text is squeezed horizontally. */
    const val MAX_TEXTURE_WIDTH = 4096
    /** Vanilla theme: tickerBg rgba(0,0,0,0.65), tickerFg #ffffff (compose.ts). */
    const val BAR_ALPHA = 166

    /** Collapses line breaks and runs of whitespace to single spaces (a ticker is one line), trims, caps at [MAX_LENGTH]. */
    fun sanitize(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_LENGTH).trimEnd()

    fun clampFont(px: Int) = px.coerceIn(MIN_FONT, MAX_FONT)

    /** compose.ts newsBarHeight: 36 px at the default 18 px font, otherwise 1.9 x the font size. */
    fun webBarHeight(fontPx: Int): Int = max(36, (fontPx * 1.9).roundToInt())

    fun scale(frameW: Int, frameH: Int): Float = min(frameW, frameH) / 720f

    data class Metrics(val fontPx: Float, val barPx: Int)

    fun metrics(size: TickerSize, frameW: Int, frameH: Int): Metrics {
        val s = scale(frameW, frameH)
        val web = clampFont(size.webFontPx)
        return Metrics(web * s, max(1, (webBarHeight(web) * s).roundToInt()))
    }

    fun speedPxPerS(frameW: Int): Double = frameW * SPEED_PER_S_OF_WIDTH

    /**
     * Left edge of the text at [elapsedMs] since the text was set. Web: starts at x = w, moves left, and when the text has fully left
     * the frame it restarts at w (a blank gap of one frame width between loops). Closed form of that integration, so it is
     * independent of the frame rate and loops seamlessly.
     */
    fun scrollX(elapsedMs: Long, frameW: Int, textW: Float): Float {
        val travelled = max(0L, elapsedMs) / 1000.0 * speedPxPerS(frameW)
        val period = frameW + textW.toDouble()
        return (frameW - travelled % period).toFloat()
    }

    fun easeOutCubic(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).toDouble().pow(3.0).toFloat()

    /** How far the bar is still below its resting place [msSinceAppeared] after the text first appeared (null = no entrance running). */
    fun slideOffsetPx(msSinceAppeared: Long?, barPx: Int): Float {
        if (msSinceAppeared == null) return 0f
        val t = (msSinceAppeared.toFloat() / ENTRANCE_MS).coerceIn(0f, 1f)
        return (1f - easeOutCubic(t)) * barPx
    }
}

/** What the compositor draws. [version] changes whenever the text or size changes (re-renders the bitmap texture); [startedAtMs] is SystemClock.elapsedRealtime(). */
data class TickerSpec(val text: String, val size: TickerSize, val version: Long, val startedAtMs: Long, val entranceAtMs: Long?) {
    companion object {
        /** New spec after an edit. The entrance plays only when the ticker goes from hidden to shown (web setNewsText). Empty text = hidden (null). */
        fun next(previous: TickerSpec?, rawText: String, size: TickerSize, nowMs: Long): TickerSpec? {
            val text = Ticker.sanitize(rawText)
            if (text.isEmpty()) return null
            val unchanged = previous != null && previous.text == text && previous.size == size
            if (unchanged) return previous
            val entrance = if (previous == null) nowMs else null
            return TickerSpec(text, size, (previous?.version ?: 0L) + 1, nowMs, entrance)
        }
    }
}
