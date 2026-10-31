package app.streambird.android.stream

import app.streambird.android.data.ThumbnailResult

/** "Watch me live: <link>" (one link on the same line, several as a list). Empty list -> empty text. */
fun buildShareText(links: List<String>): String {
    val l = links.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return when (l.size) {
        0 -> ""
        1 -> "Watch me live: ${l[0]}"
        else -> "Watch me live:\n" + l.joinToString("\n")
    }
}

/**
 * The links to share for the destinations of a stream: the connection's permanent live link (`liveLinkUrl`) first,
 * else the destination's own `watchUrl` from the stream/status response. [watchUrls] are connectionId -> watchUrl pairs.
 */
fun resolveLiveLinks(destinationIds: Collection<String>, liveLinks: Map<String, String?>, watchUrls: List<Pair<String, String?>>): List<String> =
    destinationIds.mapNotNull { id ->
        liveLinks[id]?.takeIf { it.isNotBlank() } ?: watchUrls.firstOrNull { it.first == id && !it.second.isNullOrBlank() }?.second
    }.distinct()

enum class NoticeKind { SUCCESS, WARNING, ERROR }

/** A dismissible message on the Live screen about the background thumbnail upload. */
data class ThumbnailNotice(val kind: NoticeKind, val text: String)

fun thumbnailNotice(r: ThumbnailResult): ThumbnailNotice =
    if (r.platformWarnings.isEmpty()) ThumbnailNotice(NoticeKind.SUCCESS, "Thumbnail uploaded.")
    else ThumbnailNotice(NoticeKind.WARNING, "Thumbnail saved, with notes:\n" + r.platformWarnings.joinToString("\n") { "- $it" })

fun thumbnailFailure(message: String?): ThumbnailNotice =
    ThumbnailNotice(NoticeKind.ERROR, "Thumbnail was not set: ${message ?: "unknown error"}. Your stream is not affected.")
