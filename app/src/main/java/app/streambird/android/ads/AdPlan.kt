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

/** One editor row: a placement with an optional picture. A row without a picture is not sent. */
data class AdEditSlot(val placement: AdPlacement, val enabled: Boolean, val assetId: String?, val schedule: AdSchedule)

fun slotsFromKit(ads: List<KitAd>): List<AdEditSlot> = AdPlacement.entries.map { p ->
    val a = ads.firstOrNull { it.placement == p.wire }
    AdEditSlot(p, a?.enabled ?: true, a?.assetId, a?.let { scheduleOf(it) } ?: AdSchedule.Continuous)
}

/** First problem in the editor (named placement + the web's message), or null. */
fun validateSlots(slots: List<AdEditSlot>): String? {
    for (s in slots) {
        if (s.assetId == null) continue
        scheduleError(s.schedule)?.let { return "${s.placement.label}: $it" }
    }
    return null
}

/** The `ads` array to send: only slots with a picture, in placement order; continuous ads carry null seconds. */
fun kitAdsFromSlots(slots: List<AdEditSlot>): List<KitAd> = AdPlacement.entries.mapNotNull { p ->
    val s = slots.firstOrNull { it.placement == p } ?: return@mapNotNull null
    val id = s.assetId ?: return@mapNotNull null
    val sch = s.schedule
    if (sch is AdSchedule.Interval) KitAd(p.wire, id, s.enabled, "interval", sch.showSec, sch.everySec) else KitAd(p.wire, id, s.enabled, "continuous", null, null)
}

/** True when the user changed the ads (so the PATCH carries `ads`); false = omit it so other edits never touch the ads. */
fun adsEdited(original: List<KitAd>, edited: List<AdEditSlot>): Boolean = kitAdsFromSlots(edited) != kitAdsFromSlots(slotsFromKit(original))

// ---- per-stream state (Setup screen) ----

/** A picture picked on the phone for ONE stream: kept in memory, never uploaded. */
class PhoneAd(val bytes: ByteArray, val name: String, val gif: Boolean)

/** What the user changed for this stream on top of the kit: null = as the kit says. */
data class AdOverride(val enabled: Boolean? = null, val schedule: AdSchedule? = null, val phone: PhoneAd? = null)

enum class AdSource { KIT, PHONE }

/** One ad of this stream after merging kit + overrides. */
class ResolvedAd(val placement: AdPlacement, val source: AdSource, val assetId: String?, val phone: PhoneAd?, val enabled: Boolean, val schedule: AdSchedule)

/**
 * Kit ads apply automatically with the kit (like the logo); the user may switch one off, change its schedule, or replace its file with a
 * phone picture for this stream. With no kit ("manual") the kit's ads are cleared but phone-picked ones stay. Free plan: no custom ads at all.
 */
fun mergeStreamAds(kitAds: List<KitAd>?, overrides: Map<AdPlacement, AdOverride>, freePlan: Boolean): List<ResolvedAd> {
    if (freePlan) return emptyList()
    return AdPlacement.entries.mapNotNull { p ->
        val kit = kitAds?.firstOrNull { it.placement == p.wire }
        val o = overrides[p]
        when {
            o?.phone != null -> ResolvedAd(p, AdSource.PHONE, null, o.phone, o.enabled ?: true, o.schedule ?: kit?.let { scheduleOf(it) } ?: AdSchedule.Continuous)
            kit != null -> ResolvedAd(p, AdSource.KIT, kit.assetId, null, o?.enabled ?: kit.enabled, o?.schedule ?: scheduleOf(kit))
            else -> null
        }
    }
}

// ---- independent loading ----

/** [kitName] = the brand kit the ad came from (null for a phone picture). */
class AdLoadFailure(val placement: AdPlacement, val reason: String, val kitName: String? = null)

/** "Could not load the L-shaped frame of kit 'Acme' (File not found)." / "... the Left third ad (...)". */
fun describeAdFailure(f: AdLoadFailure): String {
    val what = if (f.placement == AdPlacement.L_SHAPE) f.placement.label else "${f.placement.label} ad"
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
