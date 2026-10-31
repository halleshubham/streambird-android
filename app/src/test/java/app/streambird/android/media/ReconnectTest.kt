package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test fun `backoff schedule 1 2 4 8 16 then 30 s`() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), (1..8).map { backoffMs(it) })
    }

    @Test fun `stall, ICE failure and manual drop reconnect immediately, ICE disconnected and network change wait a grace`() {
        fun first(t: Trigger) = ReconnectMachine().trigger(t, 0) as Step.Attempt
        assertEquals(0L, first(Trigger.Stall).afterMs)
        assertEquals(0L, first(Trigger.IceFailed("FAILED")).afterMs)
        assertEquals(0L, first(Trigger.Manual).afterMs)
        assertEquals(4000L, first(Trigger.IceDisconnected).afterMs); assertTrue(first(Trigger.IceDisconnected).onlyIfStalled)
        assertEquals(3000L, first(Trigger.NetworkChanged("cellular", "wifi")).afterMs); assertTrue(first(Trigger.NetworkChanged("a", "b")).onlyIfStalled)
        assertFalse(first(Trigger.Stall).onlyIfStalled)
    }

    @Test fun `failed attempts back off and then give up after five minutes`() {
        val m = ReconnectMachine()
        m.trigger(Trigger.Stall, 0)
        m.attemptStarted()
        var step = m.attemptFailed(1_000) as Step.Attempt
        assertEquals(1000L, step.afterMs); assertEquals(2, step.number)
        m.attemptStarted(); step = m.attemptFailed(3_000) as Step.Attempt
        assertEquals(2000L, step.afterMs)
        m.attemptStarted(); assertEquals(4000L, (m.attemptFailed(7_000) as Step.Attempt).afterMs)
        repeat(6) { m.attemptStarted(); m.attemptFailed(20_000L + it) }
        m.attemptStarted()
        assertEquals(Step.GiveUp, m.attemptFailed(GIVE_UP_AFTER_MS + 1))
        assertEquals(ConnPhase.GAVE_UP, m.phase)
    }

    @Test fun `success returns the episode length and goes back to OK`() {
        val m = ReconnectMachine()
        m.trigger(Trigger.Stall, 10_000); m.attemptStarted()
        assertEquals(12.5, m.attemptSucceeded(22_500), 0.001)
        assertEquals(ConnPhase.OK, m.phase)
    }

    @Test fun `network lost waits, the next network starts a fast attempt`() {
        val m = ReconnectMachine()
        assertEquals(Step.None, m.trigger(Trigger.NetworkLost, 0)); assertEquals(ConnPhase.WAITING_NETWORK, m.phase)
        assertEquals(Step.None, m.trigger(Trigger.Stall, 100))                       // nothing to do offline
        val s = m.trigger(Trigger.NetworkChanged("none", "wifi"), 5_000) as Step.Attempt
        assertEquals(1000L, s.afterMs); assertFalse(s.onlyIfStalled); assertEquals(ConnPhase.RECONNECTING, m.phase)
    }

    @Test fun `a network change during recovery replaces the attempt and restarts the fast schedule`() {
        val m = ReconnectMachine()
        m.trigger(Trigger.Stall, 0); m.attemptStarted(); m.attemptFailed(500); m.attemptStarted()
        val s = m.trigger(Trigger.NetworkChanged("wifi", "cellular"), 2_000) as Step.Attempt
        assertEquals(1, s.number); assertEquals(1000L, s.afterMs)
        assertEquals(Step.None, m.trigger(Trigger.Stall, 2_100))                      // already handling it
    }

    @Test fun `ICE that comes back by itself during the grace cancels the reconnect`() {
        val m = ReconnectMachine()
        m.trigger(Trigger.IceDisconnected, 0)
        assertTrue(m.iceRecovered()); assertEquals(ConnPhase.OK, m.phase)
        m.trigger(Trigger.Stall, 1); m.attemptStarted()
        assertFalse(m.iceRecovered())
    }

    // ---- stall detection ----

    private fun sample(t: Double, bytes: Long, rtt: Double? = 101.1, nack: Long = 687, pli: Long = 37, pairRx: Long? = null) =
        FeedbackSample(t, bytes, rtt, nack, pli, pairRx)

    @Test fun `replay of the logged frozen feedback pattern, rtt nack pli unchanged while bytesSent advances`() {
        val d = StallDetector(stallMs = 7_000)
        // healthy before the switch: counters move
        assertFalse(d.add(sample(0.0, 0, rtt = 98.0, nack = 600, pli = 30)))
        assertFalse(d.add(sample(2000.0, 60_000, rtt = 120.5, nack = 640, pli = 33)))
        assertFalse(d.add(sample(4000.0, 120_000, rtt = 101.1, nack = 687, pli = 37)))
        // after the network switch the encoder keeps producing (150-700 kbps) but feedback is frozen at 101.1 / 687 / 37
        assertFalse(d.add(sample(6000.0, 150_000)))
        assertFalse(d.add(sample(8000.0, 200_000)))
        assertFalse(d.add(sample(10000.0, 260_000)))
        assertTrue(d.add(sample(12000.0, 330_000)))                      // 8 s frozen with bytes flowing
        assertTrue(d.add(sample(14000.0, 400_000)))
    }

    @Test fun `no stall while feedback moves, or when we are not sending either`() {
        val d = StallDetector(7_000)
        for (i in 0..10) assertFalse(d.add(sample(i * 2000.0, i * 50_000L, rtt = 90.0 + i)))
        val idle = StallDetector(7_000)
        for (i in 0..10) assertFalse(idle.add(sample(i * 2000.0, 5_000L)))   // bytesSent frozen too: idle encoder, not a stall
    }

    @Test fun `candidate pair receive counter moving keeps it alive even when rtt and nack are constant`() {
        val d = StallDetector(7_000)
        for (i in 0..10) assertFalse(d.add(sample(i * 2000.0, i * 50_000L, pairRx = 1000L + i * 300)))
    }

    @Test fun `advancedSince tells a harmless network change from a dead one`() {
        val d = StallDetector(7_000)
        d.add(sample(0.0, 0, rtt = 50.0)); d.add(sample(2000.0, 10_000, rtt = 55.0))
        assertTrue(d.advancedSince(1500.0)); assertFalse(d.advancedSince(2000.0)); assertFalse(d.advancedSince(2500.0))
        d.reset(); assertFalse(d.advancedSince(0.0))
    }

    // ---- network and hints ----

    @Test fun `network callbacks map to triggers`() {
        val wifi = NetState("net1", "wifi"); val cell = NetState("net2", "cellular")
        assertNull(networkTrigger(wifi, wifi))
        assertEquals(Trigger.NetworkChanged("cellular", "wifi"), networkTrigger(cell, wifi))
        assertEquals(Trigger.NetworkLost, networkTrigger(wifi, NetState(null, "none")))
        assertNull(networkTrigger(NetState(null, "none"), NetState(null, "none")))
        assertEquals(Trigger.NetworkChanged("none", "wifi"), networkTrigger(NetState(null, "none"), wifi))
        assertEquals(Trigger.NetworkChanged("wifi", "wifi"), networkTrigger(wifi, NetState("net9", "wifi")))   // another wifi
        assertEquals("network cellular -> wifi", Trigger.NetworkChanged("cellular", "wifi").label)
    }

    @Test fun `cellular hint`() {
        assertNull(cellularHint("wifi", StreamTier.P720))
        assertEquals("Mobile data detected: a steady 3 Mbps upload is needed for 720p. Try 540p, or use Wi-Fi.", cellularHint("cellular", StreamTier.P720))
        assertTrue(cellularHint("cellular", StreamTier.P540)!!.contains("1 Mbps"))
    }
}
