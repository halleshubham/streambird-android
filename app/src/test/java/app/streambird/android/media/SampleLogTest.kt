package app.streambird.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLogTest {
    private val stats = EncoderStats(720, 1280, 29.6, 123L, 0.0, "bandwidth", "HWEncoder", null, 10, 2, 3, 1, 0, 41.5, 1.2, 2e6, 9.5)
    private val dev = DeviceReading(81, 36.4, false, "LIGHT", "wifi")

    @Test fun `csv has header comments, columns and one row per sample`() {
        val log = SampleLog(listOf("device" to "Pixel, test", "tier" to "720p"))
        log.add(LogRow(1_700_000_000_000, 12, stats, 2987.4, true, dev))
        log.add(LogRow(1_700_000_002_000, 14, null, null, false, dev.copy(network = "cellular"), event = "network wifi -> cellular"))
        val lines = log.toCsv().trim().lines()
        assertEquals("# StreamBird Android spike log", lines[0])
        assertEquals("# device: Pixel, test", lines[1])
        val header = lines.first { it.startsWith("timestamp_utc") }
        assertEquals(SampleLog.COLUMNS.joinToString(","), header)
        val row = lines[lines.indexOf(header) + 1].split(",")
        assertEquals("2023-11-14T22:13:20Z", row[0])
        assertEquals("12", row[1]); assertEquals("720", row[2]); assertEquals("1280", row[3])
        assertEquals("29.6", row[4]); assertEquals("2987", row[5]); assertEquals("bandwidth", row[6]); assertEquals("WARN", row[7])
        assertEquals("81", row[15]); assertEquals("36.4", row[16]); assertEquals("LIGHT", row[17]); assertEquals("wifi", row[18])
        val ev = lines.last().split(",")
        assertEquals(SampleLog.COLUMNS.size, ev.size)
        assertEquals("cellular", ev[18]); assertEquals("network wifi -> cellular", ev[19]); assertEquals("NA", ev[2])
    }

    @Test fun `csv escaping`() {
        assertEquals("plain", SampleLog.csv("plain"))
        assertEquals("\"a,b\"", SampleLog.csv("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", SampleLog.csv("say \"hi\""))
        assertTrue(SampleLog(emptyList()).toCsv().contains("timestamp_utc"))
    }

    @Test fun `event rows keep device readings in their own columns even if empty runs are collapsed`() {
        val log = SampleLog(emptyList())
        log.add(LogRow(1_700_000_000_000, 0, null, null, false, DeviceReading(40, 33.0, false, "NONE", "wifi"), event = "LIVE"))
        val cells = log.toCsv().trim().lines().last().split(",")
        assertEquals(SampleLog.COLUMNS.size, cells.size)
        assertEquals("NA", cells[SampleLog.COLUMNS.indexOf("width")])
        assertEquals("40", cells[SampleLog.COLUMNS.indexOf("battery_pct")])
        assertEquals("NONE", cells[SampleLog.COLUMNS.indexOf("thermal")])
        assertEquals("LIVE", cells[SampleLog.COLUMNS.indexOf("event")])
        // no run of two commas anywhere, so a collapsing viewer cannot shift columns
        assertTrue(!log.toCsv().trim().lines().last().contains(",,"))
    }
}
