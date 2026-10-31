package app.streambird.android.data

import kotlinx.serialization.Serializable

/** One row of GET /streams (StreamListItemDto): only what the app needs. */
@Serializable
data class StreamListItem(
    val id: String,
    val title: String = "",
    val status: String,
    val startedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class StreamList(val items: List<StreamListItem> = emptyList(), val total: Int = 0)

/** GET /api/streams?limit=20 (newest first). */
suspend fun ApiClient.recentStreams(limit: Int = 20): List<StreamListItem> =
    call(get("/streams?limit=$limit")) { ApiJson.decodeFromString<StreamList>(it).items }
