package app.streambird.android.clock

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two strings of the clock. [date] is null when the date is switched off. */
data class ClockText(
    val date: String?,
    val time: String,
    /** Compact TEXT style: the time with a TWO-digit 12-hour hour ("02:56 PM"); the same as [time] in 24-hour. */
    val compactTime: String = time,
    /** Compact TEXT style, alternating date: day, short month, year ("18 Nov, 2026"); null when the date is off. */
    val shortDate: String? = null,
) {
    /** The single line the overlay shows: date, two spaces, time. */
    val line: String get() = if (date == null) time else "$date  $time"

    /** Compact TEXT style with date and time at once (alternation off): "Wed 18 Nov · 02:56 PM". */
    val compactLine: String get() = if (date == null) compactTime else "$date · $compactTime"
}

/** What the compact clock shows in a moment: the time, or (while alternating) the date. */
enum class ClockPhase { TIME, DATE }

/** Alternation timing of the compact clock: the time shows [TIME_MS], then the date [DATE_MS], repeating, based on the wall clock; [FADE_MS] cross-fade. */
object ClockCycle {
    const val TIME_MS = 5000L
    const val DATE_MS = 3000L
    const val FADE_MS = 200L
    const val PERIOD_MS = TIME_MS + DATE_MS
}

/** TIME for the first 5 s of every 8 s of the epoch, DATE for the next 3 s. Without alternation or without a date it is always TIME. */
fun clockPhase(epochMillis: Long, alternate: Boolean, showDate: Boolean): ClockPhase {
    if (!alternate || !showDate) return ClockPhase.TIME
    return if (Math.floorMod(epochMillis, ClockCycle.PERIOD_MS) < ClockCycle.TIME_MS) ClockPhase.TIME else ClockPhase.DATE
}

/**
 * Opacity (0..1) of the TIME text at [epochMillis]: 1 without alternation, else it fades in over [ClockCycle.FADE_MS] at the start of its phase and out
 * over the same time at the start of the date phase. The date's opacity is [clockDateAlpha]; the two always add up to 1.
 */
fun clockTimeAlpha(epochMillis: Long, alternate: Boolean, showDate: Boolean): Float {
    if (!alternate || !showDate) return 1f
    val pos = Math.floorMod(epochMillis, ClockCycle.PERIOD_MS)
    return when {
        pos < ClockCycle.FADE_MS -> pos.toFloat() / ClockCycle.FADE_MS                                   // date -> time
        pos < ClockCycle.TIME_MS -> 1f
        pos < ClockCycle.TIME_MS + ClockCycle.FADE_MS -> 1f - (pos - ClockCycle.TIME_MS).toFloat() / ClockCycle.FADE_MS // time -> date
        else -> 0f
    }
}

fun clockDateAlpha(epochMillis: Long, alternate: Boolean, showDate: Boolean): Float =
    if (!alternate || !showDate) 0f else 1f - clockTimeAlpha(epochMillis, alternate, showDate)

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
    // Compact TEXT style: two-digit 12-hour hour, and the alternating date "18 Nov, 2026".
    val compactTime = if (hours == ClockHours.H12) DateTimeFormatter.ofPattern(if (showSeconds) "hh:mm:ss a" else "hh:mm a", locale).format(zdt).uppercase(locale) else time
    val shortDate = if (showDate) DateTimeFormatter.ofPattern("d MMM, yyyy", locale).format(zdt) else null
    return ClockText(date, time, compactTime, shortDate)
}

/** [formatClock] for a [ClockSettings]. */
fun formatClock(instant: Instant, c: ClockSettings, locale: Locale, deviceZone: ZoneId = ZoneId.systemDefault()): ClockText =
    formatClock(instant, c.timezone, c.hours, c.showDate, c.showSeconds, locale, deviceZone)

/**
 * The step of the displayed text: the epoch second while seconds show, else the epoch minute (every real zone offset is a whole number of minutes
 * today). The compositor re-renders the overlay only when this changes, so at most once per second and, without seconds, once per minute.
 */
fun clockBucket(epochMs: Long, showSeconds: Boolean): Long = Math.floorDiv(epochMs, if (showSeconds) 1000L else 60_000L)
