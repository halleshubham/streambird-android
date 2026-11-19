package app.streambird.android.remote

import app.streambird.android.ads.AdPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** seq order, dedupe, stale drop, rate limit, live check and the ack texts, with a fake clock. */
class RemoteDispatcherTest {
    private var now = 10_000_000L
    private val controls = RemoteApplierTest.FakeControls()
    private val host = HostChangeTracker { now }
    private val logs = mutableListOf<String>()
    private var live = true
    private val d = RemoteDispatcher(RemoteApplier(controls, host), { live }, RateLimiter(20, 10_000) { now }, { now }, { logs.add(it) })

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).toString()
    private fun cmd(seq: Int, type: String = "ads.shrink", payload: String = """{"on":true}""", id: String = "id$seq", at: Long = now) =
        """{"seq":$seq,"id":"$id","type":"$type","payload":$payload,"at":"${iso(at)}"}"""
    private fun batch(vararg c: String, serverNow: Long? = now) =
        RemoteParser.parseBatch("""{"commands":[${c.joinToString(",")}]${serverNow?.let { ""","now":"${iso(it)}"""" } ?: ""}}""")

    @Test fun `commands are applied in seq order whatever order they arrive in`() {
        val r = d.process(batch(
            cmd(3, "ads.shrink", """{"on":false}"""), cmd(1, "ads.shrink", """{"on":true}"""), cmd(2, "ticker.set", """{"text":"a","on":true}"""),
        ))
        assertEquals(listOf("shrink true", "ticker 'a'", "shrink false"), controls.calls)
        assertEquals(listOf("id1", "id2", "id3"), r.acks.map { it.id })
        assertTrue(r.acks.all { it.ok }); assertTrue(r.appliedAny)
        assertEquals(3L, d.lastSeq)
    }

    @Test fun `a repeated answer never re-applies by seq or by id`() {
        d.process(batch(cmd(1), cmd(2)))
        val again = d.process(batch(cmd(1), cmd(2)))
        assertTrue(again.acks.isEmpty()); assertFalse(again.appliedAny)
        val sameIdNewSeq = d.process(batch(cmd(5, id = "id1")))        // an id seen before
        assertTrue(sameIdNewSeq.acks.isEmpty())
        assertEquals(2, controls.calls.size)
        assertEquals(5L, d.lastSeq)                                      // but the sequence moved on
    }

    @Test fun `a gap in seq is fine and the last seq follows the highest one`() {
        d.process(batch(cmd(4), cmd(9)))
        assertEquals(9L, d.lastSeq)
        d.process(batch(cmd(7, id = "late")))                           // older than lastSeq: skipped
        assertEquals(2, controls.calls.size)
    }

    @Test fun `a command older than 2 minutes is dropped and acked with an error without applying`() {
        val r = d.process(batch(cmd(1, at = now - 120_001), cmd(2, at = now - 119_000)))
        assertEquals(listOf(false, true), r.acks.map { it.ok })
        assertEquals("command too old", r.acks[0].error)
        assertEquals(1, controls.calls.size)
    }

    @Test fun `staleness is judged against the server clock when it sends one even if the phone clock is wrong`() {
        val serverNow = now + 3_600_000L                                // the phone's clock is an hour behind
        val r = d.process(batch(cmd(1, at = serverNow - 1_000), serverNow = serverNow))
        assertTrue(r.acks[0].ok)
    }

    @Test fun `no live stream means every command is refused`() {
        live = false
        val r = d.process(batch(cmd(1), cmd(2, "ticker.set", """{"text":"a","on":true}""")))
        assertEquals(listOf("stream not live", "stream not live"), r.acks.map { it.error })
        assertTrue(controls.calls.isEmpty()); assertFalse(r.appliedAny)
    }

    @Test fun `rate limit lets 20 commands through per 10 seconds and acks the rest as rate limited`() {
        val r = d.process(batch(*(1..25).map { cmd(it) }.toTypedArray()))
        assertEquals(20, r.acks.count { it.ok })
        assertEquals(List(5) { "rate limited" }, r.acks.filter { !it.ok }.map { it.error })
        assertEquals(20, controls.calls.size)
        now += 10_000
        assertTrue(d.process(batch(cmd(26))).acks.single().ok)           // the window moved on
    }

    @Test fun `rejected and unsupported commands are acked with the reason and the sequence moves on`() {
        val r = d.process(batch(cmd(1, "stream.stop", "{}"), cmd(2, "logo.set", """{"size":10}"""), cmd(3, "ads.manual", """{"placement":"x","mode":"shown"}"""), cmd(4)))
        assertEquals(listOf("command not allowed", "not supported on the phone app", "unknown placement", null), r.acks.map { it.error })
        assertEquals(listOf(false, false, false, true), r.acks.map { it.ok })
        assertEquals(4L, d.lastSeq)
        assertEquals(listOf("shrink true"), controls.calls)
    }

    @Test fun `a command with no readable id is not acked but a readable seq still advances`() {
        val r = d.process(RemoteParser.parseBatch("""{"commands":[{"seq":8,"id":"bad id","type":"ads.shrink","payload":{"on":true},"at":"${iso(now)}"},"junk"]}"""))
        assertTrue(r.acks.isEmpty()); assertEquals(8L, d.lastSeq); assertTrue(controls.calls.isEmpty())
    }

    @Test fun `conflict with a host change is acked as host changed it just now`() {
        host.mark(RemoteKeys.adManual(AdPlacement.LOWER_THIRD))
        now += 1_000
        val r = d.process(batch(cmd(1, "ads.manual", """{"placement":"lower_third","mode":"shown"}""", at = now)))
        assertEquals("host changed it just now", r.acks.single().error)
        assertTrue(controls.calls.isEmpty())
    }

    @Test fun `acks carry only known fields and never any text content of the command`() {
        val r = d.process(batch(cmd(1, "ticker.set", """{"text":"SECRET HEADLINE","on":true}"""), cmd(2, "stream.stop", "{}")))
        val json = r.acks.map { it.toJson().toString() }
        assertEquals(setOf("id", "ok", "appliedAt"), (r.acks[0].toJson().keys))
        assertEquals(setOf("id", "ok", "error", "appliedAt"), (r.acks[1].toJson().keys))
        assertEquals("""{"id":"id1","ok":true,"appliedAt":"${iso(now)}"}""", json[0])
        assertTrue(logs.joinToString("\n"), logs.none { it.contains("SECRET") })
        assertTrue(logs.any { it.startsWith("remote: ticker.set seq=1 -> ok") })
        assertTrue(logs.any { it.contains("remote: rejected seq=2") })
    }

    @Test fun `the error text is capped at 120 characters`() {
        assertEquals(120, RemoteAck("a", false, "e".repeat(500), 0L).toJson()["error"]!!.toString().trim('"').length)
    }
}
