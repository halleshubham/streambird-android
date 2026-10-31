package app.streambird.android.stream

import app.streambird.android.data.StreamListItem
import app.streambird.android.data.StreamStatusDestination
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure logic for following the server's view of a stream (StreamStatus: scheduled | live | ended | failed). */
object StatusFollow {
    enum class Server { LIVE, ENDED, FAILED, OTHER }

    fun interpret(status: String?): Server = when (status?.lowercase()) {
        "live" -> Server.LIVE
        "ended" -> Server.ENDED
        "failed" -> Server.FAILED
        else -> Server.OTHER
    }

    /** True when publishing must stop because the server no longer has this stream live. */
    fun mustStop(status: String?): Boolean = interpret(status) != Server.LIVE

    /** What the Ended summary says. [byUs]: the user pressed End on this phone. */
    fun endMessage(status: String?, byUs: Boolean, confirmed: Boolean = true): String = when {
        byUs && confirmed -> "Stream ended."
        byUs -> "Stopped sending, but the server did not confirm the end."
        interpret(status) == Server.FAILED -> "This stream was stopped by the server (failed)."
        else -> "This stream was ended elsewhere (on the website or another device)."
    }

    /** A destination row must not say "live" once the stream is over; a failed/ended destination keeps its own status. */
    fun destinationStatus(streamStatus: String?, destStatus: String): String =
        if (interpret(streamStatus) != Server.LIVE && interpret(streamStatus) != Server.OTHER && destStatus.lowercase() in setOf("live", "pending", "ready")) "ended" else destStatus

    fun finalDestinations(streamStatus: String?, dests: List<StreamStatusDestination>): List<StreamStatusDestination> =
        dests.map { it.copy(status = destinationStatus(streamStatus, it.status), platformStatus = if (interpret(streamStatus) == Server.LIVE) it.platformStatus else null) }

    /** Consecutive poll failures (network) never end a stream; a 404 means the stream is gone. */
    fun gone(httpStatus: Int) = httpStatus == 404
}

/** Streams the server still lists as live that THIS phone is not publishing (e.g. left behind by a crash). */
fun staleLiveStreams(items: List<StreamListItem>, activeStreamId: String?, dismissed: Set<String>): List<StreamListItem> =
    items.filter { it.status.equals("live", true) && it.id != activeStreamId && it.id !in dismissed }

fun formatStarted(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.US)) }.getOrDefault("")
