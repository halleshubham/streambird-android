package app.streambird.android.ads

import app.streambird.android.brand.BrandOrientation
import app.streambird.android.brand.kitSetFor
import app.streambird.android.data.ApiException
import app.streambird.android.data.BrandKit
import app.streambird.android.data.EffectiveLimits
import app.streambird.android.data.KitAd
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure rules around ads that are not geometry: plan gating, kit <-> editor conversion, per-stream overrides, loading and GIF planning. */

// ---- plan gating (backend: watermark plan = Free = no own ads; kits need assetStorageMb > 0) ----

enum class AdsAccess { LOADING, EDITABLE, FREE_PLAN, NO_STORAGE }

/** The editor is only editable once the plan limits have loaded; Free (watermark) and plans without a library are read-only. */
fun adsAccess(limits: EffectiveLimits?): AdsAccess = when {
    limits == null -> AdsAccess.LOADING
    limits.watermark -> AdsAccess.FREE_PLAN
    limits.assetStorageMb <= 0 -> AdsAccess.NO_STORAGE
    else -> AdsAccess.EDITABLE
}

// ---- kit editor <-> API ----

fun scheduleOf(a: KitAd): AdSchedule =
    if (a.mode == "interval" && a.showSec != null && a.everySec != null) AdSchedule.Interval(a.showSec, a.everySec) else AdSchedule.Continuous

/** One ad of a placement's sequence in the editor: its picture, its On switch and its seconds (interval: the show window; continuous: the dwell before the next ad; null = not set / default 20). */
data class AdEdit(val assetId: String, val enabled: Boolean = true, val showSec: Int? = null)

/**
 * One editor row: a placement with an optional picture. A row without a picture is not sent. 1.0.18 sequences: [assetId] / [enabled] / [schedule] are the FIRST ad
 * (the schedule's mode and pause are the whole placement's, its show window is the first ad's); [more] are the 2nd..5th ads in play order; [dwellSec] is the first
 * ad's seconds in continuous mode. A row with one ad and no [more] is exactly what it was before sequences.
 */
data class AdEditSlot(
    val placement: AdPlacement, val enabled: Boolean, val assetId: String?, val schedule: AdSchedule,
    val more: List<AdEdit> = emptyList(), val dwellSec: Int? = null,
) {
    /** The first ad's seconds (interval: its show window, continuous: [dwellSec]). */
    val firstSec: Int? get() = (schedule as? AdSchedule.Interval)?.showSec ?: dwellSec
    /** Every ad of the row in play order (empty without a picture). */
    val ads: List<AdEdit> get() = if (assetId == null) emptyList() else listOf(AdEdit(assetId, enabled, firstSec)) + more
    val count: Int get() = ads.size
    val canAddAnother: Boolean get() = assetId != null && count < MAX_ADS_PER_PLACEMENT
}

/** Rebuilds the row from [list] (the first entry becomes the row's own picture); an empty list removes every picture but keeps the row's mode and pause. */
fun AdEditSlot.withAds(list: List<AdEdit>): AdEditSlot {
    val l = list.take(MAX_ADS_PER_PLACEMENT)
    if (l.isEmpty()) return copy(assetId = null, more = emptyList(), dwellSec = null)
    val first = l[0]
    val sch = schedule
    return if (sch is AdSchedule.Interval) copy(assetId = first.assetId, enabled = first.enabled, schedule = sch.copy(showSec = first.showSec ?: sch.showSec), more = l.drop(1), dwellSec = null)
    else copy(assetId = first.assetId, enabled = first.enabled, more = l.drop(1), dwellSec = first.showSec)
}

/** Adds a picture at the end of the sequence (the first picture of an empty row sets the row's own); no-op at 5 ads. */
fun AdEditSlot.addAd(assetId: String): AdEditSlot =
    if (this.assetId == null) copy(assetId = assetId, more = emptyList())
    else if (count >= MAX_ADS_PER_PLACEMENT) this
    else copy(more = more + AdEdit(assetId, true, null))

fun AdEditSlot.removeAd(index: Int): AdEditSlot = if (index !in 0 until count) this else withAds(ads.filterIndexed { i, _ -> i != index })

