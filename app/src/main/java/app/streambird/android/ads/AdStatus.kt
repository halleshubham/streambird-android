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
 * a manual Show / Hide, then the schedule. (The "Shrink picture while ads show" switch does not matter here: the L-shaped frame runs with it on or off.)
 * [hasOnSwitch] = the row has the ad's On switch next to it (Setup); the Live sheet has none, so it says where to switch it on.
 * [hasWindow] false = an L-shaped frame graphic with no clear window (null = unknown / not a frame).
 */
fun adStatusText(
    elapsedSec: Double?,
    slot: AdSlot,
    source: AdSource,
    freePlan: Boolean = false,
    hasWindow: Boolean? = null,
    problem: String? = null,
    hasOnSwitch: Boolean = true,
): String {
    if (freePlan) return "Not included in this account's plan. The StreamBird banner shows instead."
    val prefix = adSourcePrefix(source)
    if (problem != null) return "$prefix Not on the picture: $problem"
    if (!slot.enabled) return "$prefix ${if (hasOnSwitch) "Switched off: tick On to use it" else "Switched off for this stream"}"
    val isFrame = slot.placement == AdPlacement.L_SHAPE
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

// ---- Switched-off ads: said clearly where the ad is shown (Setup row, Live sheet, kit editor, go-live note) ----

/** The small chip on a row / card whose ad is switched off. */
const val AD_OFF_CHIP = "Off"

/**
 * The warning line under an ad that is present but switched off. With [hasOnSwitch] (Setup, kit editor) it says what to do: "L-shaped frame is
 * switched off: tick On to use it". The Live sheet has no On switch, so there it says where to change it instead.
 */
fun adSwitchedOffText(p: AdPlacement, hasOnSwitch: Boolean = true): String =
    if (hasOnSwitch) "${p.label} is switched off: tick On to use it"
    else "${p.label} is switched off: it cannot be switched on during the stream. Tick On in the kit or on the Setup screen before going live."

/** Name of a placement inside a sentence ("lower third", "L-shaped frame"). */
private fun adNameInSentence(p: AdPlacement): String = if (p.label.startsWith("L-")) p.label else p.label.replaceFirstChar { it.lowercase() }

/** At most three names ("a", "a and b", "a, b and c", "a, b, c and 1 more"). */
private fun joinAdNames(ps: List<AdPlacement>): String {
    val names = ps.map(::adNameInSentence)
    return when {
        names.size == 1 -> names[0]
        names.size <= 3 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        else -> names.take(3).joinToString(", ") + " and ${names.size - 3} more"
    }
}

/**
 * The note at go-live when the kit / the stream's own choices leave loaded ads switched off ([off] = their placements), or null when none.
 * "The L-shaped frame ad is switched off: open Ads and tick On to use it."
 */
fun adsSwitchedOffNote(off: List<AdPlacement>): String? {
    val list = off.distinct().sortedBy { it.ordinal }
    if (list.isEmpty()) return null
    return if (list.size == 1) "The ${adNameInSentence(list[0])} ad is switched off: open Ads and tick On to use it."
    else "The ${joinAdNames(list)} ads are switched off: open Ads and tick On to use them."
}

/** Placements of ads that exist but are switched off. [ads] = (placement, enabled) of the ads that are present. */
fun switchedOffPlacements(ads: List<Pair<AdPlacement, Boolean>>): List<AdPlacement> = ads.filter { !it.second }.map { it.first }.distinct().sortedBy { it.ordinal }

/** The kit editor's slots: a slot with a picture and its On switch off. */
fun slotsSwitchedOff(slots: List<AdEditSlot>): List<AdPlacement> = slots.filter { it.assetId != null && !it.enabled }.map { it.placement }

/** Number of switched-off ads on a saved kit (both shapes). */
fun kitAdsSwitchedOffCount(landscape: List<app.streambird.android.data.KitAd>, portrait: List<app.streambird.android.data.KitAd>): Int =
    (landscape + portrait).count { !it.enabled }

/** The chip on a kit list card: "1 ad switched off", "2 ads switched off"; null when none. */
fun adsSwitchedOffChipText(n: Int): String? = when { n <= 0 -> null; n == 1 -> "1 ad switched off"; else -> "$n ads switched off" }
