package app.streambird.android.clock

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two strings of the clock. [date] is null when the date is switched off. */
data class ClockText(val date: String?, val time: String) {
    /** The single line the overlay shows: date, two spaces, time. */
    val line: String get() = if (date == null) time else "$date  $time"
}

/**
 * Zone for the clock: the IANA id when it is valid, else (null, blank, unknown, malformed) [device]. An unknown zone never stops the stream or the
 * clock; it shows the phone's time.
 */
fun resolveClockZone(id: String?, device: ZoneId = ZoneId.systemDefault()): ZoneId {
    val z = Clock.cleanZone(id) ?: return device
    return try { ZoneId.of(z) } catch (e: DateTimeException) { device }
}

/**
 * Formats [instant] for the overlay. Pure and JVM-tested.
 *
 * Patterns are fixed so the look matches the website studio ("Wed 18 Nov", "3:04 PM" / "15:04"); the LOCALE only supplies the names of weekday and
 * month and the digits. The app passes `Locale.getDefault()` (the phone's language). The AM/PM marker is upper-cased because some locales (en-IN)
 * write "pm".
 */
fun formatClock(
    instant: Instant,
    zoneId: String?,
    hours: ClockHours,
    showDate: Boolean,
    showSeconds: Boolean,
    locale: Locale,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): ClockText {
    val zdt = instant.atZone(resolveClockZone(zoneId, deviceZone))
    val timePattern = when (hours) {
        ClockHours.H12 -> if (showSeconds) "h:mm:ss a" else "h:mm a"
        ClockHours.H24 -> if (showSeconds) "HH:mm:ss" else "HH:mm"
    }
    var time = DateTimeFormatter.ofPattern(timePattern, locale).format(zdt)
    if (hours == ClockHours.H12) time = time.uppercase(locale)
    val date = if (showDate) DateTimeFormatter.ofPattern("EEE d MMM", locale).format(zdt) else null
    return ClockText(date, time)
}

/** [formatClock] for a [ClockSettings]. */
fun formatClock(instant: Instant, c: ClockSettings, locale: Locale, deviceZone: ZoneId = ZoneId.systemDefault()): ClockText =
    formatClock(instant, c.timezone, c.hours, c.showDate, c.showSeconds, locale, deviceZone)

/**
 * The step of the displayed text: the epoch second while seconds show, else the epoch minute (every real zone offset is a whole number of minutes
 * today). The compositor re-renders the overlay only when this changes, so at most once per second and, without seconds, once per minute.
 */
fun clockBucket(epochMs: Long, showSeconds: Boolean): Long = Math.floorDiv(epochMs, if (showSeconds) 1000L else 60_000L)