/** Moves one ad up (-1) or down (+1) in the play order. */
fun AdEditSlot.moveAd(index: Int, delta: Int): AdEditSlot {
    val to = index + delta
    if (index !in 0 until count || to !in 0 until count) return this
    val l = ads.toMutableList(); val x = l.removeAt(index); l.add(to, x)
    return withAds(l)
}

fun AdEditSlot.replaceAd(index: Int, assetId: String): AdEditSlot = if (index !in 0 until count) this else withAds(ads.mapIndexed { i, a -> if (i == index) a.copy(assetId = assetId) else a })
fun AdEditSlot.setAdEnabled(index: Int, on: Boolean): AdEditSlot = if (index !in 0 until count) this else withAds(ads.mapIndexed { i, a -> if (i == index) a.copy(enabled = on) else a })
fun AdEditSlot.setAdSeconds(index: Int, sec: Int?): AdEditSlot = if (index !in 0 until count) this else withAds(ads.mapIndexed { i, a -> if (i == index) a.copy(showSec = sec) else a })

/**
 * Sets the placement's schedule (mode, pause between showings, the first ad's show window). Changing the MODE clears the other ads' own seconds (an interval
 * show window of 15 min is not a valid dwell, and a dwell is not a show window): they start from the defaults again.
 */
fun AdEditSlot.withSchedule(sch: AdSchedule): AdEditSlot {
    val modeChanged = (sch is AdSchedule.Interval) != (schedule is AdSchedule.Interval)
    val cleared = if (modeChanged) more.map { it.copy(showSec = null) } else more
    return copy(schedule = sch, more = cleared, dwellSec = if (modeChanged) null else dwellSec)
}

/** Rows of the editor for one set: [ads] = the set's first ads, [more] = its further ads (older servers: none). */
fun slotsFromKit(ads: List<KitAd>, more: List<KitAd> = emptyList()): List<AdEditSlot> = AdPlacement.entries.map { p ->
    val a = ads.firstOrNull { it.placement == p.wire }
    val sch = a?.let { scheduleOf(it) } ?: AdSchedule.Continuous
    val rest = if (a == null) emptyList() else more.filter { it.placement == p.wire }.take(MAX_ADS_PER_PLACEMENT - 1).map { AdEdit(it.assetId, it.enabled, it.showSec) }
    AdEditSlot(p, a?.enabled ?: true, a?.assetId, sch, rest, dwellSec = if (sch is AdSchedule.Interval) null else a?.showSec)
}

/** First problem in the editor (named placement + the web's message), or null. */
fun validateSlots(slots: List<AdEditSlot>): String? {
    for (s in slots) {
        if (s.assetId == null) continue
        scheduleError(s.schedule)?.let { return "${s.placement.label}: $it" }
        if (s.count < 2) continue
        s.ads.forEachIndexed { i, a -> adSecondsError(s.schedule, a.showSec)?.let { return "${s.placement.label} ad ${i + 1}: $it" } }
    }
    return null
}

/** The seconds of one ad of a sequence: interval = its show window (1 s..60 min, empty = the first ad's), continuous = the dwell (5..300 s, empty = 20 s). null = valid. */
fun adSecondsError(schedule: AdSchedule, sec: Int?): String? {
    if (sec == null) return null
    return if (schedule is AdSchedule.Interval) {
        if (sec < AD_SHOW_SEC_MIN || sec > AD_SHOW_SEC_MAX) "Show time must be between 1 second and 60 minutes." else null
    } else if (sec < AD_DWELL_MIN || sec > AD_DWELL_MAX) "Seconds before the next ad must be between $AD_DWELL_MIN and $AD_DWELL_MAX." else null
}

private fun editToKit(p: AdPlacement, schedule: AdSchedule, a: AdEdit, count: Int, firstShow: Int): KitAd =
    if (schedule is AdSchedule.Interval) KitAd(p.wire, a.assetId, a.enabled, "interval", a.showSec ?: firstShow, schedule.everySec)
    else KitAd(p.wire, a.assetId, a.enabled, "continuous", if (count >= 2) a.showSec else null, null)   // a lone continuous ad has no seconds

