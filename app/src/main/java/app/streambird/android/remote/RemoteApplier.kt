package app.streambird.android.remote

import app.streambird.android.ads.AdManual
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.clock.ClockSettings
import app.streambird.android.media.Ticker

/**
 * What a remote command may change on the running stream. Implemented by the stream controller, whose methods apply the change to the engine
 * (volatile fields read by the GL thread: no frame is dropped) and update the screen state, but do NOT count as host changes and write nothing
 * to the host-visible event list.
 */
interface RemoteControls {
    /** True only while the stream is LIVE (not starting, ending or ended). */
    val isLive: Boolean
    /** An ad of this placement exists on this stream and loaded (so it can be shown, hidden, switched or rescheduled). */
    fun hasAd(p: AdPlacement): Boolean
    fun tickerText(): String
    fun clock(): ClockSettings
    fun applyAdManual(p: AdPlacement, m: AdManual?)
    fun applyAdEnabled(p: AdPlacement, on: Boolean)
    fun applyAdsShrink(on: Boolean)
    fun applyAdSchedule(p: AdPlacement, s: AdSchedule)
    /** Empty text = ticker off. Size stays what the host chose; nothing is saved to the phone's preferences. */
    fun applyTicker(text: String)
    fun applyClock(c: ClockSettings)
}

data class ApplyResult(val ok: Boolean, val error: String? = null) {
    companion object { val OK = ApplyResult(true); fun err(msg: String) = ApplyResult(false, msg) }
}

/** Maps each validated command onto the live controls. Never throws: a failure becomes an error result. */
class RemoteApplier(private val controls: RemoteControls, private val host: HostChangeTracker) {
    fun apply(cmd: RemoteCommand): ApplyResult {
        val a = cmd.action
        if (a is RemoteAction.Unsupported) return ApplyResult.err(a.why)
        if (!controls.isLive) return ApplyResult.err("stream not live")
        return try { applyLive(a) } catch (e: Exception) { ApplyResult.err("could not apply") }
    }

    private fun applyLive(a: RemoteAction): ApplyResult {
        when (a) {
            is RemoteAction.AdsManual -> return ad(a.placement, RemoteKeys.adManual(a.placement)) { controls.applyAdManual(a.placement, a.mode) }
            is RemoteAction.AdsEnabled -> return ad(a.placement, RemoteKeys.adEnabled(a.placement)) { controls.applyAdEnabled(a.placement, a.on) }
            is RemoteAction.AdsSchedule -> return ad(a.placement, RemoteKeys.adSchedule(a.placement)) { controls.applyAdSchedule(a.placement, a.schedule) }
            is RemoteAction.AdsShrink -> return guarded(RemoteKeys.ADS_SHRINK) { controls.applyAdsShrink(a.on) }
            is RemoteAction.TickerSet -> {
                val text = if (a.on) Ticker.sanitize(a.text ?: controls.tickerText()) else ""
                if (a.on && text.isEmpty()) return ApplyResult.err("no ticker text")
                return guarded(RemoteKeys.TICKER) { controls.applyTicker(text) }
            }
            is RemoteAction.ClockSet -> return guarded(RemoteKeys.CLOCK) { controls.applyClock(a.patch.applyTo(controls.clock())) }
            is RemoteAction.Unsupported -> return ApplyResult.err(a.why)
        }
    }

    private fun ad(p: AdPlacement, key: String, change: () -> Unit): ApplyResult =
        if (!controls.hasAd(p)) ApplyResult.err("no ${p.wire} ad on this stream") else guarded(key, change)

    private fun guarded(key: String, change: () -> Unit): ApplyResult {
        if (host.recent(key)) return ApplyResult.err(HOST_CHANGED)
        change()
        return ApplyResult.OK
    }

    companion object { const val HOST_CHANGED = "host changed it just now" }
}
