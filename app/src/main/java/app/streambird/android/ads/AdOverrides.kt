package app.streambird.android.ads

import app.streambird.android.data.KitAd

/**
 * Pure editing of the per-stream ad lists on the Setup screen (1.0.18 sequences): reorder, remove, add a phone picture, per-ad On switch and seconds.
 * Every function takes the kit's ads for the stream's shape ([kitAds], flat, placement by placement in play order) and the current overrides and returns the new overrides.
 * The first edit of a placement turns "as the kit says" into an explicit [AdOverride.sequence]; [resetPlacement] goes back.
 */

/** The placement's ads as they are now (kit + overrides), in play order. */
fun currentAds(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement): List<ResolvedAd> = mergeStreamAds(kitAds, overrides, freePlan = false).of(p)

/** The explicit list of entries for the ads as they are now (seconds are written out so the list means the same later, whatever the kit says). */
fun entriesFromAds(ads: List<ResolvedAd>): List<AdSeqEntry> = ads.map { r ->
    val interval = r.schedule is AdSchedule.Interval
    AdSeqEntry(
        kitAssetId = r.assetId.takeIf { r.source == AdSource.KIT }, phone = r.phone.takeIf { r.source == AdSource.PHONE }, enabled = r.enabled,
        showSec = if (interval) r.showSec else r.dwellSec,
    )
}

private fun edit(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, change: (List<AdSeqEntry>) -> List<AdSeqEntry>): Map<AdPlacement, AdOverride> {
    val cur = entriesFromAds(currentAds(kitAds, overrides, p))
    val next = change(cur).take(MAX_ADS_PER_PLACEMENT)
    val o = overrides[p] ?: AdOverride()
    val seen = kitAds.orEmpty().filter { it.placement == p.wire }.map { it.assetId }.toSet()
    return overrides + (p to o.copy(enabled = null, phone = null, sequence = next, kitSeen = seen))
}

/** Moves one ad up (-1) or down (+1) in the placement's play order. */
fun overrideMoveAd(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, index: Int, delta: Int): Map<AdPlacement, AdOverride> =
    edit(kitAds, overrides, p) { l ->
        val to = index + delta
        if (index !in l.indices || to !in l.indices) l else l.toMutableList().also { m -> val x = m.removeAt(index); m.add(to, x) }
    }

/** Takes one ad out of this stream's list (a kit ad stays out until [resetPlacement]). */
fun overrideRemoveAd(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, index: Int): Map<AdPlacement, AdOverride> =
    edit(kitAds, overrides, p) { l -> if (index !in l.indices) l else l.filterIndexed { i, _ -> i != index } }

/** Adds a picture from this phone at the end (no-op at 5 ads). */
fun overrideAddPhone(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, phone: PhoneAd): Map<AdPlacement, AdOverride> =
    if (currentAds(kitAds, overrides, p).size >= MAX_ADS_PER_PLACEMENT) overrides else edit(kitAds, overrides, p) { l -> l + AdSeqEntry(phone = phone) }

/** Replaces one ad's picture with one from this phone (its On switch and seconds stay). */
fun overrideReplaceWithPhone(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, index: Int, phone: PhoneAd): Map<AdPlacement, AdOverride> =
    edit(kitAds, overrides, p) { l -> l.mapIndexed { i, e -> if (i == index) AdSeqEntry(phone = phone, enabled = e.enabled, showSec = e.showSec) else e } }

/** The On switch of ONE ad of the placement. */
fun overrideSetOn(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, index: Int, on: Boolean): Map<AdPlacement, AdOverride> =
    edit(kitAds, overrides, p) { l -> l.mapIndexed { i, e -> if (i == index) e.copy(enabled = on) else e } }

/** One ad's seconds (interval: its show window, continuous: its dwell); null = the kit's / default. */
fun overrideSetSeconds(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, index: Int, sec: Int?): Map<AdPlacement, AdOverride> =
    edit(kitAds, overrides, p) { l -> l.mapIndexed { i, e -> if (i == index) e.copy(showSec = sec) else e } }

/**
 * The placement's schedule for this stream. With several ads a change of MODE clears the ads' own seconds (a show window is not a dwell), and in interval mode the first ad's
 * show window is the schedule's. A single ad: exactly the old single-ad override.
 */
fun overrideSetSchedule(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, p: AdPlacement, sch: AdSchedule): Map<AdPlacement, AdOverride> {
    val ads = currentAds(kitAds, overrides, p)
    val modeChanged = ads.isNotEmpty() && (ads[0].schedule is AdSchedule.Interval) != (sch is AdSchedule.Interval)
    val o = overrides[p] ?: AdOverride()
    if (ads.size < 2 && o.sequence == null) return overrides + (p to o.copy(schedule = sch))
    val next = edit(kitAds, overrides + (p to o.copy(schedule = sch)), p) { l ->
        l.mapIndexed { i, e ->
            // After a mode flip every ad starts from the default of the new mode (written out: an empty value would fall back to the kit's old number).
            if (modeChanged) e.copy(showSec = if (sch is AdSchedule.Interval) sch.showSec else AD_DWELL_DEFAULT)
            else if (i == 0 && sch is AdSchedule.Interval) e.copy(showSec = sch.showSec) else e
        }
    }
    return next
}

/** Back to what the kit says for this placement (drops the list, the switches and the schedule; a phone picture goes too). */
fun resetPlacement(overrides: Map<AdPlacement, AdOverride>, p: AdPlacement): Map<AdPlacement, AdOverride> = overrides - p

/** True when the placement's list was edited for this stream (so "Use the kit's ads" has something to undo). */
fun placementEdited(overrides: Map<AdPlacement, AdOverride>, p: AdPlacement): Boolean = overrides[p]?.let { it.sequence != null || it.phone != null } == true

/** "3 ads, played in turn" style line for the Setup card of a placement with several ads; the single-ad status line is [adStatusText]. */
fun sequenceSetupText(ads: List<ResolvedAd>): String {
    val on = ads.count { it.enabled }
    val first = ads.firstOrNull() ?: return "No ad"
    val head = "${ads.size} ads play in turn" + if (on < ads.size) " ($on switched on)" else ""
    return when (val s = first.schedule) {
        is AdSchedule.Interval -> "$head. Each shows for its own seconds, then hides for ${formatDuration(s.everySec)}, and the next ad comes round."
        else -> "$head, always on: each stays for its own seconds, then the next one fades in."
    }
}