/** The `ads` array to send: only slots with a picture, in placement order, the FIRST ad of each; continuous ads carry null seconds when alone. */
fun kitAdsFromSlots(slots: List<AdEditSlot>): List<KitAd> = AdPlacement.entries.mapNotNull { p ->
    val s = slots.firstOrNull { it.placement == p } ?: return@mapNotNull null
    s.assetId ?: return@mapNotNull null
    editToKit(p, s.schedule, s.ads[0], s.count, (s.schedule as? AdSchedule.Interval)?.showSec ?: AD_SHOW_SEC_MIN)
}

/** The `adsMore` array to send: the 2nd..5th ads of every placement, placement by placement in play order. They carry the first ad's mode and pause (the server copies them anyway). */
fun kitAdsMoreFromSlots(slots: List<AdEditSlot>): List<KitAd> = AdPlacement.entries.flatMap { p ->
    val s = slots.firstOrNull { it.placement == p } ?: return@flatMap emptyList()
    if (s.assetId == null) return@flatMap emptyList()
    val firstShow = s.firstSec ?: AD_SHOW_SEC_MIN
    s.ads.drop(1).map { editToKit(p, s.schedule, it, s.count, firstShow) }
}

/** True when the user changed the ads (so the PATCH carries `ads`); false = omit it so other edits never touch the ads. */
fun adsEdited(original: List<KitAd>, edited: List<AdEditSlot>): Boolean = kitAdsFromSlots(edited) != kitAdsFromSlots(slotsFromKit(original))

/** What a kit save sends for one set: [ads] always; [adsMore] only when it matters (see [kitAdsSave]). */
data class KitAdsSave(val ads: List<KitAd>, val adsMore: List<KitAd>?)

/**
 * The ads part of a kit save for one set, or null when the user changed nothing (then NOTHING is sent). New clients send `ads` AND `adsMore` together so the set
 * is exact (`adsMore: []` = no further ads); `adsMore` is left out only when there were none and there are none (an older server never sees a field it
 * rejects, and a lone ad per placement saves exactly like before sequences).
 */
fun kitAdsSave(originalAds: List<KitAd>, originalMore: List<KitAd>, edited: List<AdEditSlot>): KitAdsSave? {
    val before = slotsFromKit(originalAds, originalMore)
    val first = kitAdsFromSlots(edited); val more = kitAdsMoreFromSlots(edited)
    if (first == kitAdsFromSlots(before) && more == kitAdsMoreFromSlots(before)) return null
    return KitAdsSave(first, more.takeIf { it.isNotEmpty() || kitAdsMoreFromSlots(before).isNotEmpty() })
}

// ---- per-stream state (Setup screen) ----

/** A picture picked on the phone for ONE stream: kept in memory, never uploaded. */
class PhoneAd(val bytes: ByteArray, val name: String, val gif: Boolean)

/**
 * One ad of a placement's sequence on THIS stream (1.0.18): a kit ad ([kitAssetId], found in the kit by its file) or a phone picture ([phone]), with its own
 * On switch and seconds ([showSec] null = the kit's / default).
 */
data class AdSeqEntry(val kitAssetId: String? = null, val phone: PhoneAd? = null, val enabled: Boolean = true, val showSec: Int? = null)

/**
 * What the user changed for this stream on top of the kit: null = as the kit says. The first three fields are the single-ad form (a switch for the placement, its
 * schedule, a phone picture replacing the first ad); [sequence] (1.0.18) is the full ordered list of the placement when the user edited it (reorder, remove, add,
 * per-ad On / seconds): then it wins and the single-ad fields are only the placement's schedule. [kitSeen] = the kit ad files that existed when the list was made,
 * so ads of a DIFFERENT kit chosen later are appended instead of vanishing, while an ad the user removed stays removed.
 */
data class AdOverride(
    val enabled: Boolean? = null, val schedule: AdSchedule? = null, val phone: PhoneAd? = null,
    val sequence: List<AdSeqEntry>? = null, val kitSeen: Set<String> = emptySet(),
)

enum class AdSource { KIT, PHONE }

/**
 * One ad of this stream after merging kit + overrides. [index] = its place in the placement's sequence (0 = first). [schedule] = the placement's mode and
 * pause with THIS ad's show window (interval); [dwellSec] = this ad's seconds in continuous mode (null = 20, or the lone ad).
 */
