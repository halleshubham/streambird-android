package app.streambird.android.ads

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Ad SEQUENCES: several ads of ONE placement (up to [MAX_ADS_PER_PLACEMENT]) played in turn. A 1:1 port of the web studio's
 * web-app/src/studio/ads.ts ("Ad SEQUENCES", `adSequenceState`, `adSequenceTargets`, `adPriorityFactors`) and of its test vectors
 * (test/ui/ad-sequences-pure.spec.ts), see [AdSequenceTest]. Pure Kotlin, JVM-tested, allocation-free when a [SeqState] is passed.
 *
 * Rules (elapsed = seconds since going live, negative counts as 0; only ads with a picture AND switched on take part, in list order; none = the
 * placement is off; ONE such ad = exactly the single-ad rules of Ads.kt):
 *  - interval: ad k has the cycle everySec + showSec_k; t = elapsed mod sum(cycles); the ad whose cycle holds t is the current one (in its hidden part
 *    it is the ad that shows NEXT); inside its show window the usual fade f = min(0.3, showSec_k / 2). Each time the show window comes round the next ad shows.
 *  - continuous: d_k = showSec_k (null / invalid = 20); t = elapsed mod sum(d); the ad with start_k <= t < start_k + d_k is current. At a change the new ad
 *    fades in over f = min(0.3, d_k / 2) while the previous one (cyclically) fades out (alphas sum to 1). The very start (elapsed < f) shows ad 1 at alpha 1.
 *  - manual: hidden = every ad 0; shown (interval) = the current / next ad at 1; continuous keeps rotating. Both act on the whole placement.
 *  - the placement's opacity (priority, shrink) = min(1, sum of its ads' alphas).
 */

/** Most ads one placement plays in turn (per orientation), the server's limit. */
const val MAX_ADS_PER_PLACEMENT = 5
/** The server accepts at most this many entries in `adsMore` (4 further ads x 4 placements). */
const val MAX_MORE_ADS = 16
/** Always-on sequences: seconds each ad stays before the next one (the ad's `showSec`). */
const val AD_DWELL_MIN = 5
const val AD_DWELL_MAX = 300
const val AD_DWELL_DEFAULT = 20

/** One ad of a sequence as the scheduler sees it. [showSec] = the show window (interval) or the dwell before the next ad (continuous). */
class SeqAd(@JvmField var enabled: Boolean = false, @JvmField var showSec: Int = AD_DWELL_DEFAULT)

/** The placement's schedule: the first ad's mode, plus the pause between showings for interval. */
sealed class SeqSchedule {
    object Continuous : SeqSchedule() { override fun toString() = "Continuous" }
    data class Interval(val everySec: Int) : SeqSchedule()
}

/** The schedule of a placement from its first ad's schedule. */
fun seqScheduleOf(s: AdSchedule): SeqSchedule = if (s is AdSchedule.Interval) SeqSchedule.Interval(s.everySec) else SeqSchedule.Continuous

class SeqState {
    /** The ad (index into the list given) that is showing, or in interval mode's hidden part the one that shows next; -1 = no ad is on. */
    @JvmField var index = -1
    /** That ad's target opacity 0..1 (interval: the fade formula; continuous: 1, or the fade-in share while cross-fading). */
    @JvmField var alpha = 0.0
    /** True while [index] is inside its shown window (always true in continuous mode when an ad is on). */
    @JvmField var visible = false
    /** The ad fading out during a continuous cross-fade (-1 = none) and its opacity. */
    @JvmField var from = -1
    @JvmField var fromAlpha = 0.0
    /** Whole seconds until the current ad appears / disappears (interval), until the next ad (continuous); 0 with no ad. */
    @JvmField var secondsLeft = 0

    fun reset(): SeqState { index = -1; alpha = 0.0; visible = false; from = -1; fromAlpha = 0.0; secondsLeft = 0; return this }
}

private fun clamp01(n: Double) = if (n < 0) 0.0 else if (n > 1) 1.0 else n
private fun dwellOf(showSec: Int) = if (showSec > 0) showSec else AD_DWELL_DEFAULT

