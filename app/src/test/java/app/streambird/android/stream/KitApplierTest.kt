package app.streambird.android.stream

import android.graphics.Bitmap
import app.streambird.android.ads.AdPlacement
import app.streambird.android.ads.AdSchedule
import app.streambird.android.brand.BrandOrientation
import app.streambird.android.data.ApiException
import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitAd
import app.streambird.android.media.KitContent
import app.streambird.android.media.KitTarget
import app.streambird.android.media.PreparedKitHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live kit change orchestration with a fake downloader and a fake render thread: preparation never runs on the render thread, the swap runs ONLY
 * there, any failure leaves the old state, one change at a time.
 */
class KitApplierTest {
    private val renderThreadName = "fake-render"
    private val render = Executors.newSingleThreadExecutor { r -> Thread(r, renderThreadName) }
    @After fun stop() { render.shutdownNow() }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun png(w: Int = 100, h: Int = 50) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13) + "IHDR".toByteArray() + be32(w) + be32(h) + ByteArray(10)

    private fun ad(p: String, asset: String, mode: String = "continuous", show: Int? = null, every: Int? = null) = KitAd(p, asset, true, mode, show, every)
    private val kitId = "0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11"
    private fun kit(id: String = kitId, logo: String? = null, portraitLogo: String? = null, ads: List<KitAd> = emptyList(), portraitAds: List<KitAd> = emptyList(), pos: String = "right", size: Int = 140) = BrandKit(
        id = id, name = "Kit", logoAssetId = logo, logoAssetIdPortrait = portraitLogo, logoPosition = pos, logoSize = size, ads = ads, adsPortrait = portraitAds,
        adsShrinkPicture = false, clockEnabled = true, clockPosition = "bottom_left", clockFormat = "24h", clockStyle = "text", clockAlternate = true,
    )

    private class FakeSource(var kitsFn: suspend () -> List<BrandKit>, var fileFn: suspend (String) -> ByteArray) : KitSource {
        val threads = CopyOnWriteArrayList<String>(); val kitCalls = AtomicInteger(); val fileCalls = CopyOnWriteArrayList<String>()
        override suspend fun kits(): List<BrandKit> { threads.add(Thread.currentThread().name); kitCalls.incrementAndGet(); return kitsFn() }
        override suspend fun file(assetId: String): ByteArray { threads.add(Thread.currentThread().name); fileCalls.add(assetId); return fileFn(assetId) }
    }

    private class Handle(val unread: List<AdPlacement> = emptyList()) : PreparedKitHandle {
        override val unreadable = unread; override val noWindow = emptyList<AdPlacement>(); override val notes = emptyList<String>()
        val discarded = AtomicInteger()
        override fun discard() { discarded.incrementAndGet() }
    }

    private inner class FakeTarget(var swapResult: Boolean = true, var prepareFails: Throwable? = null, var unreadable: List<AdPlacement> = emptyList(), var executor: Executor = render) : KitTarget {
        override val renderExecutor: Executor get() = executor
        val prepareThreads = CopyOnWriteArrayList<String>(); val swapThreads = CopyOnWriteArrayList<String>()
        val handles = CopyOnWriteArrayList<Handle>(); val contents = CopyOnWriteArrayList<KitContent>()
        override fun prepare(content: KitContent): PreparedKitHandle {
            prepareThreads.add(Thread.currentThread().name); contents.add(content)
            prepareFails?.let { throw it }
            return Handle(unreadable).also { handles.add(it) }
        }
        override fun swap(prepared: PreparedKitHandle, content: KitContent): Boolean { swapThreads.add(Thread.currentThread().name); return swapResult }
    }

    private fun applier(src: KitSource, logs: MutableList<String> = mutableListOf(), decode: (ByteArray) -> Bitmap? = { null }) =
        KitApplier(src, decode, { logs.add(it) }, Dispatchers.Default, downloadTimeoutMs = 400, swapTimeoutMs = 400, retryDelayMs = 5)

    private fun run(a: KitApplier, t: KitTarget, id: String = kitId, o: BrandOrientation = BrandOrientation.LANDSCAPE, guard: () -> String? = { null }): KitOutcome {
        assertTrue(a.tryBegin())
        return runBlocking { a.run(id, o, t, 12345L, guard) }
    }

    private fun failed(o: KitOutcome) = (o as KitOutcome.Failed).reason
    private fun applied(o: KitOutcome) = o as? KitOutcome.Applied ?: error("expected Applied, was Failed(${(o as KitOutcome.Failed).reason})")

    // ---- the happy path and where each step runs ----

    @Test fun `preparation never runs on the render thread and the swap runs only on it`() {
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a1"), ad("lower_third", "a2", "interval", 10, 60)))) }, { png() })
        val t = FakeTarget()
        val o = applied(run(applier(src), t))
        assertEquals(listOf(renderThreadName), t.swapThreads)
        assertEquals(1, t.prepareThreads.size); assertNotEquals(renderThreadName, t.prepareThreads[0])
        assertTrue(src.threads.isNotEmpty()); assertTrue(src.threads.none { it == renderThreadName })          // fetch and downloads are off the render thread too
        assertEquals(listOf("a1", "a2").sorted(), src.fileCalls.sorted())
        assertEquals(0, t.handles[0].discarded.get())
        assertEquals(2, o.inputs.size); assertEquals(false, o.shrink); assertTrue(o.clock.enabled)
        assertEquals(AdSchedule.Interval(10, 60), o.inputs.first { it.placement == AdPlacement.LOWER_THIRD }.schedule)
        // what is handed to the swap: the stream's ad clock start, the kit's shrink and clock
        val c = t.contents[0]
        assertEquals(12345L, c.adClockStartMs); assertEquals(false, c.shrink); assertEquals("bottom_left", c.clock.position.wire)
    }

    @Test fun `the render thread is not used at all until everything is prepared`() {
        val gate = CompletableDeferred<Unit>()
        val executed = AtomicInteger()
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "slow")))) }, { gate.await(); png() })
        val t = FakeTarget(executor = Executor { r -> executed.incrementAndGet(); render.execute(r) })
        val a = applier(src).also { it.downloadTimeoutMs = 5_000 }
        assertTrue(a.tryBegin())
        val result = java.util.concurrent.CompletableFuture.supplyAsync { runBlocking { a.run(kitId, BrandOrientation.LANDSCAPE, t, 0L) { null } } }
        Thread.sleep(150)
        assertEquals(0, executed.get())                                                  // still downloading: the render thread was never asked for anything
        assertTrue(t.prepareThreads.isEmpty())
        gate.complete(Unit)
        applied(result.get())
        assertEquals(1, executed.get())                                                  // exactly ONE task for the whole change
    }

    @Test fun `portrait streams use the portrait set and landscape the landscape one`() {
        val k = kit(logo = null, ads = listOf(ad("full_screen", "land")), portraitAds = listOf(ad("lower_third", "port1"), ad("side_third", "port2")))
        val src = FakeSource({ listOf(k) }, { png() })
        val o = applied(run(applier(src), FakeTarget(), o = BrandOrientation.PORTRAIT))
        assertEquals(setOf(AdPlacement.LOWER_THIRD, AdPlacement.SIDE_THIRD), o.inputs.map { it.placement }.toSet())
        assertEquals(listOf("port1", "port2").sorted(), src.fileCalls.sorted())
    }

    @Test fun `a kit with no ads replaces the ad set with nothing`() {
        val o = applied(run(applier(FakeSource({ listOf(kit()) }, { png() })), FakeTarget()))
        assertTrue(o.inputs.isEmpty()); assertNull(o.logo)
    }

    @Test fun `the logo is downloaded decoded and placed like the kit says`() {
        val src = FakeSource({ listOf(kit(logo = "logoLand", portraitLogo = "logoPort", pos = "right", size = 200)) }, { png(512, 512) })
        val bmp = unsafeBitmap()
        val o = applied(run(applier(src, decode = { bmp }), FakeTarget(), o = BrandOrientation.PORTRAIT))
        assertEquals(listOf("logoPort"), src.fileCalls)                     // the portrait logo for a portrait stream
        assertEquals(true, o.logo!!.onRight); assertEquals(200, o.logo!!.heightPx); assertTrue(o.logo!!.bitmap === bmp)
        val land = applied(run(applier(FakeSource({ listOf(kit(logo = "logoLand", portraitLogo = "logoPort", pos = "left", size = 90)) }, { png() }), decode = { bmp }), FakeTarget()))
        assertEquals(false, land.logo!!.onRight)
    }

    /** A Bitmap object without a constructor call (the unit test android.jar has only stubs): enough to be carried around and compared. */
    private fun unsafeBitmap(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), Bitmap::class.java) as Bitmap
    }

    // ---- failures keep the old state ----

    @Test fun `an unknown kit id fails with kit not found and still returns the fetched list`() {
        val o = run(applier(FakeSource({ listOf(kit()) }, { png() })), FakeTarget(), id = "11111111-2222-3333-4444-555555555555")
        assertEquals("kit not found", failed(o))
        assertEquals(1, (o as KitOutcome.Failed).kits!!.size)
    }

    @Test fun `a failed download keeps the old state, discards nothing prepared and never touches the render thread`() {
        val swaps = AtomicInteger()
        val t = FakeTarget(executor = Executor { swaps.incrementAndGet(); render.execute(it) })
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "ok"), ad("lower_third", "gone")))) }, { id -> if (id == "gone") throw ApiException(404, "File not found.") else png() })
        assertEquals("download failed", failed(run(applier(src), t)))
        assertEquals(0, swaps.get()); assertTrue(t.prepareThreads.isEmpty()); assertTrue(t.swapThreads.isEmpty())
    }

    @Test fun `a temporary error is retried once and logged, a 404 is not retried`() {
        val tries = AtomicInteger(); val logs = mutableListOf<String>()
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "flaky")))) }, { if (tries.incrementAndGet() == 1) throw ApiException(503, "busy") else png() })
        applied(run(applier(src, logs), FakeTarget()))
        assertEquals(2, tries.get()); assertTrue(logs.any { it.contains("trying once more") })
        val notFound = AtomicInteger()
        val src2 = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "x")))) }, { notFound.incrementAndGet(); throw ApiException(404, "File not found.") })
        failed(run(applier(src2), FakeTarget()))
        assertEquals(1, notFound.get())
        val twice = AtomicInteger()
        val src3 = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "x")))) }, { twice.incrementAndGet(); throw java.io.IOException("reset") })
        assertEquals("download failed", failed(run(applier(src3), FakeTarget())))
        assertEquals(2, twice.get())                                                                  // one retry, then it gives up
    }

    @Test fun `a download over the time limit fails as download failed`() {
        val logs = mutableListOf<String>()
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "hang")))) }, { delay(60_000); png() })
        assertEquals("download failed", failed(run(applier(src, logs), FakeTarget())))
        assertTrue(logs.any { it.contains("timed out") })
        val src2 = FakeSource({ delay(60_000); listOf(kit()) }, { png() })                             // the kit list itself
        assertEquals("download failed", failed(run(applier(src2), FakeTarget())))
    }

    @Test fun `too large and plan failures map to their short reasons`() {
        fun reasonFor(e: Throwable, onLogo: Boolean = false): String {
            val src = FakeSource({ listOf(if (onLogo) kit(logo = "l") else kit(ads = listOf(ad("full_screen", "a")))) }, { throw e })
            return failed(run(applier(src), FakeTarget()))
        }
        assertEquals("too large", reasonFor(ApiException(-1, "This picture file is too large to use.")))
        assertEquals("plan does not include ads", reasonFor(ApiException(403, "This is not included in this account's plan.")))
        assertEquals("plan does not include ads", reasonFor(ApiException(402, "x")))
        assertEquals("plan does not include brand kits", reasonFor(ApiException(403, "no"), onLogo = true))
        val listFails = FakeSource({ throw ApiException(403, "This is not included in this account's plan.") }, { png() })
        assertEquals("plan does not include brand kits", failed(run(applier(listFails), FakeTarget())))
        assertEquals("download failed", failed(run(applier(FakeSource({ throw ApiException(500, "x") }, { png() })), FakeTarget())))
    }

    @Test fun `a picture that is not an image or is over the side cap is refused`() {
        val notPng = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { "<html>login</html>".toByteArray() })
        assertEquals("picture unreadable", failed(run(applier(notPng), FakeTarget())))
        val huge = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { png(9000, 100) })
        assertEquals("too large", failed(run(applier(huge), FakeTarget())))
        val hugeLogo = FakeSource({ listOf(kit(logo = "l")) }, { png(100, 8193) })
        assertEquals("too large", failed(run(applier(hugeLogo), FakeTarget())))
    }

    @Test fun `a logo that cannot be decoded fails the whole change`() {
        val src = FakeSource({ listOf(kit(logo = "l", ads = listOf(ad("full_screen", "a")))) }, { png() })
        val t = FakeTarget()
        assertEquals("picture unreadable", failed(run(applier(src, decode = { null }), t)))
        assertTrue(t.prepareThreads.isEmpty())
        assertEquals("too large", failed(run(applier(src, decode = { throw OutOfMemoryError() }), FakeTarget())))
    }

    @Test fun `an ad that cannot be decoded fails the change and the prepared layer is discarded`() {
        val t = FakeTarget(unreadable = listOf(AdPlacement.FULL_SCREEN))
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { png() })
        assertEquals("picture unreadable", failed(run(applier(src), t)))
        assertEquals(1, t.handles[0].discarded.get()); assertTrue(t.swapThreads.isEmpty())
    }

    @Test fun `a failing prepare step and an out of memory one both keep the old state`() {
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { png() })
        val t = FakeTarget(prepareFails = IllegalStateException("boom"))
        assertEquals("could not apply", failed(run(applier(src), t)))
        assertTrue(t.swapThreads.isEmpty())
        assertEquals("too large", failed(run(applier(src), FakeTarget(prepareFails = OutOfMemoryError()))))
    }

    @Test fun `the guard is asked right before the swap and a refusal discards what was prepared`() {
        val t = FakeTarget()
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { png() })
        assertEquals("host changed it just now", failed(run(applier(src), t, guard = { "host changed it just now" })))
        assertEquals(1, t.handles[0].discarded.get()); assertTrue(t.swapThreads.isEmpty())
        val t2 = FakeTarget()
        assertEquals("stream not live", failed(run(applier(src), t2, guard = { "stream not live" })))
    }

    @Test fun `a swap that reports failure or a render thread that is gone leaves the old state`() {
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { png() })
        val t = FakeTarget(swapResult = false)
        assertEquals("could not apply", failed(run(applier(src), t)))
        assertEquals(1, t.handles[0].discarded.get())
        val gone = FakeTarget(executor = Executor { throw RejectedExecutionException("gone") })
        assertEquals("could not apply", failed(run(applier(src), gone)))
        assertEquals(1, gone.handles[0].discarded.get()); assertTrue(gone.swapThreads.isEmpty())
    }

    @Test fun `a render thread that never gets to the swap in time cannot change the picture later`() {
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { png() })
        val parked = CopyOnWriteArrayList<Runnable>()
        val t = FakeTarget(executor = Executor { parked.add(it) })                       // accepted but not run: a stalled render thread
        assertEquals("could not apply", failed(run(applier(src), t)))
        parked.forEach { it.run() }                                                       // it wakes up late
        assertTrue(t.swapThreads.isEmpty())                                               // the abandoned task does nothing
        assertEquals(1, t.handles[0].discarded.get())
    }

    // ---- one at a time ----

    @Test fun `only one change runs at a time and the slot is free again afterwards, also after a failure`() {
        val gate = CompletableDeferred<Unit>()
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { gate.await(); png() })
        val a = applier(src).also { it.downloadTimeoutMs = 5_000 }
        assertTrue(a.tryBegin())
        assertFalse(a.tryBegin())                                                          // a second one is refused ("busy" for the ack)
        assertTrue(a.isRunning)
        val fut = java.util.concurrent.CompletableFuture.supplyAsync { runBlocking { a.run(kitId, BrandOrientation.LANDSCAPE, FakeTarget(), 0L) { null } } }
        Thread.sleep(100); assertFalse(a.tryBegin())
        gate.complete(Unit); applied(fut.get())
        assertFalse(a.isRunning)
        assertTrue(a.tryBegin()); a.release()                                              // free again
        assertEquals("kit not found", failed(run(applier(FakeSource({ emptyList() }, { png() })), FakeTarget())))
        val a2 = applier(FakeSource({ throw ApiException(500, "x") }, { png() }))
        failed(run(a2, FakeTarget())); assertFalse(a2.isRunning)
    }

    @Test fun `log lines carry steps and reasons but never ids or names`() {
        val logs = mutableListOf<String>()
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "secret-asset-id")))) }, { png() })
        applied(run(applier(src, logs), FakeTarget()))
        failed(run(applier(FakeSource({ emptyList() }, { png() }), logs), FakeTarget()))
        assertTrue(logs.any { it.contains("on air") }); assertTrue(logs.any { it.contains("failed (kit not found)") })
        assertTrue(logs.none { it.contains(kitId) || it.contains("secret-asset-id") || it.contains("Kit'") })
    }

    @Test fun `work happens off the caller thread`() = runBlocking {
        val caller = Thread.currentThread().name
        val src = FakeSource({ listOf(kit(ads = listOf(ad("full_screen", "a")))) }, { withContext(Dispatchers.IO) { png() } })
        val t = FakeTarget()
        val a = applier(src); assertTrue(a.tryBegin())
        a.run(kitId, BrandOrientation.LANDSCAPE, t, 0L) { null }
        assertNotEquals(caller, t.prepareThreads[0])
    }
}