class ResolvedAd(
    val placement: AdPlacement, val source: AdSource, val assetId: String?, val phone: PhoneAd?, val enabled: Boolean, val schedule: AdSchedule,
    val index: Int = 0, val dwellSec: Int? = null,
) {
    /** The seconds the scheduler uses for this ad. */
    val showSec: Int get() = (schedule as? AdSchedule.Interval)?.showSec ?: dwellSec ?: AD_DWELL_DEFAULT
}

/** The kit's sequence of [p] in the flat list [kitAds] (play order). */
private fun kitSeqOf(kitAds: List<KitAd>?, p: AdPlacement): List<KitAd> = kitAds.orEmpty().filter { it.placement == p.wire }.take(MAX_ADS_PER_PLACEMENT)

/** The entries of [o]'s list against the current kit sequence: kit ads by file (each file used once), ads of the kit the list has not seen appended, vanished ones dropped. */
internal fun entriesOf(kit: List<KitAd>, o: AdOverride): List<AdSeqEntry> {
    val seq = o.sequence ?: return emptyList()
    val pool = kit.toMutableList()
    val out = ArrayList<AdSeqEntry>()
    for (e in seq) {
        if (e.phone != null) { out.add(e); continue }
        val i = pool.indexOfFirst { it.assetId == e.kitAssetId }
        if (i >= 0) { pool.removeAt(i); out.add(e) }
    }
    for (k in pool) if (k.assetId !in o.kitSeen) out.add(AdSeqEntry(kitAssetId = k.assetId))
    return out.take(MAX_ADS_PER_PLACEMENT)
}

private class MergeRow(val source: AdSource, val assetId: String?, val phone: PhoneAd?, val enabled: Boolean, val sec: Int?)

/**
 * Kit ads apply automatically with the kit (like the logo); the user may switch one off, change its schedule or seconds, reorder, remove, add phone pictures, or
 * replace its file with a phone picture for this stream. Every kit ad starts ENABLED on a new stream, even if the kit saved it off (only the stream's own
 * switch-off counts). With no kit ("manual") the kit's ads are cleared but phone-picked ones stay. Free plan: no custom ads at all.
 * [kitAds] = every ad of the kit's set (placement by placement, each in play order, see [app.streambird.android.brand.kitSetFor]); the result lists the ads of
 * every placement in play order (at most 5 each). The mode and pause of a placement are its first ad's / the override's; every ad has its own show window.
 */
fun mergeStreamAds(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, freePlan: Boolean): List<ResolvedAd> {
    if (freePlan) return emptyList()
    return AdPlacement.entries.flatMap { p ->
        val kit = kitSeqOf(kitAds, p)
        val o = overrides[p]
        val rows: List<MergeRow> = if (o?.sequence != null) {
            entriesOf(kit, o).map { e ->
                MergeRow(if (e.phone != null) AdSource.PHONE else AdSource.KIT, e.kitAssetId.takeIf { e.phone == null }, e.phone, e.enabled, e.showSec ?: kit.firstOrNull { it.assetId == e.kitAssetId }?.showSec)
            }
        } else {
            val on = o?.enabled ?: true
            val base = kit.map { MergeRow(AdSource.KIT, it.assetId, null, on, it.showSec) }
            if (o?.phone != null) listOf(MergeRow(AdSource.PHONE, null, o.phone, on, null)) + base.drop(1) else base
        }
        if (rows.isEmpty()) return@flatMap emptyList()
        // Placement schedule: the override's, else the kit's first ad's; every ad then gets its own show window.
        val sch = o?.schedule ?: kit.firstOrNull()?.let { scheduleOf(it) } ?: AdSchedule.Continuous
        rows.mapIndexed { i, r ->
            val own = if (sch is AdSchedule.Interval) AdSchedule.Interval(if (i == 0 && o?.sequence == null) sch.showSec else (r.sec ?: sch.showSec), sch.everySec) else AdSchedule.Continuous
            // A lone continuous ad has no seconds; with several ads a missing one is the default.
            ResolvedAd(p, r.source, r.assetId, r.phone, r.enabled, own, i, if (sch is AdSchedule.Interval || rows.size < 2) null else r.sec)
        }
    }
}

