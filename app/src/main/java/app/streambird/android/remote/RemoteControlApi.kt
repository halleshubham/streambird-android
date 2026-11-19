package app.streambird.android.remote

import app.streambird.android.data.ApiClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What one acknowledgement says. Only these fields are ever sent. */
data class RemoteAck(val id: String, val ok: Boolean, val error: String?, val appliedAtMs: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("ok", ok)
        if (!ok && error != null) put("error", error.take(MAX_ERROR))
        put("appliedAt", java.time.Instant.ofEpochMilli(appliedAtMs).toString())
    }

    companion object { const val MAX_ERROR = 120 }
}

/** The three calls of the remote-control protocol. Errors are thrown as [app.streambird.android.data.ApiException] (HTTP status) or IOException (network). */
interface RemoteControlApi {
    /** Long poll: the raw answer body; blank = 204 / nothing new. */
    suspend fun poll(streamId: String, after: Long): String
    suspend fun ack(streamId: String, ack: RemoteAck)
    suspend fun putState(streamId: String, rev: Long, state: JsonObject)
}

/**
 * The real thing over the app's [ApiClient]: same server base URL, cookie jar and Origin interceptor as every other call, so the commands
 * are trusted only because they arrive over the authenticated session. The poll uses [ApiClient.remoteHttp] (35 s read timeout, no call timeout).
 */
class HttpRemoteControlApi(private val api: ApiClient) : RemoteControlApi {
    override suspend fun poll(streamId: String, after: Long): String =
        api.call(Request.Builder().url(api.url("/streams/$streamId/remote-control?after=$after&wait=$WAIT_S")).get().build(), api.remoteHttp) { it }

    override suspend fun ack(streamId: String, ack: RemoteAck) {
        api.call(Request.Builder().url(api.url("/streams/$streamId/remote-control/ack")).post(ack.toJson().toString().toRequestBody(api.jsonType)).build()) { }
    }

    override suspend fun putState(streamId: String, rev: Long, state: JsonObject) {
        val body = buildJsonObject { put("rev", rev); put("state", state) }.toString().toRequestBody(api.jsonType)
        api.call(Request.Builder().url(api.url("/streams/$streamId/remote-control/state")).put(body).build()) { }
    }

    companion object { const val WAIT_S = 25 }
}
