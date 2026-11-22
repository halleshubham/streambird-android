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
fun adSwitchedOffText(p: AdPlacement, hasOnSwitch: Boolean = true, adNumber: Int = 1): String {
    val name = adLabel(p, adNumber)
    return if (hasOnSwitch) "$name is switched off: tick On to use it"
    else "$name is switched off: it cannot be switched on during the stream. Tick On in the kit or on the Setup screen before going live."
}

/** "Lower third" for the first ad of a placement (as before sequences), "Lower third ad 2" for the others. */
fun adLabel(p: AdPlacement, adNumber: Int = 1): String = if (adNumber <= 1) p.label else "${p.label} ad $adNumber"

/** TalkBack name of one ad of a placement ("Lower third ad 2"), also for the first ad and lone ads. */
fun adTalkBackLabel(p: AdPlacement, adNumber: Int): String = "${p.label} ad $adNumber"

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
fun switchedOffPlacements(ads: List<Pair<AdPlacement, Boolean>>): List<AdPlacement> =
    ads.map { it.first }.distinct().filter { p -> ads.none { it.first == p && it.second } }.sortedBy { it.ordinal }   // a placement counts only when EVERY ad of it is off

/** The kit editor's slots: a slot with a picture and EVERY ad of it switched off. */
fun slotsSwitchedOff(slots: List<AdEditSlot>): List<AdPlacement> = slots.filter { s -> s.assetId != null && s.ads.none { it.enabled } }.map { it.placement }

/** Number of switched-off ads on a saved kit (both shapes); pass every ad of each set (first ads + further ads, see [allAds]). */
fun kitAdsSwitchedOffCount(landscape: List<app.streambird.android.data.KitAd>, portrait: List<app.streambird.android.data.KitAd>): Int =
    (landscape + portrait).count { !it.enabled }

/** Every ad of a kit: landscape (first ads + `adsMore`) and portrait, for [kitAdsSwitchedOffCount]. */
fun app.streambird.android.data.BrandKit.allAds(): Pair<List<app.streambird.android.data.KitAd>, List<app.streambird.android.data.KitAd>> = (ads + adsMore) to (adsPortrait + adsMorePortrait)

// ---- Live: the sequence of a placement in words ----

/**
 * "Now showing 2 of 3" while one ad of the sequence is on the picture, "Up next: 2 of 3" while the placement is in its hidden part (interval) or hidden by the
 * host. [position] = 1-based place of the current ad in the whole list of [total]; null when fewer than two ads can play.
 */
fun sequencePositionText(position: Int, total: Int, showing: Boolean): String? =
    if (total < 2 || position < 1 || position > total) null else if (showing) "Now showing $position of $total" else "Up next: $position of $total"

/**
 * One line for a placement with SEVERAL ads (a single ad is described by [adStatusText] exactly as before). [ads] = the placement's ads in play order, each as the
 * scheduler sees them ([SeqAd.enabled] = on AND loaded), [manual] the placement's manual state, [schedule] its mode and pause. What wins: nothing on, manual, the schedule.
 */
fun sequenceStatusText(elapsedSec: Double, ads: List<SeqAd>, manual: AdManual?, schedule: SeqSchedule): String {
    val st = adSequenceState(elapsedSec, ads, schedule, ads.size, SeqState())
    if (st.index < 0) return "Switched off for this stream"
    val pos = st.index + 1; val total = ads.size
    when (manual) {
        AdManual.SHOWN -> return "Shown by you (${sequencePositionText(pos, total, true)}). Pick Follow schedule to go back."
        AdManual.HIDDEN -> return "Hidden by you. Pick Follow schedule to go back."
        null -> {}
    }
    if (schedule !is SeqSchedule.Interval) return "${sequencePositionText(pos, total, true)}. Next ad in ${formatCountdown(st.secondsLeft.toLong())}"
    return if (st.visible) "${sequencePositionText(pos, total, true)} (ends in ${formatDuration(max(1, st.secondsLeft))})"
    else "${sequencePositionText(pos, total, false)}. Shows in ${formatCountdown(st.secondsLeft.toLong())} for ${formatDuration(ads[st.index].showSec)}"
}

/** The chip on a kit list card: "1 ad switched off", "2 ads switched off"; null when none. */
fun adsSwitchedOffChipText(n: Int): String? = when { n <= 0 -> null; n == 1 -> "1 ad switched off"; else -> "$n ads switched off" }
