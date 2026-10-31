package app.streambird.android.media

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Device-side readings taken with each stats sample. */
data class DeviceReading(
    val batteryPct: Int?,
    val batteryTempC: Double?,
    val charging: Boolean?,
    /** PowerManager thermal status name: NONE/LIGHT/MODERATE/SEVERE/CRITICAL/EMERGENCY/SHUTDOWN or null (API < 29). */
    val thermal: String?,
    val network: String,
)

data class LogRow(
    val timeMs: Long,
    val elapsedS: Long,
    val stats: EncoderStats?,
    val kbps: Double?,
    val warning: Boolean,
    val device: DeviceReading,
    /** Non-null on event rows (network change, state change...). */
    val event: String? = null,
)

/** Collects measurement rows and renders the CSV the founder shares from the Live screen. */
class SampleLog(private val header: List<Pair<String, String>>) {
    private val rows = mutableListOf<LogRow>()
    val size get() = synchronized(rows) { rows.size }

    fun add(row: LogRow) = synchronized(rows) { rows += row; Unit }
    fun snapshot(): List<LogRow> = synchronized(rows) { rows.toList() }

    fun toCsv(): String {
        val sb = StringBuilder()
        sb.append("# StreamBird Android spike log\n")
        for ((k, v) in header) sb.append("# ").append(k).append(": ").append(v.replace('\n', ' ')).append('\n')
        sb.append(COLUMNS.joinToString(",")).append('\n')
        for (r in snapshot()) sb.append(line(r)).append('\n')
        return sb.toString()
    }

    private fun line(r: LogRow): String {
        val s = r.stats
        val d = r.device
        // Always exactly COLUMNS.size cells. Missing values are written as NA (not empty), so tools that collapse
        // runs of commas cannot shift the battery/network values into the width/height columns of event rows.
        val cells = listOf(
            iso(r.timeMs), r.elapsedS.toString(),
            s?.width?.toString() ?: NA, s?.height?.toString() ?: NA,
            s?.fps?.let { f1(it) } ?: NA,
            r.kbps?.let { Math.round(it).toString() } ?: NA,
            s?.let { it.limitationReason ?: "none" } ?: NA,
            if (s == null) NA else if (r.warning) "WARN" else "ok",
            s?.encoderImpl ?: NA,
            s?.rttMs?.let { f1(it) } ?: NA,
            s?.lossPct?.let { f1(it) } ?: NA,
            s?.nackCount?.toString() ?: NA,
            s?.pliCount?.toString() ?: NA,
            s?.keyFramesEncoded?.toString() ?: NA,
            s?.avgEncodeMs?.let { f1(it) } ?: NA,
            d.batteryPct?.toString() ?: NA,
            d.batteryTempC?.let { f1(it) } ?: NA,
            d.thermal ?: NA,
            d.network,
            r.event ?: "",
        )
        return cells.joinToString(",") { csv(it) }
    }

    companion object {
        val COLUMNS = listOf(
            "timestamp_utc", "elapsed_s", "width", "height", "fps", "kbps", "limitation", "status", "encoder",
            "rtt_ms", "loss_pct", "nack_count", "pli_count", "keyframes", "avg_encode_ms",
            "battery_pct", "battery_temp_c", "thermal", "network", "event",
        )

        const val NA = "NA"

        private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)

        fun iso(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun csv(v: String): String = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}
