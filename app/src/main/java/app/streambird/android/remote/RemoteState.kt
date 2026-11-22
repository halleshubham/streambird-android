package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdSource
import app.streambird.android.ads.MAX_ADS_PER_PLACEMENT
import app.streambird.android.ads.SeqAd
import app.streambird.android.ads.adSequenceState
import app.streambird.android.ads.seqScheduleOf
import app.streambird.android.clock.ClockSettings
import app.streambird.android.media.NameBanner
import app.streambird.android.media.NameBannerState
import app.streambird.android.stream.LiveAd
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Everything the snapshot is built from (plain values, so the builder is pure and JVM-testable). */
data class RemoteStateInput(
    val ads: List<LiveAd>,
    val adsShrink: Boolean,
    val tickerText: String,
    val clock: ClockSettings,
    val orientation: String,
    val appVersion: String,
    val freePlan: Boolean,
    /** 1.0.15: the customer's brand kits as last fetched (empty = unknown), the kit on air now, the logo and the name banner. */
    val kits: List<KitRef> = emptyList(),
    val kitId: String? = null,
    val logo: LogoInfo = LogoInfo(false, false, DEFAULT_LOGO_SIZE),
    val banner: NameBannerState = NameBannerState(),
    /** 1.0.18: seconds since go-live, to say which ad of a sequence is current (`current`); the snapshot is taken when the state changes, so it is the ad current THEN. */
    val adElapsedSec: Double = 0.0,
)

/** A brand kit as the snapshot lists it: id and name only. */
data class KitRef(val id: String, val name: String)

/** The logo on air: [onRight] false = left, [size] = height in canvas px (the kit's `logoSize` unit). [present] false = the stream has no logo. */
data class LogoInfo(val present: Boolean, val onRight: Boolean, val size: Int)

const val DEFAULT_LOGO_SIZE = 90

/**
 * The compact snapshot of the settings a support session may change, sent with `PUT /streams/{id}/remote-control/state`.
 * Only what exists on the phone and is controllable: no tokens, no cookies, no URLs, no pictures, no ad or logo files, no account data.
 * (The ticker text is included because the web studio shows it as the current value.)
 */
object RemoteState {
    const val SCHEMA = 1
    const val MAX_KITS = 50
    const val MAX_KIT_NAME = 80
    const val MAX_KIT_ID = 64

    fun build(i: RemoteStateInput): JsonObject = buildJsonObject {
        put("v", SCHEMA)
        put("platform", "android")
        put("appVersion", i.appVersion.take(32))
        put("orientation", i.orientation.take(16))
        put("freePlan", i.freePlan)
        put("ads", buildJsonObject {
            for (p in AdPlacement.entries) {
                val g = i.ads.filter { it.placement == p }.sortedBy { it.index }
                put(p.wire, if (g.isEmpty()) buildJsonObject { put("present", false) } else ad(g, i.adElapsedSec))
            }
        })
        put("adsShrink", i.adsShrink)
        put("ticker", buildJsonObject {
            val t = i.tickerText.takeIf { it.isNotEmpty() }
            put("text", t)
            put("on", t != null)
        })
        put("clock", clock(i.clock))
        // 1.0.15 optional fields (an older control page ignores them).
        val kits = if (i.freePlan) emptyList() else i.kits.filter { it.id.isNotEmpty() && it.id.length <= MAX_KIT_ID }.take(MAX_KITS)
        if (kits.isNotEmpty()) put("kits", buildJsonArray { kits.forEach { k -> add(buildJsonObject { put("id", k.id); put("name", NameBanner.clean(k.name, MAX_KIT_NAME).ifEmpty { "Brand kit" }) }) } })
        put("kit", i.kitId?.takeIf { it.isNotEmpty() && it.length <= MAX_KIT_ID }?.let { id -> buildJsonObject { put("id", id) } } ?: JsonNull)
        put("logo", buildJsonObject {
            put("present", i.logo.present)
            put("position", if (i.logo.onRight) "right" else "left")
            put("size", i.logo.size.coerceIn(RemoteParser.MIN_LOGO_SIZE, RemoteParser.MAX_LOGO_SIZE))
        })
        put("lowerThird", buildJsonObject {
            put("available", true)
            put("name", i.banner.name.takeIf { it.isNotEmpty() })
            put("title", i.banner.title)
            put("show", i.banner.show)
        })
    }

