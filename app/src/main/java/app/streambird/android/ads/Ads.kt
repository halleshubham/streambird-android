package app.streambird.android.ads

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Advertisements on the broadcast picture: placements, geometry and the show/hide schedule. A 1:1 port of the web studio's
 * web-app/src/studio/ads.ts (the spec lives in its header and in the backend repo's .claude/docs/studio.md). Pure Kotlin, JVM-tested.
 *
 * W x H = the output frame in pixels (portrait or landscape alike), origin top-left:
 *   lower_third  x=0, y=H-tickerBar-round(H/3), w=W, h=round(H/3)      (moves UP by the ticker bar height while the ticker shows)
 *   side_third   x=0, y=0, w=round(W/3), h=H                           (the LEFT third)
 *   full_screen  x=0, y=0, w=W, h=H
 * The picture is scaled to fit INSIDE its area (contain, no crop, up or down); lower_third bottom-centre, the others centred.
 * Schedule (seconds since going live): continuous = always; interval {showSec, everySec}: cycle = everySec + showSec, t = elapsed mod cycle,
 * hidden while t < everySec, then shown for showSec with 300 ms fades (f = min(0.3, showSec/2)). full_screen cross-fades the other two away.
 * Draw order: camera, logo, ticker, then lower_third, side_third, full_screen, then the Free "Powered by StreamBird" mark LAST.
 */
enum class AdPlacement(val wire: String, val label: String) {
    LOWER_THIRD("lower_third", "Lower third"),
    SIDE_THIRD("side_third", "Left third"),
    FULL_SCREEN("full_screen", "Full screen");

    companion object {
        fun fromWire(s: String?): AdPlacement? = entries.firstOrNull { it.wire == s }
    }
}

/** Draw order, first = bottom. */
val AD_DRAW_ORDER: List<AdPlacement> = AdPlacement.entries

const val AD_FADE_SEC = 0.3
const val AD_SHOW_SEC_MIN = 1
const val AD_SHOW_SEC_MAX = 3600
const val AD_EVERY_SEC_MIN = 1
const val AD_EVERY_SEC_MAX = 86400

sealed class AdSchedule {
    object Continuous : AdSchedule() { override fun toString() = "Continuous" }
    data class Interval(val showSec: Int, val everySec: Int) : AdSchedule()
}

/** The StreamBird banner every Free stream carries in the lower third: shown 15 s, hidden 180 s (hidden first). */
val FREE_PLAN_AD_SCHEDULE = AdSchedule.Interval(showSec = 15, everySec = 180)

data class AdRect(val x: Double, val y: Double, val w: Double, val h: Double)

/** The area a placement occupies on a W x H frame; [tickerBarHeight] is 0 when no ticker is showing. */
fun adRect(placement: AdPlacement, w: Int, h: Int, tickerBarHeight: Int = 0): AdRect = when (placement) {
    AdPlacement.LOWER_THIRD -> {
        val th = Math.round(h / 3.0).toInt()
        AdRect(0.0, (h - max(0, tickerBarHeight) - th).toDouble(), w.toDouble(), th.toDouble())
    }
    AdPlacement.SIDE_THIRD -> AdRect(0.0, 0.0, Math.round(w / 3.0).toDouble(), h.toDouble())
    AdPlacement.FULL_SCREEN -> AdRect(0.0, 0.0, w.toDouble(), h.toDouble())
}

/** Where a picture of imgW x imgH is drawn inside [area]: contain, bottom-centre for lower_third, centred otherwise. Zero size for broken input. */
fun fitAd(placement: AdPlacement, imgW: Int, imgH: Int, area: AdRect): AdRect {
    if (imgW <= 0 || imgH <= 0 || area.w <= 0 || area.h <= 0) return AdRect(area.x, area.y, 0.0, 0.0)
    val scale = min(area.w / imgW, area.h / imgH)
    val w = imgW * scale
    val h = imgH * scale
    val x = area.x + (area.w - w) / 2
    val y = if (placement == AdPlacement.LOWER_THIRD) area.y + area.h - h else area.y + (area.h - h) / 2
    return AdRect(x, y, w, h)
}

data class AdVisibility(val visible: Boolean, val alpha: Double)

private fun clamp01(n: Double) = if (n < 0) 0.0 else if (n > 1) 1.0 else n

/** The schedule's opacity at [elapsedSec]; -1 = outside the shown window. Allocation-free (called every frame). */
fun scheduleAlpha(elapsedSec: Double, schedule: AdSchedule): Double {
    if (schedule !is AdSchedule.Interval) return 1.0
    val show = schedule.showSec; val every = schedule.everySec
    if (show <= 0 || every <= 0) return -1.0
    val cycle = (every + show).toDouble()
    val e = if (elapsedSec.isFinite() && elapsedSec > 0) elapsedSec else 0.0
    val t = e % cycle
    if (t < every) return -1.0
    val u = t - every
    val fade = min(AD_FADE_SEC, show / 2.0)
    return clamp01(min(u / fade, (show - u) / fade))
}

fun adVisibility(elapsedSec: Double, schedule: AdSchedule): AdVisibility {
    val a = scheduleAlpha(elapsedSec, schedule)
    return if (a < 0) AdVisibility(false, 0.0) else AdVisibility(true, a)
}

/** What the host did by hand: null = follow the schedule. Never saved. */
enum class AdManual { SHOWN, HIDDEN }

class AdSlot(var placement: AdPlacement, var enabled: Boolean, var schedule: AdSchedule, var manual: AdManual? = null)

/** Opacity wanted for one ad right now, before priority: not enabled = 0, then manual, then the schedule. */
fun adTargetAlpha(elapsedSec: Double, slot: AdSlot): Double {
    if (!slot.enabled) return 0.0
    if (slot.manual == AdManual.SHOWN) return 1.0
    if (slot.manual == AdManual.HIDDEN) return 0.0
    return max(0.0, scheduleAlpha(elapsedSec, slot.schedule))
}

/** Target opacity per placement (index = ordinal) after priority: full_screen fades the other two away while it is visible. Writes into [out] (size 3). */
fun resolveAdAlphas(elapsedSec: Double, slots: List<AdSlot>, out: DoubleArray = DoubleArray(3)): DoubleArray {
    out.fill(0.0)
    for (s in slots) out[s.placement.ordinal] = adTargetAlpha(elapsedSec, s)
    val cover = out[AdPlacement.FULL_SCREEN.ordinal]
    out[AdPlacement.LOWER_THIRD.ordinal] *= 1 - cover
    out[AdPlacement.SIDE_THIRD.ordinal] *= 1 - cover
    return out
}

/** Moves [current] toward [target] by at most dtSec / 0.3 s: manual Show/Hide fades like the schedule does. */
fun stepAlpha(current: Double, target: Double, dtSec: Double): Double {
    val step = max(0.0, dtSec) / AD_FADE_SEC
    return if (target > current) min(target, current + step) else max(target, current - step)
}

// ---- schedule input helpers (the editor's "Show for [n][sec|min] then hide for [n][sec|min]") ----

enum class SecUnit(val label: String, val factor: Int) { SEC("sec", 1), MIN("min", 60) }
data class UnitValue(val value: Int, val unit: SecUnit)

/** Whole minutes show as minutes. */
fun splitSeconds(sec: Int): UnitValue = if (sec >= 60 && sec % 60 == 0) UnitValue(sec / 60, SecUnit.MIN) else UnitValue(sec, SecUnit.SEC)

fun toSeconds(value: Double, unit: SecUnit): Int = (value * unit.factor).roundToInt()

fun describeSchedule(s: AdSchedule): String {
    if (s !is AdSchedule.Interval) return "Always on"
    fun fmt(sec: Int) = splitSeconds(sec).let { "${it.value} ${it.unit.label}" }
    return "Shown for ${fmt(s.showSec)}, then hidden for ${fmt(s.everySec)}"
}

/** Same ranges and wording as the web (ads.ts scheduleError); null = valid. */
fun scheduleError(s: AdSchedule): String? {
    if (s !is AdSchedule.Interval) return null
    if (s.showSec < AD_SHOW_SEC_MIN || s.showSec > AD_SHOW_SEC_MAX) return "Show time must be between 1 second and 60 minutes."
    if (s.everySec < AD_EVERY_SEC_MIN || s.everySec > AD_EVERY_SEC_MAX) return "Time between showings must be between 1 second and 24 hours."
    return null
}

/** Seconds until the ad's next showing starts (0 when it is showing / continuous). Used to decode a GIF just before it is needed. */
fun secondsUntilVisible(elapsedSec: Double, schedule: AdSchedule): Double {
    if (schedule !is AdSchedule.Interval) return 0.0
    val show = schedule.showSec; val every = schedule.everySec
    if (show <= 0 || every <= 0) return Double.MAX_VALUE
    val e = if (elapsedSec.isFinite() && elapsedSec > 0) elapsedSec else 0.0
    val t = e % (every + show).toDouble()
    return if (t < every) every - t else 0.0
}
