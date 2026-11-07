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

class ApiException(val status: Int, override val message: String, /** First 300 characters of the raw response body, for the diagnostics log only (never shown to the user). */ val body: String = "") : IOException(message) {
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

    /**
     * Client for big uploads (brand images up to 8 MB over a slow mobile uplink): same cookie jar, interceptors (Origin) and connection pool as [http],
     * but a write that stalls fails after 60 s instead of the default 10 s, the server gets 2 minutes to store the file and answer, and there is no overall
     * call deadline (a slow but moving upload may take minutes; the coroutine can still be cancelled).
     */
    internal val uploadHttp: OkHttpClient by lazy {
        http.newBuilder()
            .writeTimeout(UPLOAD_WRITE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(UPLOAD_READ_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    internal fun url(path: String): HttpUrl = (baseUrl().trimEnd('/') + "/api" + path).toHttpUrl()

    /** Cancellable: cancelling the coroutine cancels the HTTP call, also while the body is being read. Unexpected bodies (HTML from a captive portal, missing fields) become [ApiException]. */
    internal suspend fun <T> call(req: Request, client: OkHttpClient = http, parse: (String) -> T): T {
        val (code, text) = exec(req, client) { res -> res.code to res.body?.string().orEmpty() }
        if (code !in 200..299) throw ApiException(code, errorMessage(code, text), text.take(300))
        return try {
            parse(text)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ApiException(-1, "The server sent an unexpected answer. Check the server address or your connection.")
        } catch (e: IllegalArgumentException) {
            throw ApiException(-1, "The server sent an unexpected answer. Check the server address or your connection.")
        }
    }

    /** Runs [read] on OkHttp's thread as soon as the response arrives (the body is read there, so a cancel interrupts it). */
    private suspend fun <R> exec(req: Request, client: OkHttpClient = http, read: (Response) -> R): R = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(req)
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

    /** POST /auth/google/app/redeem {code, verifier}: browser sign-in handoff; same answer and sb_session cookie as [verifyCode]. Nothing else is sent. */
    suspend fun googleAppRedeem(code: String, verifier: String): AuthResponse =
        call(post("/auth/google/app/redeem", googleAppRedeemBody(code, verifier))) { ApiJson.decodeFromString<AuthResponse>(it) }

    fun googleAppRedeemBody(code: String, verifier: String): RequestBody =
        buildJsonObject { put("code", code); put("verifier", verifier) }.toString().toRequestBody(jsonType)

    /** PATCH /auth/me/phone {phone}: only the contract field is sent. Answers like /auth/me (`{user, accountId}`); 400 carries the server's own validation text. */
    suspend fun setPhone(phone: String): AuthResponse =
        call(Request.Builder().url(url("/auth/me/phone")).patch(setPhoneBody(phone)).build()) { ApiJson.decodeFromString<AuthResponse>(it) }

    fun setPhoneBody(phone: String): RequestBody = buildJsonObject { put("phone", phone) }.toString().toRequestBody(jsonType)

    suspend fun me(): AuthResponse = call(get("/auth/me")) { ApiJson.decodeFromString(it) }
    suspend fun logout() = call(post("/auth/logout")) { }

    // ---- data ----
    suspend fun platformConnections(): List<PlatformConnection> =
        call(get("/platform-connections")) { ApiJson.decodeFromString(it) }

    suspend fun myLimits(): EffectiveLimits = call(get("/plans/me")) { ApiJson.decodeFromString(it) }
    suspend fun brandKits(): List<BrandKit> = call(get("/brand-kits")) { ApiJson.decodeFromString(it) }

    /** The image bytes of a library asset; refuses anything over [MAX_ASSET_DOWNLOAD] (the backend's own cap is 8 MB per brand image). */
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

        /** Uploads over a slow mobile uplink: a write that makes no progress for this long fails (OkHttp counts per write, not per call). */
        const val UPLOAD_WRITE_TIMEOUT_S = 60L

        /** After the last byte is sent the server validates and stores the file (object storage) before it answers. */
        const val UPLOAD_READ_TIMEOUT_S = 120L

        /**
         * Pulls a human message out of the backend's error body, whatever its shape. The real server wraps Nest's own response in its AllExceptionsFilter:
         * `{"statusCode":400,"message":{"message":["config.each value in destinationIds must be a UUID"],"error":"Bad Request","statusCode":400},"timestamp":"..."}`
         * (a validation failure carries an ARRAY one level down), while thrown HttpExceptions with a string carry `message.message` as a string, and
         * a bare `{"message":"..."}` / `{"message":["..."]}` also works. Arrays are joined with ", ".
         */
        fun errorMessage(status: Int, body: String): String {
            val parsed = runCatching { ApiJson.decodeFromString<ErrorBody>(body) }.getOrNull()
            val text = messageText(parsed?.message)
            return text?.takeIf { it.isNotBlank() }?.let { neutralizePlanSteering(it) } ?: "Request failed ($status)"
        }

        private fun messageText(el: kotlinx.serialization.json.JsonElement?, depth: Int = 0): String? = when {
            el == null || depth > 3 || el is kotlinx.serialization.json.JsonNull -> null
            el is JsonPrimitive -> el.content
            el is JsonArray -> el.mapNotNull { messageText(it, depth + 1) }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
            el is JsonObject -> messageText(el["message"], depth + 1)
            else -> null
        }

        /** What the app says instead of any server text that points at upgrading, plans, prices or billing (Google Play payments policy: no purchase steering in the app). */
        const val NOT_IN_PLAN = "This is not included in this account's plan."
        private val STEERING = Regex("""\b(upgrade|paid plans?|free plan|see plans|pricing|prices?|billing|subscribe|subscription)\b""", RegexOption.IGNORE_CASE)

        fun neutralizePlanSteering(message: String): String = if (STEERING.containsMatchIn(message)) NOT_IN_PLAN else message
    }
}