/** The ads of ONE placement of [merged] in play order. */
fun List<ResolvedAd>.of(p: AdPlacement): List<ResolvedAd> = filter { it.placement == p }.sortedBy { it.index }

// ---- independent loading ----

/** [kitName] = the brand kit the ad came from (null for a phone picture). */
class AdLoadFailure(val placement: AdPlacement, val reason: String, val kitName: String? = null, val index: Int = 0)

/** "Could not load the L-shaped frame of kit 'Acme' (File not found)." / "... the Left third ad (...)". */
fun describeAdFailure(f: AdLoadFailure): String {
    val what = if (f.index > 0) "${f.placement.label} ad ${f.index + 1}" else if (f.placement == AdPlacement.L_SHAPE) f.placement.label else "${f.placement.label} ad"
    val of = f.kitName?.let { " of kit '$it'" }.orEmpty()
    return "Could not load the $what$of (${f.reason.take(80).trimEnd('.')})."
}

/** One message naming every ad that could not be loaded (the others still run), or null when all loaded. */
fun describeAdFailures(failures: List<AdLoadFailure>): String? {
    if (failures.isEmpty()) return null
    return failures.joinToString(" ") { describeAdFailure(it) } + " The other ads and the logo are not affected."
}

/** The kit list itself could not be fetched at go-live: no kit ad can be placed. */
fun describeKitFetchFailure(reason: String): String =
    "Could not load the brand kit's ads (${reason.take(80).trimEnd('.')}). The kit's ads are not on this stream; the logo and picture are not affected."

/** A kit with ads for the other shape only: the host would see no ads and not know why. null = nothing to say. */
fun kitAdsMissingNote(kit: BrandKit?, o: BrandOrientation): String? {
    kit ?: return null
    if (kitSetFor(kit, o).ads.isNotEmpty()) return null
    val other = if (o == BrandOrientation.PORTRAIT) BrandOrientation.LANDSCAPE else BrandOrientation.PORTRAIT
    if (kitSetFor(kit, other).ads.isEmpty()) return null
    return "The brand kit '${kit.name}' has no ads for ${o.label} streams, only for ${other.label}. Add them under Brand kits."
}

/** A failed ad download is tried once more only when the cause can be temporary (network, server error, rate limit), never for 404 / too large. */
fun isTransientAdError(t: Throwable): Boolean = when (t) {
    is ApiException -> t.status >= 500 || t.status == 429 || t.status == 408
    is java.io.IOException -> true
    else -> false
}

/** Pictures picked on the phone: same caps as the web (12 MB, 8192 px longest side). Returns the refusal or null. */
const val MAX_PHONE_AD_BYTES = 12 * 1024 * 1024
const val MAX_AD_SIDE = 8192
fun phoneAdError(sizeBytes: Int, width: Int, height: Int): String? = when {
    sizeBytes > MAX_PHONE_AD_BYTES -> "That picture is too large for an ad -- the limit is ${MAX_PHONE_AD_BYTES / 1024 / 1024} MB."
    width > MAX_AD_SIDE || height > MAX_AD_SIDE -> "That picture is too big for an ad -- its longest side must be at most ${MAX_AD_SIDE}px."
    else -> null
}

// ---- GIF planning ----

const val MAX_GIF_FRAMES = 150
const val MAX_GIF_BYTES = 96L * 1024 * 1024

/** Counts image frames of a GIF by walking its blocks (no decoding). -1 when the data is not a readable GIF. */
fun countGifFrames(b: ByteArray): Int {
    if (b.size < 13 || String(b, 0, 3, Charsets.ISO_8859_1) != "GIF") return -1
    fun u8(i: Int) = b[i].toInt() and 0xff
    val packed = u8(10)
    var i = 13 + if (packed and 0x80 != 0) 3 * (1 shl ((packed and 7) + 1)) else 0
    var frames = 0
    while (i < b.size) {
        when (u8(i)) {
            0x3B -> return frames
            0x21 -> { i += 2; while (i < b.size && u8(i) != 0) i += u8(i) + 1; i++ }
            0x2C -> {
                if (i + 10 > b.size) return frames
                val lp = u8(i + 9)
                i += 10 + if (lp and 0x80 != 0) 3 * (1 shl ((lp and 7) + 1)) else 0
                i++ // LZW minimum code size
                while (i < b.size && u8(i) != 0) i += u8(i) + 1
                i++; frames++
            }
            else -> return if (frames > 0) frames else -1
        }
    }
    return frames
}

