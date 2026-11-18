package app.streambird.android.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The pure text of the clock: patterns, midnight / noon, seconds, DST, a half-hour zone and the fallback for a bad zone. */
class ClockFormatTest {
    private val us = Locale.US
    private val utc = ZoneId.of("UTC")
    private fun at(s: String) = Instant.parse(s)
    private fun fmt(i: String, zone: String? = "UTC", h: ClockHours = ClockHours.H12, date: Boolean = true, sec: Boolean = false, device: ZoneId = utc) =
        formatClock(at(i), zone, h, date, sec, us, device)

    @Test fun `12 hour shows date and time like the website`() {
        val t = fmt("2026-11-18T15:04:09Z")
        assertEquals("Wed 18 Nov", t.date)
        assertEquals("3:04 PM", t.time)
        assertEquals("Wed 18 Nov  3:04 PM", t.line)
    }

    @Test fun `24 hour pads the hour`() {
        assertEquals("15:04", fmt("2026-11-18T15:04:09Z", h = ClockHours.H24).time)
        assertEquals("07:05", fmt("2026-11-18T07:05:09Z", h = ClockHours.H24).time)
    }

    @Test fun `midnight and noon in both formats`() {
        assertEquals("12:00 AM", fmt("2026-11-18T00:00:00Z").time)
        assertEquals("12:00 PM", fmt("2026-11-18T12:00:00Z").time)
        assertEquals("12:59 AM", fmt("2026-11-18T00:59:00Z").time)
        assertEquals("00:00", fmt("2026-11-18T00:00:00Z", h = ClockHours.H24).time)
        assertEquals("12:00", fmt("2026-11-18T12:00:00Z", h = ClockHours.H24).time)
        assertEquals("23:59", fmt("2026-11-18T23:59:59Z", h = ClockHours.H24).time)
    }

    @Test fun `seconds are optional`() {
        assertEquals("3:04:09 PM", fmt("2026-11-18T15:04:09Z", sec = true).time)
        assertEquals("15:04:09", fmt("2026-11-18T15:04:09Z", h = ClockHours.H24, sec = true).time)
        assertEquals("3:04 PM", fmt("2026-11-18T15:04:09Z", sec = false).time)
    }

    @Test fun `date can be switched off`() {
        val t = fmt("2026-11-18T15:04:09Z", date = false)
        assertNull(t.date)
        assertEquals("3:04 PM", t.line)
    }

    @Test fun `single digit day has no leading zero`() {
        assertEquals("Fri 5 Jun", fmt("2026-06-05T10:00:00Z").date)
    }

    @Test fun `Asia Kolkata is plus five thirty and can change the date`() {
        val t = fmt("2026-11-18T22:00:00Z", zone = "Asia/Kolkata")
        assertEquals("3:30 AM", t.time)
        assertEquals("Thu 19 Nov", t.date)
        assertEquals("03:30", fmt("2026-11-18T22:00:00Z", zone = "Asia/Kolkata", h = ClockHours.H24).time)
    }

    @Test fun `daylight saving is applied by the zone`() {
        // New York: EST (UTC-5) in winter, EDT (UTC-4) in summer; the clocks change on 8 Mar 2026 at 02:00 local.
        assertEquals("7:00 AM", fmt("2026-01-15T12:00:00Z", zone = "America/New_York").time)
        assertEquals("8:00 AM", fmt("2026-07-15T12:00:00Z", zone = "America/New_York").time)
        assertEquals("1:59 AM", fmt("2026-03-08T06:59:00Z", zone = "America/New_York").time)
        assertEquals("3:00 AM", fmt("2026-03-08T07:00:00Z", zone = "America/New_York").time)
    }

    @Test fun `null blank and invalid zones use the device zone`() {
        val device = ZoneId.of("Asia/Kolkata")
        val i = "2026-11-18T10:00:00Z"
        assertEquals("3:30 PM", fmt(i, zone = null, device = device).time)
        assertEquals("3:30 PM", fmt(i, zone = "   ", device = device).time)
        assertEquals("3:30 PM", fmt(i, zone = "Mars/Olympus_Mons", device = device).time)
        assertEquals("3:30 PM", fmt(i, zone = "not a zone!!", device = device).time)
        assertEquals(device, resolveClockZone("garbage", device))
        assertEquals(ZoneId.of("Europe/Paris"), resolveClockZone(" Europe/Paris ", device))
    }

    @Test fun `AM PM marker is upper case even where the locale writes lower case`() {
        val t = formatClock(at("2026-11-18T15:04:00Z"), "UTC", ClockHours.H12, true, false, Locale.forLanguageTag("en-IN"), utc)
        assertEquals("3:04 PM", t.time)
    }

    @Test fun `settings overload agrees`() {
        val c = ClockSettings(enabled = true, hours = ClockHours.H24, showDate = false, showSeconds = true, timezone = "Asia/Kolkata")
        assertEquals("03:30:00", formatClock(at("2026-11-18T22:00:00Z"), c, us, utc).line)
    }

    @Test fun `bucket changes once per second with seconds and once per minute without`() {
        val base = 1_700_000_000_000L
        assertEquals(clockBucket(base, true), clockBucket(base + 999 - base % 1000, true))
        assertTrue(clockBucket(base, true) != clockBucket(base + 1000, true))
        val minuteStart = base - Math.floorMod(base, 60_000L)
        assertEquals(clockBucket(minuteStart, false), clockBucket(minuteStart + 59_999, false))
        assertTrue(clockBucket(minuteStart, false) != clockBucket(minuteStart + 60_000, false))
        assertEquals(clockBucket(minuteStart, false), clockBucket(minuteStart + 30_000, false))
    }

    @Test fun `bucket handles instants before 1970`() {
        assertEquals(-1L, clockBucket(-1L, true))
        assertEquals(-1L, clockBucket(-1L, false))
    }
}
