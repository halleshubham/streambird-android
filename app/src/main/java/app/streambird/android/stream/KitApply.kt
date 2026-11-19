package app.streambird.android.stream

import android.graphics.Bitmap
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSource
import app.streambird.android.ads.MAX_AD_SIDE
import app.streambird.android.ads.isTransientAdError
import app.streambird.android.ads.mergeStreamAds
import app.streambird.android.brand.BrandOrientation
import app.streambird.android.brand.kitSetFor
import app.streambird.android.brand.sniffImage
import app.streambird.android.clock.ClockSettings
import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.BrandKit
import app.streambird.android.media.AdInput
import app.streambird.android.media.KitContent
import app.streambird.android.media.KitTarget
import app.streambird.android.media.LogoOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Where a live kit change gets its data: the same two calls as at go-live (GET /brand-kits, GET /assets/{id}/file) and nothing else. */
interface KitSource {
    suspend fun kits(): List<BrandKit>
    suspend fun file(assetId: String): ByteArray
}

/** The app's own server only: the kit list and the library files of the configured base URL (`ApiClient` builds every URL from it). */
class ApiKitSource(private val api: ApiClient) : KitSource {
    override suspend fun kits() = api.brandKits()
    override suspend fun file(assetId: String) = api.assetFile(assetId)
}

sealed class KitOutcome {
    /** The kit is ON AIR. [kits] = the customer's kits as just fetched; [logoBytes] = the logo file when it is a GIF candidate (the controller starts its animation). */
    class Applied(
        val kit: BrandKit, val kits: List<BrandKit>, val logo: LogoOverlay?, val logoBytes: ByteArray?, val inputs: List<AdInput>,
        val sources: Map<AdPlacement, AdSource>, val shrink: Boolean, val clock: ClockSettings, val noWindow: List<AdPlacement>,
    ) : KitOutcome()

    /** Nothing changed on air. [reason] is the short phrase for the ack. [kits] is set when the list was fetched before the failure. */
    class Failed(val reason: String, val kits: List<BrandKit>? = null) : KitOutcome()
}

private class KitFail(val reason: String, val kits: List<BrandKit>? = null) : Exception(reason)

/**
 * Applies one brand kit to the RUNNING stream without disturbing the picture. Steps, in order:
 *  1. fetch the kit list fresh and pick the kit by id ("kit not found");
 *  2. download the kit's logo and ads for the stream's orientation in parallel, each with a [downloadTimeoutMs] limit, one retry on a temporary error,
 *     the same 10 MB download cap and 8192 px side cap as at go-live ("download failed" / "too large" / plan reasons);
 *  3. decode the logo and let the [KitTarget] decode the ads into a new ad layer (all of this on [cpu], never on the render thread);
 *  4. ask [guard] whether it is still fine to change the picture (stream still live, the host did not touch these settings meanwhile);
 *  5. post ONE short task to the render thread ([KitTarget.renderExecutor]) that swaps logo, ads, shrink and clock between two frames.
 * Any failure at any step discards what was prepared and leaves the old picture exactly as it was. One apply at a time ([tryBegin]).
 * Log lines carry steps and reasons only, never names, ids or text.
 */
