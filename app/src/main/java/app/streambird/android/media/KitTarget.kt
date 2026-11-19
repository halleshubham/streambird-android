package app.streambird.android.media

import app.streambird.android.ads.AdPlacement
import app.streambird.android.clock.ClockSettings
import java.util.concurrent.Executor

/**
 * Everything of a brand kit that changes the running picture, already downloaded and decoded where possible ([logo] is a decoded bitmap, the ads are
 * raw files the engine decodes into an ad layer). [clock] and [shrink] are plain values. Built by the controller, handed to [KitTarget.prepare].
 */
class KitContent(
    val kitId: String,
    val logo: LogoOverlay?,
    val adInputs: List<AdInput>,
    val shrink: Boolean,
    val clock: ClockSettings,
    /** The stream's ad clock start (SystemClock.elapsedRealtime at LIVE): the new ads keep the schedule where it is, nothing restarts. */
    val adClockStartMs: Long,
)

/** The result of [KitTarget.prepare]: a new ad layer (decoded, not yet on the GPU) waiting for [KitTarget.swap]. */
interface PreparedKitHandle {
    /** Ads whose picture could not be read / decoded. */
    val unreadable: List<AdPlacement>
    /** L-shaped frame graphics without a clear window (shown as a plain overlay, like at go-live). */
    val noWindow: List<AdPlacement>
    /** Plain-language notes about the pictures (an animated GIF shown as a still ...): for the diagnostic log only. */
    val notes: List<String>
    /** Frees what was prepared when it will never be swapped in (never touches the GPU, any thread). */
    fun discard()
}

/**
 * The two halves of a live kit change. [prepare] runs on ANY thread except the render thread and may take a while (bitmap decoding); [swap] runs ONLY
 * on the render thread (it is posted there with [renderExecutor]) and is short: one texture upload and a handful of reference assignments, so it fits
 * between two camera frames. Splitting it this way is what keeps the picture flowing while a kit is applied.
 */
interface KitTarget {
    /** Posts a task to the render thread, to run between two frames. Throws when the pipeline is gone. */
    val renderExecutor: Executor
    fun prepare(content: KitContent): PreparedKitHandle
    /** Render thread only. True = the kit is on air; false = nothing was changed. */
    fun swap(prepared: PreparedKitHandle, content: KitContent): Boolean
}
