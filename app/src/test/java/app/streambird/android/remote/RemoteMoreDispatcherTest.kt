package app.streambird.android.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0.15: a command that finishes later (kit.apply) is acked later, once, and the rest of the batch is not held up. */
class RemoteMoreDispatcherTest {
    private var now = 10_000_000L
    private val controls = RemoteApplierTest.FakeControls()
    private val host = HostChangeTracker { now }
    private val logs = mutableListOf<String>()
    private val d = RemoteDispatcher(RemoteApplier(controls, host), { true }, RateLimiter(20, 10_000) { now }, { now }, { logs.add(it) })
    private val late = mutableListOf<Pair<RemoteAck, Boolean>>()
    private val kit = "0b6f6f0e-3c43-4a76-9d3b-6a3f3d6a9c11"

    init { d.onLateAck = { a, ok -> late.add(a to ok) } }

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
    private fun cmd(seq: Int, type: String, payload: String) = """{"seq":$seq,"id":"id$seq","type":"$type","payload":$payload,"at":"${iso(now)}"}"""
    private fun batch(vararg c: String) = RemoteParser.parseBatch("""{"commands":[${c.joinToString(",")}],"now":"${iso(now)}"}""")

    @Test fun `a started kit change gets no ack now, later commands in the batch are applied, the late ack follows once`() {
        val r = d.process(batch(cmd(1, "kit.apply", """{"kitId":"$kit"}"""), cmd(2, "ads.shrink", """{"on":false}""")))
        assertEquals(listOf("id2"), r.acks.map { it.id })                 // only the shrink is acked now
        assertEquals(listOf("kit $kit", "shrink false"), controls.calls)
        assertTrue(late.isEmpty()); assertEquals(2L, d.lastSeq)
        assertTrue(logs.any { it == "remote: kit.apply seq=1 -> started" })
        controls.kitLate!!.invoke(ApplyResult.OK)
        assertEquals(1, late.size); assertEquals("id1", late[0].first.id); assertTrue(late[0].first.ok); assertTrue(late[0].second)
        assertTrue(logs.any { it == "remote: kit.apply seq=1 -> ok" })
    }

    @Test fun `a failed kit change is acked with its short reason and never counts as applied`() {
        d.process(batch(cmd(1, "kit.apply", """{"kitId":"$kit"}""")))
        controls.kitLate!!.invoke(ApplyResult.err("download failed"))
        assertEquals("download failed", late[0].first.error); assertFalse(late[0].first.ok); assertFalse(late[0].second)
        assertTrue(logs.any { it == "remote: kit.apply seq=1 -> refused (download failed)" })
    }

    @Test fun `an immediate refusal is a normal ack and nothing is pending`() {
        controls.kitResult = ApplyResult.err("busy")
        val r = d.process(batch(cmd(1, "kit.apply", """{"kitId":"$kit"}""")))
        assertEquals("busy", r.acks.single().error); assertTrue(late.isEmpty())
    }

    @Test fun `a repeated answer does not start the kit change twice and logs carry no names or ids`() {
        d.process(batch(cmd(1, "kit.apply", """{"kitId":"$kit"}""")))
        d.process(batch(cmd(1, "kit.apply", """{"kitId":"$kit"}""")))
        assertEquals(1, controls.calls.count { it.startsWith("kit ") })
        assertTrue(logs.none { it.contains(kit) })
    }

    @Test fun `banner text from a command is never logged`() {
        d.process(batch(cmd(1, "lowerThird.set", """{"name":"Secret Person","title":"Secret Title","show":true}""")))
        assertTrue(controls.bannerNow.visible)
        assertTrue(logs.isNotEmpty()); assertTrue(logs.none { it.contains("Secret") })
        assertTrue(logs.any { it == "remote: lowerThird.set seq=1 -> ok" })
    }
}
