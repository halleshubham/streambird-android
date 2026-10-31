package app.streambird.android.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagTest {
    private fun tmp() = File.createTempFile("ring", ".log").apply { delete(); deleteOnExit() }

    @Test fun `ring log keeps the last 200 lines and survives a new instance (process restart)`() {
        val f = tmp()
        val r = RingLog(f, 200)
        for (i in 1..1000) r.append("event $i")
        val lines = r.lines()
        assertEquals(200, lines.size); assertEquals("event 1000", lines.last()); assertEquals("event 801", lines.first())
        assertTrue(f.readLines().size <= 400)                      // trimmed on disk too
        val again = RingLog(f, 200)                                 // "next app start"
        assertEquals("event 1000", again.lines().last())
        again.append("after restart")
        assertEquals("after restart", again.lines().last())
    }

    @Test fun `ring log flattens newlines and never throws on an unusable path`() {
        val f = tmp(); val r = RingLog(f, 10)
        r.append("a\nb\r\nc")
        assertEquals(listOf("a b  c"), r.lines())
        RingLog(File("/proc/definitely/not/writable/x.log"), 10).append("x")   // swallowed
    }

    @Test fun `trim keeps head and tail of an oversized trace`() {
        val big = "H".repeat(10) + "x".repeat(200_000) + "TAIL"
        val t = CrashReport.trim(big)
        assertTrue(t.length < CrashReport.MAX_BYTES + 100); assertTrue(t.startsWith("HHHHHHHHHH")); assertTrue(t.endsWith("TAIL")); assertTrue(t.contains("[trimmed"))
        assertEquals("short", CrashReport.trim("short"))
    }

    @Test fun `report has version, device, reason, events and trace within 64 KB`() {
        val r = CrashReport.build(
            CrashReport.Input("0.2.2-brand (12)", "Xiaomi 24115RA8EI (Android 15, SDK 35)", 1_761_900_000_000, "CRASH_NATIVE", "signal 11 (SIGSEGV)", "#00 pc 0x6b3ffc libjingle_peerconnection_so.so", List(200) { "e$it" }),
            nowMs = 1_761_900_100_000,
        )
        assertTrue(r.contains("app: 0.2.2-brand (12)")); assertTrue(r.contains("reason: CRASH_NATIVE")); assertTrue(r.contains("description: signal 11"))
        assertTrue(r.contains("last 200 events")); assertTrue(r.contains("e199")); assertTrue(r.contains("#00 pc 0x6b3ffc"))
        val huge = CrashReport.build(CrashReport.Input("v", "d", null, "ANR", null, "t".repeat(500_000), listOf("only event")))
        assertTrue(huge.length <= CrashReport.MAX_BYTES + 100); assertTrue(huge.contains("only event"))
        assertTrue(CrashReport.build(CrashReport.Input("v", "d", null, "CRASH", null, null, emptyList())).contains("(none available)"))
    }

    @Test fun `which exit reasons are crashes`() {
        assertTrue(CrashReport.isCrashReason(5)); assertTrue(CrashReport.isCrashReason(6)); assertTrue(CrashReport.isCrashReason(7))
        assertFalse(CrashReport.isCrashReason(1)); assertFalse(CrashReport.isCrashReason(10)); assertFalse(CrashReport.isCrashReason(11))
        assertEquals("CRASH_NATIVE", CrashReport.reasonName(6))
    }
}