data class GifPlan(val animate: Boolean, val targetW: Int, val targetH: Int, val reason: String?)

/**
 * Decode size = the picture fitted into its area (never larger than the picture itself); animate only when the frame count and the
 * decoded size are within the web's caps (150 frames, 96 MB in total) and the phone can do it (API 28+). Otherwise a still, with the reason to tell the host.
 */
fun planGif(bytes: ByteArray, imgW: Int, imgH: Int, areaW: Int, areaH: Int, sdk: Int): GifPlan {
    if (imgW <= 0 || imgH <= 0 || areaW <= 0 || areaH <= 0) return GifPlan(false, 0, 0, "unreadable size")
    val scale = min(1.0, min(areaW.toDouble() / imgW, areaH.toDouble() / imgH))
    val w = max(1, (imgW * scale).roundToInt()); val h = max(1, (imgH * scale).roundToInt())
    if (sdk < 28) return GifPlan(false, w, h, "animated GIFs need Android 9 or newer")
    val frames = countGifFrames(bytes)
    if (frames < 0) return GifPlan(false, w, h, "the GIF could not be read")
    if (frames < 2) return GifPlan(false, w, h, null)               // a single frame is a still, nothing to report
    if (frames > MAX_GIF_FRAMES) return GifPlan(false, w, h, "the GIF has $frames frames (limit $MAX_GIF_FRAMES)")
    if (w.toLong() * h * 4 * frames > MAX_GIF_BYTES) return GifPlan(false, w, h, "the GIF would need too much memory")
    return GifPlan(true, w, h, null)
}

/** Sample size for a still: the largest power of two that keeps the picture at least as big as its area (bounds memory for 8192 px pictures). */
fun stillSampleSize(imgW: Int, imgH: Int, areaW: Int, areaH: Int): Int {
    var s = 1
    while (imgW / (s * 2) >= areaW && imgH / (s * 2) >= areaH) s *= 2
    return s
}

// ---- what the Setup / Customize ads block tells about the kit itself ----

/** A line above the ad rows: [warn] = amber (something prevents the kit's ads), else plain information. */
data class KitAdsNotice(val text: String, val warn: Boolean)

/**
 * Why the ad rows show what they show. [kit] = the kit that would apply (explicit choice or automatic) or null.
 * Order: the logo choice switched kit use off; the kit list could not be loaded; still loading; no kit applies; the kit has no ads for this shape; else the kit's name.
 */
fun kitAdsSetupNotice(
    logoIsBrand: Boolean, logoLabel: String,
    loading: Boolean, loaded: Boolean, error: String?, blocked: Boolean,
    kit: BrandKit?, orientation: BrandOrientation,
): KitAdsNotice? {
    if (!logoIsBrand) return KitAdsNotice("The brand kit's ads are not used because the logo is set to \"$logoLabel\". Choose \"Brand kit logo\" to use them.", true)
    if (blocked) return null
    if (kit == null && error != null) return KitAdsNotice("Could not load your brand kits ($error). The kit's ads are not listed here; they are fetched again when the stream starts.", true)
    if (kit == null && (loading || !loaded)) return KitAdsNotice("Loading the brand kit...", false)
    if (kit == null) return KitAdsNotice("No brand kit applies to the ticked channels (none chosen and no default kit), so there are no kit ads.", true)
    kitAdsMissingNote(kit, orientation)?.let { return KitAdsNotice(it, true) }
    return KitAdsNotice("Brand kit: ${kit.name}", false)
}

/** Used by the Setup/Editor UIs to show which kit ads exist without the editor. */
fun kitAdSummary(kit: BrandKit?): List<Pair<AdPlacement, String>> =
    kit?.ads.orEmpty().mapNotNull { a -> AdPlacement.fromWire(a.placement)?.let { it to (if (a.enabled) describeSchedule(scheduleOf(a)) else "Off") } }
