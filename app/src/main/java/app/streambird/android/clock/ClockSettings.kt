package app.streambird.android.clock

import app.streambird.android.data.BrandKit

/** The four corners the clock can sit in (wire values of the server's `clockPosition`). */
enum class ClockPosition(val wire: String, val label: String) {
    TOP_LEFT("top_left", "Top left"), TOP_RIGHT("top_right", "Top right"), BOTTOM_LEFT("bottom_left", "Bottom left"), BOTTOM_RIGHT("bottom_right", "Bottom right");

    val isTop get() = this == TOP_LEFT || this == TOP_RIGHT
    val isLeft get() = this == TOP_LEFT || this == BOTTOM_LEFT

    companion object {
        fun fromWire(s: String?): ClockPosition = entries.firstOrNull { it.wire == s } ?: TOP_RIGHT
    }
}

/** `clockFormat`: 12-hour with AM/PM, or 24-hour. */
enum class ClockHours(val wire: String, val label: String) {
    H12("12h", "12-hour"), H24("24h", "24-hour");

    companion object {
        fun fromWire(s: String?): ClockHours = entries.firstOrNull { it.wire == s } ?: H12
    }
}

/**
 * `clockSize`: font height as a share of the canvas height for the PILL (about 2.2 / 3 / 4.2 percent), or of the SHORT side for the compact TEXT style
 * ([textShare], about 2.0 / 2.8 / 3.8 percent). A kit that does not say gets SMALL (the compact default of the 1.0.13 server).
 */
enum class ClockSize(val wire: String, val label: String, val heightShare: Double, val textShare: Double) {
    SMALL("small", "Small", 0.022, 0.020), MEDIUM("medium", "Medium", 0.03, 0.028), LARGE("large", "Large", 0.042, 0.038);

    companion object {
        fun fromWire(s: String?): ClockSize = entries.firstOrNull { it.wire == s } ?: SMALL
    }
}

/** `clockStyle` (1.0.13): TEXT = plain small text with a soft shadow, no box, under the logo; PILL = the dark rounded pill of 1.0.12. */
enum class ClockStyle(val wire: String, val label: String) {
    TEXT("text", "Compact text"), PILL("pill", "Pill");

    companion object {
        /** Unknown or missing = TEXT (the new server's default). A kit from an OLDER server is handled in [ClockSettings.fromKit], not here. */
        fun fromWire(s: String?): ClockStyle = entries.firstOrNull { it.wire == s } ?: TEXT
    }
}

/**
 * The on-air date and time overlay of a brand kit (or of one stream that overrides the kit). Defaults are the server's, so a kit from a server that does
 * not know the feature reads as "off". [timezone] null = the phone's own timezone.
 */
data class ClockSettings(
    val enabled: Boolean = false,
    val position: ClockPosition = ClockPosition.TOP_RIGHT,
    val hours: ClockHours = ClockHours.H12,
    val showDate: Boolean = true,
    val showSeconds: Boolean = false,
    val timezone: String? = null,
    val size: ClockSize = ClockSize.SMALL,
    /** 1.0.13: look of the overlay. */
    val style: ClockStyle = ClockStyle.TEXT,
    /** 1.0.13: with [showDate], the time and the date take turns in the same spot (5 s time, 3 s date); false = both on one line. */
    val alternate: Boolean = true,
) {
    /** Same settings with the zone cleaned (blank = phone, at most [Clock.TZ_MAX] characters). */
    fun normalized() = copy(timezone = Clock.cleanZone(timezone))

    companion object {
        /** The kit's settings, read leniently: missing / unknown values become the defaults. A null kit is the defaults (off). */
        fun fromKit(k: BrandKit?): ClockSettings = if (k == null) ClockSettings() else ClockSettings(
            enabled = k.clockEnabled,
            position = ClockPosition.fromWire(k.clockPosition),
            hours = ClockHours.fromWire(k.clockFormat),
            showDate = k.clockShowDate,
            showSeconds = k.clockShowSeconds,
            timezone = Clock.cleanZone(k.clockTimezone),
            size = ClockSize.fromWire(k.clockSize),
            // A kit with NEITHER new key comes from the 1.0.12-era server: it must keep the look it had (pill, date and time on one line).
            style = if (k.clockStyle == null && k.clockAlternate == null) ClockStyle.PILL else ClockStyle.fromWire(k.clockStyle),
            alternate = if (k.clockStyle == null && k.clockAlternate == null) false else (k.clockAlternate ?: true),
        )
    }
}

object Clock {
    /** Server limit of `clockTimezone`. */
    const val TZ_MAX = 64

    /** Trimmed, null when blank, at most [TZ_MAX] characters. */
    fun cleanZone(z: String?): String? = z?.trim()?.takeIf { it.isNotEmpty() }?.take(TZ_MAX)

    /** One line for rows and logs, for example "On: top right, 12-hour, with date, medium, Asia/Kolkata". */
    fun describe(c: ClockSettings): String =
        if (!c.enabled) "Off" else buildString {
            append("On: ").append(c.position.label.lowercase()).append(", ").append(c.hours.label.lowercase())
            append(if (c.showDate) ", with date" else ", time only")
            if (c.showSeconds) append(", with seconds")
            append(", ").append(c.size.label.lowercase()).append(", ").append(c.style.label.lowercase())
            if (c.showDate && c.style == ClockStyle.TEXT && c.alternate) append(", alternating")
            append(", ").append(c.timezone ?: "this phone's timezone")
        }
}
