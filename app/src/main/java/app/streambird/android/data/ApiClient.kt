package app.streambird.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

class ApiException(val status: Int, override val message: String) : IOException(message) {
    val isUnauthorized get() = status == 401
    val isPendingApproval get() = status == 403 && message.contains("pending", ignoreCase = true)
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val ApiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** "https://streambird.app/" -> "https://streambird.app"; adds https:// when missing. Throws IllegalArgumentException when unusable. */
fun normalizeBaseUrl(raw: String, allowCleartext: Boolean = app.streambird.android.BuildConfig.DEBUG): String {
    var s = raw.trim().trimEnd('/')
    if (s.endsWith("/api")) s = s.removeSuffix("/api")
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    val url = s.toHttpUrl()
    if (url.scheme == "http" && !allowCleartext && !isLocalHost(url.host)) throw IllegalArgumentException("Use an https:// address: the sign-in cookie must not travel unencrypted.")
    return originOf(url)
}

private fun isLocalHost(h: String) = h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.endsWith(".local")

/** The exact string a browser would send as `Origin` for this server: scheme://host[:port], default ports omitted. */
fun originOf(url: HttpUrl): String {
    val defaultPort = if (url.scheme == "https") 443 else 80
    return buildString {
        append(url.scheme).append("://").append(url.host)
        if (url.port != defaultPort) append(':').append(url.port)
    }
}

/**
 * The backend authenticates with the `sb_session` cookie and, for every mutating request on that path,
 * requires `Origin` to equal its PUBLIC_BASE_URL (AccountGuard CSRF check). A native client has no Origin of its own,
 * so we send the server's own origin on every request to the configured server (and never to other hosts, e.g. MediaMTX).
 */
class OriginInterceptor(private val baseUrl: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val base = baseUrl().toHttpUrl()
        val req = chain.request()
        return if (req.url.host == base.host && req.url.port == base.port) {
            chain.proceed(req.newBuilder().header("Origin", originOf(base)).build())
        } else chain.proceed(req)
    }
}

/**
 * Thin JSON client over the StreamBird REST API (everything lives under /api). All calls are suspend + IO-dispatched.
 * Non-2xx responses become [ApiException] with the backend's own message when it sent one.
 */
class ApiClient(
    internal val http: OkHttpClient,
    private val baseUrl: () -> String,
) {
    internal val jsonType = "application/json".toMediaType()

    internal fun url(path: String): HttpUrl = (baseUrl().trimEnd('/') + "/api" + path).toHttpUrl()

    /** Cancellable: cancelling the coroutine cancels the HTTP call, also while the body is being read. Unexpected bodies (HTML from a captive portal, missing fields) become [ApiException]. */
    internal suspend fun <T> call(req: Request, parse: (String) -> T): T {
        val (code, text) = exec(req) { res -> res.code to res.body?.string().orEmpty() }
        if (code !in 200..299) throw ApiException(code, errorMessage(code, text))
        return try {
            parse(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ApiException(-1, "The server sent an unexpected answer. Check the server address or your connection.")
        } catch (e: IllegalArgumentException) {
            throw ApiException(-1, "The server sent an unexpected answer. Check the server address or your connection.")
        }
    }

    /** Runs [read] on OkHttp's thread as soon as the response arrives (the body is read there, so a cancel interrupts it). */
    private suspend fun <R> exec(req: Request, read: (Response) -> R): R = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { if (cont.isActive) cont.resumeWith(Result.failure(e)) }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val r = try { response.use(read) } catch (t: Throwable) { if (cont.isActive) cont.resumeWith(Result.failure(t)); return }
                if (cont.isActive) cont.resumeWith(Result.success(r))
            }
        })
    }

    internal fun get(path: String) = Request.Builder().url(url(path)).get().build()
    internal fun post(path: String, body: RequestBody? = null) =
        Request.Builder().url(url(path)).post(body ?: "".toRequestBody(jsonType)).build()

    // ---- auth ----
    suspend fun requestCode(email: String) {
        val body = buildJsonObject { put("email", email) }.toString().toRequestBody(jsonType)
        call(post("/auth/request-code", body)) { }
    }

    suspend fun verifyCode(email: String, code: String): AuthResponse {
        val body = buildJsonObject { put("email", email); put("code", code) }.toString().toRequestBody(jsonType)
        return call(post("/auth/verify-code", body)) { ApiJson.decodeFromString<AuthResponse>(it) }
    }

    suspend fun me(): AuthResponse = call(get("/auth/me")) { ApiJson.decodeFromString(it) }
    suspend fun logout() = call(post("/auth/logout")) { }

    // ---- data ----
    suspend fun platformConnections(): List<PlatformConnection> =
        call(get("/platform-connections")) { ApiJson.decodeFromString(it) }

    suspend fun myLimits(): EffectiveLimits = call(get("/plans/me")) { ApiJson.decodeFromString(it) }
    suspend fun brandKits(): List<BrandKit> = call(get("/brand-kits")) { ApiJson.decodeFromString(it) }

    /** The image bytes of a library asset; refuses anything over [MAX_ASSET_DOWNLOAD] (the backend's own cap is 4 MB). */
    suspend fun assetFile(id: String): ByteArray {
        val r = exec(get("/assets/$id/file")) { res ->
            if (!res.isSuccessful) Triple(res.code, res.body?.string().orEmpty(), null)
            else {
                val src = res.body!!.source()
                src.request(MAX_ASSET_DOWNLOAD + 1)
                if (src.buffer.size > MAX_ASSET_DOWNLOAD) Triple(-1, "", null) else Triple(200, "", src.buffer.readByteArray())
            }
        }
        if (r.first == -1) throw ApiException(-1, "The logo file is too large to use.")
        if (r.first != 200) throw ApiException(r.first, errorMessage(r.first, r.second))
        return r.third!!
    }

    suspend fun turnCredentials(): TurnCredentialsResponse =
        call(get("/studio-sessions/turn-credentials")) { ApiJson.decodeFromString(it) }

    // ---- streams ----
    suspend fun createStream(req: CreateStreamRequest): StreamDto {
        val body = ApiJson.encodeToString(req).toRequestBody(jsonType)
        return call(post("/streams", body)) { ApiJson.decodeFromString(it) }
    }

    suspend fun streamStatus(id: String): StreamStatusResponse =
        call(get("/streams/$id/status")) { ApiJson.decodeFromString(it) }

    suspend fun endStream(id: String): StreamDto = call(post("/streams/$id/end")) { ApiJson.decodeFromString(it) }

    companion object {
        const val MAX_ASSET_DOWNLOAD = 10L * 1024 * 1024

        /** Pulls a human message out of Nest's error body, whatever its shape (string, string[], or nested). */
        fun errorMessage(status: Int, body: String): String {
            val parsed = runCatching { ApiJson.decodeFromString<ErrorBody>(body) }.getOrNull()
            val m = parsed?.message
            val text = when (m) {
                is JsonPrimitive -> m.content
                is JsonArray -> m.joinToString(", ") { (it as? JsonPrimitive)?.content ?: it.toString() }
                is JsonObject -> (m["message"] as? JsonPrimitive)?.content
                else -> null
            }
            return text?.takeIf { it.isNotBlank() } ?: "Request failed ($status)"
        }
    }
}