private fun lastEnabled(ads: List<SeqAd>, n: Int): Int {
    for (i in n - 1 downTo 0) if (ads[i].enabled) return i
    return -1
}

/**
 * Which ad of a sequence is current at [elapsedSec], and how opaque it (and the one fading out) is. [n] = how many entries of [ads] count.
 * Pure; no allocation (the draw loop calls it every frame).
 */
fun adSequenceState(elapsedSec: Double, ads: List<SeqAd>, schedule: SeqSchedule, n: Int = ads.size, st: SeqState = SeqState()): SeqState {
    st.reset()
    var count = 0
    var only = -1
    for (i in 0 until n) if (ads[i].enabled) { count++; only = i }
    if (count == 0) return st
    val e = if (elapsedSec.isFinite() && elapsedSec > 0) elapsedSec else 0.0

    if (schedule !is SeqSchedule.Interval) {
        st.visible = true
        if (count == 1) { st.index = only; st.alpha = 1.0; return st }
        var total = 0.0
        for (i in 0 until n) if (ads[i].enabled) total += dwellOf(ads[i].showSec)
        val t = e % total
        var start = 0.0
        var prev = -1
        for (i in 0 until n) {
            if (!ads[i].enabled) continue
            val d = dwellOf(ads[i].showSec).toDouble()
            if (t < start + d || i == only) {
                val u = t - start
                val f = min(AD_FADE_SEC, d / 2)
                st.index = i
                st.secondsLeft = ceil(start + d - t).toInt()
                if (u < f && e >= f) {
                    // Cross-fade: the ad before this one (cyclically) fades out while this one fades in.
                    st.alpha = clamp01(u / f)
                    st.from = if (prev >= 0) prev else lastEnabled(ads, n)
                    st.fromAlpha = 1 - st.alpha
                } else st.alpha = 1.0
                break
            }
            start += d
            prev = i
        }
        return st
    }

    val every = schedule.everySec
    if (every <= 0) return st
    var total = 0.0
    for (i in 0 until n) if (ads[i].enabled && ads[i].showSec > 0) total += (every + ads[i].showSec).toDouble()
    if (!(total > 0)) return st
    var t = e % total
    for (i in 0 until n) {
        if (!ads[i].enabled || ads[i].showSec <= 0) continue
        val show = ads[i].showSec
        val len = (every + show).toDouble()
        if (t < len || i == only) {
            st.index = i
            if (t < every) { st.secondsLeft = ceil(every - t).toInt(); return st }
            val u = t - every
            val fade = min(AD_FADE_SEC, show / 2.0)
            st.visible = true
            st.alpha = clamp01(min(u / fade, (show - u) / fade))
            st.secondsLeft = ceil(len - t).toInt()
            return st
        }
        t -= len
    }
    return st
}

/**
 * Target opacity of every ad of a placement into [out] (indices as in [ads]): the schedule, then the manual override. 'hidden' = all 0;
 * 'shown' = the current / next ad at 1 (interval) or the running rotation (continuous). Returns the placement's opacity min(1, sum) for the priority rule.
 */
fun adSequenceTargets(elapsedSec: Double, ads: List<SeqAd>, n: Int, schedule: SeqSchedule, manual: AdManual?, out: DoubleArray, st: SeqState = SeqState()): Double {
    out.fill(0.0)
    if (manual == AdManual.HIDDEN) return 0.0
    adSequenceState(elapsedSec, ads, schedule, n, st)
    if (st.index < 0) return 0.0
    out[st.index] = if (manual == AdManual.SHOWN && schedule is SeqSchedule.Interval) 1.0 else st.alpha
    if (st.from >= 0) out[st.from] = st.fromAlpha
    val sum = out[st.index] + (if (st.from >= 0) st.fromAlpha else 0.0)
    return if (sum > 1) 1.0 else sum
}

/**
 * How much each placement is multiplied by under the priority rule: full_screen > l_shape > lower_third / side_third (each fades the ones below it away).
 * [alphas] = the placements' own opacities (index = [AdPlacement.ordinal]); [out] gets the factors.
 */