    /**
     * One placement. A single ad is exactly what it always was. With several ads (1.0.18) the placement keeps ONE state: `enabled` = any ad is on, `manual`, the mode and pause
     * are the placement's (the first usable ad's), and two optional fields describe the sequence for a control page that knows them: `sequence` = every ad in play order
     * (`[{assetId?, enabled, showSec}]`, at most 5; an ad that failed to load is `enabled: false`) and `current` = the 0-based index of the ad showing now (or, in the hidden part
     * of "show, then hide", the one that shows next); absent when no ad is on. `ads.enabled` / `ads.manual` / `ads.schedule` commands act on the whole placement.
     */
    private fun ad(g: List<LiveAd>, elapsedSec: Double): JsonObject = buildJsonObject {
        val lead = g.firstOrNull { it.problem == null } ?: g.first()
        // A picture that failed to load counts as not present: it cannot be shown or switched.
        val usable = g.any { it.problem == null }
        put("present", usable)
        put("enabled", g.any { it.problem == null && it.enabled })
        put("manual", when (lead.manual) { AdManual.SHOWN -> "shown"; AdManual.HIDDEN -> "hidden"; null -> null })
        when (val s = lead.schedule) {
            is AdSchedule.Continuous -> put("mode", "continuous")
            is AdSchedule.Interval -> { put("mode", "interval"); put("showSec", s.showSec); put("everySec", s.everySec) }
        }
        put("source", if (lead.source == AdSource.PHONE) "phone" else "kit")
        if (g.size >= 2) {
            val list = g.take(MAX_ADS_PER_PLACEMENT)
            put("sequence", buildJsonArray {
                list.forEach { a -> add(buildJsonObject {
                    a.assetId?.takeIf { a.source == AdSource.KIT }?.let { put("assetId", it) }
                    put("enabled", a.problem == null && a.enabled)
                    put("showSec", a.showSec.coerceIn(1, 3600))
                }) }
            })
            val sch = seqScheduleOf(lead.schedule)
            val st = adSequenceState(elapsedSec, list.map { SeqAd(it.problem == null && it.enabled, it.showSec) }, sch)
            if (st.index >= 0) put("current", st.index)
        }
    }

    fun clock(c: ClockSettings): JsonObject = buildJsonObject {
        put("enabled", c.enabled)
        put("position", c.position.wire)
        put("format", c.hours.wire)
        put("showDate", c.showDate)
        put("showSeconds", c.showSeconds)
        put("timezone", c.timezone)
        put("size", c.size.wire)
        put("style", c.style.wire)
        put("alternate", c.alternate)
    }
}

/** Keys of the settings that host-originated changes are tracked by (the conflict rule), one per thing a command can change. */
object RemoteKeys {
    fun adManual(p: AdPlacement) = "ads.manual:${p.wire}"
    fun adEnabled(p: AdPlacement) = "ads.enabled:${p.wire}"
    fun adSchedule(p: AdPlacement) = "ads.schedule:${p.wire}"
    const val ADS_SHRINK = "ads.shrink"
    const val TICKER = "ticker"
    const val CLOCK = "clock"
    const val LOGO = "logo"
    const val LOWER_THIRD = "lowerThird"
    const val KIT = "kit"

    /** Everything a kit change replaces: it is refused when the host touched any of these a moment ago, and aborted when the host touches one while it is being prepared. */
    fun kitCovers(): List<String> =
        listOf(KIT, LOGO, CLOCK, ADS_SHRINK) + AdPlacement.entries.flatMap { listOf(adManual(it), adEnabled(it), adSchedule(it)) }
}

