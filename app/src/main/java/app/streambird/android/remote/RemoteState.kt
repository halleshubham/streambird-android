package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.ads.AdSource
import app.streambird.android.clock.ClockSettings
import app.streambird.android.stream.LiveAd
import kotlinx.serialization.json.JsonObject
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
)

/**
 * The compact snapshot of the settings a support session may change, sent with `PUT /streams/{id}/remote-control/state`.
 * Only what exists on the phone and is controllable: no tokens, no cookies, no URLs, no pictures, no ad or logo files, no account data.
 * (The ticker text is included because the web studio shows it as the current value.)
 */
object RemoteState {
    const val SCHEMA = 1

    fun build(i: RemoteStateInput): JsonObject = buildJsonObject {
        put("v", SCHEMA)
        put("platform", "android")
        put("appVersion", i.appVersion.take(32))
        put("orientation", i.orientation.take(16))
        put("freePlan", i.freePlan)
        put("ads", buildJsonObject {
            for (p in AdPlacement.entries) {
                val a = i.ads.firstOrNull { it.placement == p }
                put(p.wire, if (a == null) buildJsonObject { put("present", false) } else ad(a))
            }
        })
        put("adsShrink", i.adsShrink)
        put("ticker", buildJsonObject {
            val t = i.tickerText.takeIf { it.isNotEmpty() }
            put("text", t)
            put("on", t != null)
        })
        put("clock", clock(i.clock))
    }

    private fun ad(a: LiveAd): JsonObject = buildJsonObject {
        // A picture that failed to load counts as not present: it cannot be shown or switched.
        val usable = a.problem == null
        put("present", usable)
        put("enabled", usable && a.enabled)
        put("manual", when (a.manual) { AdManual.SHOWN -> "shown"; AdManual.HIDDEN -> "hidden"; null -> null })
        when (val s = a.schedule) {
            is AdSchedule.Continuous -> put("mode", "continuous")
            is AdSchedule.Interval -> { put("mode", "interval"); put("showSec", s.showSec); put("everySec", s.everySec) }
        }
        put("source", if (a.source == AdSource.PHONE) "phone" else "kit")
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
}