fun adPriorityFactors(alphas: DoubleArray, out: DoubleArray = DoubleArray(AdPlacement.entries.size)): DoubleArray {
    val cover = alphas[AdPlacement.FULL_SCREEN.ordinal]
    val frame = alphas[AdPlacement.L_SHAPE.ordinal] * (1 - cover)
    out[AdPlacement.FULL_SCREEN.ordinal] = 1.0
    out[AdPlacement.L_SHAPE.ordinal] = 1 - cover
    out[AdPlacement.LOWER_THIRD.ordinal] = (1 - cover) * (1 - frame)
    out[AdPlacement.SIDE_THIRD.ordinal] = (1 - cover) * (1 - frame)
    return out
}

// ---- the scene follows a sequence: blend of the showing ads' rectangles (web adLayer.ts `combined`) ----

/** One ad's drawn rectangle with its current opacity, for [combinePlacement]. [win] = the l_shape window on the canvas (null = none). */
class AdBlendIn(val alpha: Double, val rect: AdRect, val win: AdRect?)

/** A placement as the shrink rule sees it: opacity = min(1, sum of the alphas), rectangle and window = the blend of the showing ads. */
class CombinedAd(val alpha: Double, val rect: AdRect?, val win: AdRect?)

/**
 * A placement's ads as ONE for the shrink rule: opacity = min(1, sum of the alphas of the ads that count), and rect / window = the blend of the showing
 * ads' rectangles weighted by their smoothstepped alpha shares ([shrinkEase] of alpha / sum), so the scene eases from one ad's area to the next during
 * a cross-fade instead of jumping. A single showing ad returns its own alpha and rectangle untouched (identical to before sequences).
 * [windowed] = only count frames that have a window. No ad showing = alpha 0 and no rectangle.
 */
fun combinePlacement(ads: List<AdBlendIn>, windowed: Boolean): CombinedAd {
    var sum = 0.0
    var used = 0
    var one: AdBlendIn? = null
    for (a in ads) {
        if (!(a.alpha > 0) || (windowed && a.win == null)) continue
        sum += a.alpha; used++; one = a
    }
    if (used == 0 || one == null) return CombinedAd(0.0, null, null)
    if (used == 1) return CombinedAd(one.alpha, one.rect, one.win)
    var wt = 0.0
    var rx = 0.0; var ry = 0.0; var rw = 0.0; var rh = 0.0
    var wx = 0.0; var wy = 0.0; var ww = 0.0; var wh = 0.0
    for (a in ads) {
        if (!(a.alpha > 0) || (windowed && a.win == null)) continue
        val k = shrinkEase(a.alpha / sum)
        val wp = a.win ?: a.rect
        wt += k
        rx += a.rect.x * k; ry += a.rect.y * k; rw += a.rect.w * k; rh += a.rect.h * k
        wx += wp.x * k; wy += wp.y * k; ww += wp.w * k; wh += wp.h * k
    }
    val rect = if (wt > 0) AdRect(rx / wt, ry / wt, rw / wt, rh / wt) else one.rect
    val win = if (wt > 0) AdRect(wx / wt, wy / wt, ww / wt, wh / wt) else one.win
    return CombinedAd(min(1.0, sum), rect, win)
}

// ---- which pictures a placement keeps on the GPU ----

/**
 * The ads of a placement whose pictures must be on the GPU now: the current ad, the one fading out, and the one that shows next (so a
 * cross-fade or the next showing never waits for a decode). Everything else is released. [indexes] of ads that count (switched on, with a picture);
 * at most 3 of the 5, and in practice 2. Pure so the memory bound is unit-tested.
 */
fun wantedAds(st: SeqState, ads: List<SeqAd>, n: Int, schedule: SeqSchedule, out: BooleanArray) {
    out.fill(false)
    if (st.index < 0) return
    out[st.index] = true
    if (st.from >= 0) out[st.from] = true
    // The next enabled ad after the current one (cyclically).
    var i = (st.index + 1) % max(1, n)
    var guard = 0
    while (guard++ < n) {
        if (i < n && ads[i].enabled && (schedule !is SeqSchedule.Interval || ads[i].showSec > 0)) { out[i] = true; break }
        i = (i + 1) % max(1, n)
    }
}
