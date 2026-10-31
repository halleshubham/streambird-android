package app.streambird.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class EngineLifecycleTest {
    @Test fun `second and concurrent stops are no-ops that wait for the first`() = runBlocking {
        val lc = EngineLifecycle()
        val runs = AtomicInteger(); val finished = AtomicInteger()
        val a = async { lc.stop { runs.incrementAndGet(); delay(150); finished.incrementAndGet() } }
        delay(20)
        val b = async { lc.stop { runs.incrementAndGet() } }          // concurrent: must not run teardown again
        b.await()
        assertEquals(1, finished.get())                                // and it returned only AFTER the first finished
        a.await()
        lc.stop { runs.incrementAndGet() }                             // later call
        assertEquals(1, runs.get())
    }

    @Test fun `teardown continues when the caller is cancelled`() = runBlocking {
        val lc = EngineLifecycle()
        val steps = mutableListOf<String>()
        val job = launch { lc.stop { steps.add("close pc"); delay(200); steps.add("dispose tracks"); steps.add("release camera") } }
        delay(30); job.cancel()                                        // e.g. the 10 s timeout of the old controller
        job.join()
        delay(300)
        assertEquals(listOf("close pc", "dispose tracks", "release camera"), steps)
    }

    @Test fun `republish is refused once stop was called, and stop waits for a running republish`() = runBlocking {
        val lc = EngineLifecycle()
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val rep = async { lc.exclusive { order.add("republish start"); gate.await(); order.add("republish end") } }
        delay(30)
        val stop = async { lc.stop { held -> order.add("teardown(held=$held)") } }
        delay(50)
        assertTrue(lc.isStopped)
        assertEquals(listOf("republish start"), order)                 // teardown waits for the lock
        gate.complete(Unit); rep.await(); stop.await()
        assertEquals(listOf("republish start", "republish end", "teardown(held=true)"), order)
        try { lc.exclusive { order.add("late republish") }; fail() } catch (e: CancellationException) { /* refused */ }
        assertEquals(3, order.size)
    }

    @Test fun `a hung republish cannot block the end forever`() = runBlocking {
        val lc = EngineLifecycle(lockWaitMs = 100)
        val hung = CompletableDeferred<Unit>()
        launch { lc.exclusive { hung.await() } }
        delay(30)
        var held: Boolean? = null
        lc.stop { held = it }
        assertEquals(false, held)
        hung.cancel()
    }
}
