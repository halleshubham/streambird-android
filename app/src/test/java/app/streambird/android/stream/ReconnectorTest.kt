package app.streambird.android.stream

import app.streambird.android.media.ConnPhase
import app.streambird.android.media.Trigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectorTest {
    private class Host(val scope: TestScope, val results: MutableList<Result<Unit>>) : ReconnectHost {
        val logs = mutableListOf<String>()
        val ui = mutableListOf<ReconnectUi>()
        var calls = 0
        override suspend fun republish() { calls++; results.removeFirstOrNull()?.getOrThrow() }
        override fun log(text: String) { logs.add(text) }
        override fun onUi(ui: ReconnectUi) { this.ui.add(ui) }
        override fun nowMs() = scope.currentTime
    }

    private fun setup(results: List<Result<Unit>> = emptyList()): Triple<TestScope, Host, Reconnector> {
        val scope = TestScope()
        val host = Host(scope, results.toMutableList())
        return Triple(scope, host, Reconnector(scope, host))
    }

    @Test fun `dead transport republishes with 5xx retries on the 1 2 s schedule and reports back online`() {
        val (scope, host, r) = setup(listOf(Result.failure(java.io.IOException("WHIP publish failed (503)")), Result.failure(java.io.IOException("timeout")), Result.success(Unit)))
        r.onTrigger(Trigger.IceFailed("FAILED"))
        scope.runCurrent()
        assertEquals(1, host.calls)                                   // attempt 1 immediately
        scope.advanceTimeBy(999); scope.runCurrent(); assertEquals(1, host.calls)
        scope.advanceTimeBy(2); scope.runCurrent(); assertEquals(2, host.calls)   // after 1 s
        scope.advanceTimeBy(1_998); scope.runCurrent(); assertEquals(2, host.calls)
        scope.advanceTimeBy(2); scope.runCurrent(); assertEquals(3, host.calls)   // after 2 s more
        assertEquals(ConnPhase.OK, r.phase)
        assertTrue(host.logs.any { it == "detected: peer connection FAILED" })
        assertTrue(host.logs.containsAll(listOf("republish attempt 1", "republish attempt 2", "republish attempt 3")))
        assertTrue(host.logs.any { it.startsWith("republished ok after 3.0 s") })
        assertTrue(host.ui.last().backOnlineAtMs > 0); assertEquals(ConnPhase.OK, host.ui.last().phase)
        assertTrue(host.ui.any { it.phase == ConnPhase.RECONNECTING })
    }

    @Test fun `a network change while retrying replaces the pending attempt`() {
        val (scope, host, r) = setup(listOf(Result.failure(java.io.IOException("503")), Result.success(Unit)))
        r.onTrigger(Trigger.Stall); scope.runCurrent()
        assertEquals(1, host.calls)
        scope.advanceTimeBy(500)
        r.onTrigger(Trigger.NetworkChanged("cellular", "wifi"))        // replaces the 1 s retry with a debounced (2 s) fresh attempt
        scope.advanceTimeBy(1_999); scope.runCurrent(); assertEquals(1, host.calls)
        scope.advanceTimeBy(2); scope.runCurrent(); assertEquals(2, host.calls)
        assertEquals(ConnPhase.OK, r.phase)
    }

    @Test fun `gives up after five minutes of failures and never calls end`() {
        val fails = List(100) { Result.failure<Unit>(java.io.IOException("503")) }
        val (scope, host, r) = setup(fails)
        r.onTrigger(Trigger.Stall)
        scope.advanceTimeBy(6 * 60_000L); scope.runCurrent()
        assertEquals(ConnPhase.GAVE_UP, r.phase)
        assertTrue(host.logs.any { it.startsWith("giving up") })
        val calls = host.calls
        scope.advanceTimeBy(60_000); scope.runCurrent(); assertEquals(calls, host.calls)
    }

    @Test fun `network change with flowing feedback does not republish`() {
        val (scope, host, r) = setup()
        r.onStats(stats(0.0, rtt = 50.0)); r.onStats(stats(2000.0, rtt = 60.0))
        r.onTrigger(Trigger.NetworkChanged("wifi", "cellular"))
        r.onStats(stats(4000.0, rtt = 70.0))                         // feedback moved after the change
        scope.advanceTimeBy(3_100); scope.runCurrent()
        assertEquals(0, host.calls)
        assertEquals(ConnPhase.OK, r.phase)
        assertTrue(host.logs.any { it.startsWith("feedback still flowing") })
    }

    @Test fun `network change with frozen feedback republishes after the grace`() {
        val (scope, host, r) = setup(listOf(Result.success(Unit)))
        r.onStats(stats(0.0, rtt = 50.0)); r.onStats(stats(2000.0, rtt = 60.0))
        r.onTrigger(Trigger.NetworkChanged("cellular", "wifi"))
        r.onStats(stats(4000.0, rtt = 60.0, bytes = 100_000)); r.onStats(stats(6000.0, rtt = 60.0, bytes = 200_000))
        scope.advanceTimeBy(3_100); scope.runCurrent()
        assertEquals(1, host.calls)
    }

    @Test fun `the frozen log pattern fed through onStats triggers the stall path`() {
        val (scope, host, r) = setup(listOf(Result.success(Unit)))
        r.onStats(stats(0.0, rtt = 120.0, nack = 640, pli = 33, bytes = 0)); r.onStats(stats(2000.0, rtt = 101.1, nack = 687, pli = 37, bytes = 60_000))
        for (i in 2..6) r.onStats(stats(i * 2000.0, rtt = 101.1, nack = 687, pli = 37, bytes = i * 60_000L))
        scope.runCurrent()
        assertTrue(host.logs.any { it == "detected: no feedback from the server (stall)" })
        assertEquals(1, host.calls)
    }

    @Test fun `ICE disconnected that recovers inside the grace does nothing`() {
        val (scope, host, r) = setup()
        r.onPeerState("DISCONNECTED")
        scope.advanceTimeBy(2_000); r.onPeerState("CONNECTED")
        scope.advanceTimeBy(5_000); scope.runCurrent()
        assertEquals(0, host.calls); assertEquals(ConnPhase.OK, r.phase)
    }

    @Test fun `a network flap does not cancel an attempt that is talking to the server`() {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scope = TestScope()
        val log = mutableListOf<String>()
        var calls = 0
        val host = object : ReconnectHost {
            override suspend fun republish() { calls++; if (calls == 1) { gate.await(); throw java.io.IOException("503") } }
            override fun log(text: String) { log.add(text) }
            override fun onUi(ui: ReconnectUi) {}
            override fun nowMs() = scope.currentTime
        }
        val r = Reconnector(scope, host)
        r.onTrigger(Trigger.Stall); scope.runCurrent()
        assertEquals(1, calls)
        r.onTrigger(Trigger.NetworkChanged("wifi", "cellular")); r.onTrigger(Trigger.NetworkChanged("cellular", "wifi"))
        scope.advanceTimeBy(5_000); scope.runCurrent()
        assertEquals(1, calls)                                          // the running attempt was NOT restarted by the flaps
        gate.complete(Unit); scope.runCurrent()                         // it fails; because the network moved, the retry is debounced (2 s), not the 1 s backoff step
        scope.advanceTimeBy(1_999); scope.runCurrent(); assertEquals(1, calls)
        scope.advanceTimeBy(2); scope.runCurrent(); assertEquals(2, calls)
        assertEquals(ConnPhase.OK, r.phase)
    }

    @Test fun `after giving up, a later detection re-arms the reconnect with a fresh budget`() {
        val scope = TestScope()
        var healthy = false
        val logs = mutableListOf<String>()
        val host = object : ReconnectHost {
            override suspend fun republish() { if (!healthy) throw java.io.IOException("503") }
            override fun log(text: String) { logs.add(text) }
            override fun onUi(ui: ReconnectUi) {}
            override fun nowMs() = scope.currentTime
        }
        val r = Reconnector(scope, host)
        r.onTrigger(Trigger.Stall)
        scope.advanceTimeBy(6 * 60_000L); scope.runCurrent()
        assertEquals(ConnPhase.GAVE_UP, r.phase)
        healthy = true
        r.onPeerState("FAILED")                                         // a later detection starts again
        scope.advanceTimeBy(1_000); scope.runCurrent()
        assertEquals(ConnPhase.OK, r.phase)
        assertTrue(logs.any { it.startsWith("giving up") })
    }

    private fun stats(t: Double, rtt: Double, nack: Long = 1, pli: Long = 1, bytes: Long = 0) = app.streambird.android.media.EncoderStats(
        720, 1280, 30.0, bytes, t, null, "MediaCodec", true, 0, 0, nack, pli, 0, rtt, null, null, null,
    )

}
