package app.streambird.android.clock

import java.time.ZoneId

/** Timezone choices of the editors: "This phone (automatic)" (null), a short list of common zones, and a searchable list of every zone Java knows. */
object ClockZones {
    const val AUTOMATIC_LABEL = "This phone (automatic)"

    val COMMON = listOf(
        "Asia/Kolkata", "UTC", "Asia/Dubai", "Asia/Singapore", "Asia/Tokyo", "Europe/London", "Europe/Paris", "Europe/Berlin",
        "America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Sao_Paulo", "Africa/Johannesburg",
        "Australia/Sydney", "Pacific/Auckland",
    )

    /** Every region zone id of this JVM / Android, sorted (ids with a leading "SystemV/" style prefix are skipped). */
    fun all(): List<String> = ZoneId.getAvailableZoneIds().filter { Clock.cleanZone(it) == it && !it.startsWith("SystemV/") }.sorted()

    /** Case-insensitive search; "_" and "/" count as spaces, so "new york" and "kolkata" find their zones. Blank query = [all] unchanged. */
    fun filter(query: String, all: List<String>): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return all
        return all.filter { norm(it).contains(q) }
    }

    private fun norm(s: String) = s.trim().lowercase().replace('_', ' ').replace('/', ' ').replace(Regex("\\s+"), " ")

    /** Label for a stored zone (null = automatic). */
    fun label(zone: String?): String = Clock.cleanZone(zone) ?: AUTOMATIC_LABEL
}
