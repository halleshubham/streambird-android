package app.streambird.android.whip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class WhipException(val status: Int, message: String) : IOException(message)

/** [resourceUrl] is the absolute URL from the `Location` header (DELETE it to hang up); null if the server sent none. */
data class WhipSession(val answerSdp: String, val resourceUrl: String?)

/**
 * WHIP (RFC 9725) publisher side, the same exchange the web studio does with fetch():
 * POST <whipUrl>, Content-Type: application/sdp, body = the complete (non-trickle) offer -> 201 + Location + answer SDP.
 * The Location can be relative; it is resolved against the WHIP endpoint, not against the StreamBird API host.
 * Use a client WITHOUT the StreamBird cookie jar: the ingest host is a different service.
 */
class WhipClient(private val http: OkHttpClient) {
    private val sdp = "application/sdp".toMediaType()

    suspend fun publish(whipUrl: String, offerSdp: String): WhipSession = withContext(Dispatchers.IO) {
        val endpoint = whipUrl.toHttpUrl()
        val req = Request.Builder().url(endpoint).post(offerSdp.toByteArray(Charsets.UTF_8).toRequestBody(sdp)).build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw WhipException(res.code, "WHIP publish failed (${res.code})" + body.take(200).let { if (it.isBlank()) "" else ": $it" })
            if (body.isBlank()) throw WhipException(res.code, "WHIP server returned no SDP answer")
            val location = res.header("Location")?.let { endpoint.resolve(it)?.toString() }
            WhipSession(answerSdp = body, resourceUrl = location)
        }
    }

    /** Best-effort teardown; the backend's POST /streams/:id/end also stops the relay. Never throws. */
    suspend fun hangUp(resourceUrl: String?) {
        if (resourceUrl == null) return
        withContext(Dispatchers.IO) {
            runCatching { http.newCall(Request.Builder().url(resourceUrl).delete().build()).execute().close() }
        }
    }
}
