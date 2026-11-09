package app.streambird.android.ads

import kotlin.math.ceil
import kotlin.math.max

/**
 * Plain-words state of one ad for the Setup rows and the Live ads sheet ("From the brand kit. Next shows in 2:41 for 15 s").
 * Pure Kotlin, JVM-tested. The schedule maths is NOT redone here: it reads the same cycle the compositor uses (hidden first,
 * then shown; cycle = everySec + showSec; see [scheduleAlpha]).
 */

/** Said next to the countdowns: the schedule clock is the go-live clock, not the Setup screen's. */
const val AD_CLOCK_NOTE = "Schedules run from the moment the stream goes live, so an ad set to \"show, then hide\" starts hidden."

/** "2:41", "0:09", "1:02:05". */
fun formatCountdown(totalSec: Long): String {
    val s = max(0L, totalSec)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    val ss = sec.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}

/** "15 s", "3 min", "1 min 30 s", "2 h". */
fun formatDuration(sec: Int): String {
    if (sec < 60) return "$sec s"
    if (sec % 3600 == 0) return "${sec / 3600} h"
    if (sec < 3600) return if (sec % 60 == 0) "${sec / 60} min" else "${sec / 60} min ${sec % 60} s"
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (m == 0) "$h h" else "$h h $m min"
}

fun adSourcePrefix(source: AdSource): String = when (source) {
    AdSource.KIT -> "From the brand kit."
    AdSource.PHONE -> "Picture from this phone (this stream only)."
}

/**
 * One line for one ad. [elapsedSec] = seconds since the stream went live; null = not live yet (Setup): the schedule is described as it will run.
 * [slot] carries enabled / schedule / manual state. Order of what wins: Free plan, a picture that could not be used ([problem]), switched off,
 * the L-shaped frame without "Shrink picture while ads show", a manual Show / Hide, then the schedule.
 * [hasWindow] false = an L-shaped frame graphic with no clear window (null = unknown / not a frame).
 */
fun adStatusText(
    elapsedSec: Double?,
    slot: AdSlot,
    source: AdSource,
    shrinkOn: Boolean = true,
    freePlan: Boolean = false,
    hasWindow: Boolean? = null,
    problem: String? = null,
): String {
    if (freePlan) return "Not included in this account's plan. The StreamBird banner shows instead."
    val prefix = adSourcePrefix(source)
    if (problem != null) return "$prefix Not on the picture: $problem"
    if (!slot.enabled) return "$prefix Off"
    val isFrame = slot.placement == AdPlacement.L_SHAPE
    if (isFrame && !shrinkOn) return "$prefix Needs Shrink picture while ads show"
    val noWindow = if (isFrame && hasWindow == false) " No clear window in this graphic, so it covers the picture instead of framing it." else ""
    if (elapsedSec != null) {
        when (slot.manual) {
            AdManual.SHOWN -> return "$prefix Shown by you. Pick Follow schedule to go back.$noWindow"
            AdManual.HIDDEN -> return "$prefix Hidden by you. Pick Follow schedule to go back.$noWindow"
            null -> {}
        }
    }
    val sch = slot.schedule
    if (sch !is AdSchedule.Interval) return "$prefix Always on$noWindow"
    val show = sch.showSec; val every = sch.everySec
    if (show <= 0 || every <= 0) return "$prefix Never shows (check its schedule)"
    if (elapsedSec == null) return "$prefix Starts hidden: first shows ${formatDuration(every)} after you go live, for ${formatDuration(show)}, then repeats.$noWindow"
    val cycle = (every + show).toDouble()
    val e = if (elapsedSec.isFinite() && elapsedSec > 0) elapsedSec else 0.0
    val t = e % cycle
    return if (t < every) "$prefix Next shows in ${formatCountdown(ceil(every - t).toLong())} for ${formatDuration(show)}$noWindow"
    else "$prefix Showing now (ends in ${formatDuration(max(1.0, ceil(cycle - t)).toInt())})$noWindow"
}

/** Seconds since go-live for the Live sheet; [clockStartMs] 0 = the clock has not started (reads 0, like the compositor). */
fun adElapsedSec(nowMs: Long, clockStartMs: Long): Double = if (clockStartMs <= 0L) 0.0 else max(0.0, (nowMs - clockStartMs) / 1000.0)

/** What the compositor found out about an ad while preparing it (reported to the controller so the Live sheet can say it). */
enum class AdProblem { UNREADABLE, NO_WINDOW }