class KitApplier(
    private val source: KitSource,
    private val decodeLogo: (ByteArray) -> Bitmap?,
    private val log: (String) -> Unit = {},
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    @Volatile var downloadTimeoutMs: Long = DOWNLOAD_TIMEOUT_MS,
    @Volatile var swapTimeoutMs: Long = SWAP_TIMEOUT_MS,
    @Volatile var retryDelayMs: Long = 400L,
) {
    private val running = AtomicBoolean(false)

    /** True when this call got the one slot; the caller must then run [run] (which frees it) or call [release]. */
    fun tryBegin(): Boolean = running.compareAndSet(false, true)
    fun release() = running.set(false)
    val isRunning: Boolean get() = running.get()

    suspend fun run(kitId: String, orientation: BrandOrientation, target: KitTarget, adClockStartMs: Long, guard: () -> String?): KitOutcome = try {
        runSteps(kitId, orientation, target, adClockStartMs, guard)
    } catch (e: CancellationException) {
        throw e
    } catch (e: KitFail) {
        log("kit apply: failed (${e.reason})")
        KitOutcome.Failed(e.reason, e.kits)
    } catch (t: Throwable) {
        log("kit apply: failed (${t.javaClass.simpleName})")
        KitOutcome.Failed("could not apply")
    } finally {
        running.set(false)
    }

    private suspend fun runSteps(kitId: String, orientation: BrandOrientation, target: KitTarget, adClockStartMs: Long, guard: () -> String?): KitOutcome {
        log("kit apply: fetching the kit list")
        val kits = try { timed("kit list") { source.kits() } } catch (e: ApiException) { throw KitFail(reasonOf(e, ad = false)) } catch (e: java.io.IOException) { throw KitFail("download failed") }
        val kit = kits.firstOrNull { it.id == kitId } ?: throw KitFail("kit not found", kits)
        try {
            return applyKit(kit, kits, orientation, target, adClockStartMs, guard)
        } catch (e: KitFail) {
            throw KitFail(e.reason, kits)
        }
    }

    private suspend fun applyKit(kit: BrandKit, kits: List<BrandKit>, orientation: BrandOrientation, target: KitTarget, adClockStartMs: Long, guard: () -> String?): KitOutcome {
        val set = kitSetFor(kit, orientation)
        val merged = mergeStreamAds(set.ads, emptyMap(), freePlan = false) // the kit's ads REPLACE the ad set of the stream: no per-stream overrides survive
        log("kit apply: downloading ${merged.size} ads" + if (set.logoAssetId != null) " and the logo" else "")
        val (logoBytes, ads) = coroutineScope {
            val logoJob = async { set.logoAssetId?.let { id -> checked(timedFile("logo", id, ad = false)) } }
            val adJobs = merged.map { a -> async { AdInput(a.placement, checked(timedFile("${a.placement.wire} ad", a.assetId!!, ad = true)), a.enabled, a.schedule) } }
            logoJob.await() to adJobs.awaitAll()
        }
        // Decode off the render thread. A logo that cannot be decoded fails the whole apply (nothing half-applied).
        val logo = withContext(cpu) {
            logoBytes?.let { b ->
                val bmp = try { decodeLogo(b) } catch (e: OutOfMemoryError) { throw KitFail("too large") } ?: throw KitFail("picture unreadable")
                LogoOverlay(bmp, onRight = kit.logoPosition == "right", heightPx = kit.logoSize)
            }
        }
        val shrink = kit.adsShrinkPicture
        val clock = ClockSettings.fromKit(kit).normalized()
        val content = KitContent(kit.id, logo, ads, shrink, clock, adClockStartMs)
        log("kit apply: preparing the pictures")
        val prepared = try {
            withContext(cpu) { target.prepare(content) }
        } catch (e: CancellationException) { discardLogo(logo); throw e } catch (e: Throwable) {
            discardLogo(logo); throw KitFail(if (e is OutOfMemoryError) "too large" else "could not apply")
        }
        var swapped = false
        try {
            if (prepared.unreadable.isNotEmpty()) throw KitFail("picture unreadable")
            guard()?.let { throw KitFail(it) }
            swapOnRender(target, prepared, content)
            swapped = true
        } finally {
            if (!swapped) { prepared.discard(); discardLogo(logo) }
        }
        prepared.notes.forEach { log("kit apply: note: ${it.take(120)}") }
        log("kit apply: on air (${ads.size} ads, logo ${if (logo != null) "yes" else "no"})")
        return KitOutcome.Applied(kit, kits, logo, logoBytes, ads, merged.associate { it.placement to it.source }, shrink, clock, prepared.noWindow)
    }

    /** Posts the swap to the render thread and waits for it (bounded). A task that did not start in time is cancelled, so it can never change the picture after we reported failure. */
    private suspend fun swapOnRender(target: KitTarget, prepared: app.streambird.android.media.PreparedKitHandle, content: KitContent) {
        val state = AtomicInteger(PENDING)
        val done = CompletableDeferred<Boolean>()
        try {
            target.renderExecutor.execute {
                if (!state.compareAndSet(PENDING, RUNNING)) return@execute
                done.complete(try { target.swap(prepared, content) } catch (t: Throwable) { false })
            }
        } catch (t: Throwable) { throw KitFail("could not apply") }
        val ok = withTimeoutOrNull(swapTimeoutMs) { done.await() }
            ?: if (state.compareAndSet(PENDING, ABANDONED)) { log("kit apply: the render thread did not take the swap in time"); throw KitFail("could not apply") } else done.await()
        if (!ok) throw KitFail("could not apply")
    }

    private fun discardLogo(l: LogoOverlay?) { runCatching { l?.bitmap?.recycle() } }

    // ---- downloads ----

    private suspend fun timedFile(what: String, id: String, ad: Boolean): ByteArray =
        try { timed(what) { source.file(id) } } catch (e: ApiException) { throw KitFail(reasonOf(e, ad)) } catch (e: java.io.IOException) { throw KitFail("download failed") }

    /** One attempt chain (first try + one retry on a temporary error) limited to [downloadTimeoutMs] in total. */
    private suspend fun <T : Any> timed(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(downloadTimeoutMs) { retryOnce(what, block) } ?: run { log("kit apply: $what timed out"); throw KitFail("download failed") }

    private suspend fun <T> retryOnce(what: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (!isTransientAdError(t)) throw t
        log("kit apply: $what failed (${(t as? ApiException)?.status ?: t.javaClass.simpleName}), trying once more")
        delay(retryDelayMs)
        block()
    }

    /** The file must be a picture we can read and not bigger than the caps. */
    private fun checked(b: ByteArray): ByteArray {
        val info = sniffImage(b) ?: throw KitFail("picture unreadable")
        if (info.width > MAX_AD_SIDE || info.height > MAX_AD_SIDE) throw KitFail("too large")
        return b
    }

    private fun reasonOf(e: ApiException, ad: Boolean): String = when {
        e.message.contains("too large", ignoreCase = true) || e.status == 413 -> "too large"
        e.status == 402 || e.status == 403 || e.message.contains("not included in this account's plan", ignoreCase = true) -> if (ad) "plan does not include ads" else "plan does not include brand kits"
        else -> "download failed"
    }

    companion object {
        const val DOWNLOAD_TIMEOUT_MS = 20_000L
        const val SWAP_TIMEOUT_MS = 5_000L
        private const val PENDING = 0; private const val RUNNING = 1; private const val ABANDONED = 2
    }
}
