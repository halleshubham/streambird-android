package app.streambird.android.auth

import android.content.Context
import app.streambird.android.data.ApiException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * "Sign in with Google in your browser": a PKCE-style handoff that needs no Google client on the phone.
 * app: verifier = random, challenge = base64url(SHA-256(verifier)) -> browser opens /api/auth/google/app?challenge=... -> server sends the
 * browser to `streambird://auth?code=<code>` -> app POSTs /api/auth/google/app/redeem {code, verifier}. The verifier never leaves the app
 * before the redeem, so the custom scheme is NOT a trust boundary: whoever catches the link has a code that is useless without the verifier.
 */
object BrowserSignIn {
    const val SCHEME = "streambird"
    const val HOST = "auth"
    /** A started sign-in is valid this long (the app may be killed while the browser is in front, so it is persisted). */
    const val VERIFIER_TTL_MS = 10 * 60 * 1000L
    const val MAX_CODE_LENGTH = 512

    object Text {
        const val SIGNING_IN = "Signing you in..."
        const val NO_PENDING = "That sign-in link is not valid any more. Start again with \"Sign in with Google in your browser\"."
        const val STALE = "That sign-in took too long (over 10 minutes). Start again with \"Sign in with Google in your browser\"."
        const val FAILED_LINK = GoogleSignInText.DID_NOT_FINISH
        const val NO_BROWSER = "No browser found on this phone. Use the email code."
    }

    /** 32 secure random bytes as 43 URL-safe base64 characters without padding. */
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    /** base64url(SHA-256(verifier as ASCII)), 43 characters, no padding (RFC 7636 S256). */
    fun challengeFor(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The page the browser opens. The challenge is URL-safe base64, so it needs no escaping. [baseUrl] is the normalized server origin. */
    fun startUrl(baseUrl: String, challenge: String): String = baseUrl.trimEnd('/') + "/api/auth/google/app?challenge=" + challenge

    // ---- deep link ----

    sealed interface Link {
        data class Code(val code: String) : Link
        /** `error=...` came back (the server only uses `google_failed`). The reason is not shown verbatim. */
        data class Error(val reason: String) : Link
        /** Right scheme and host but no usable code or error. */
        data object Malformed : Link
        /** Not our link at all (other scheme/host, or garbage): ignored silently. */
        data object NotOurs : Link
    }

    /** Parses `streambird://auth?code=...` / `streambird://auth?error=...`. Pure (no android.net.Uri, so it runs on the JVM). */
    fun parseLink(raw: String?): Link {
        val s = raw?.trim().orEmpty()
        val schemeEnd = s.indexOf("://")
        if (schemeEnd <= 0) return Link.NotOurs
        if (!s.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return Link.NotOurs
        val rest = s.substring(schemeEnd + 3).substringBefore('#')
        val host = rest.substringBefore('?').substringBefore('/')
        if (!host.equals(HOST, ignoreCase = true)) return Link.NotOurs
        val query = if (rest.contains('?')) rest.substringAfter('?') else ""
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            k to runCatching { java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        params["error"]?.let { return Link.Error(it.take(64)) }
        val code = params["code"]
        if (code.isNullOrEmpty() || code.length > MAX_CODE_LENGTH || !code.all { it.isLetterOrDigit() && it.code < 128 || it in "-_.~" }) return Link.Malformed
        return Link.Code(code)
    }

    // ---- pending verifier ----

    sealed interface Pending {
        data class Valid(val verifier: String) : Pending
        data object None : Pending
        data object Stale : Pending
    }

    /** Reads the stored verifier and applies the 10 minute rule. A stale one is cleared here. The caller clears a valid one after the redeem attempt. */
    fun checkPending(store: PendingVerifierStore, nowMs: Long): Pending {
        val saved = store.load() ?: return Pending.None
        val age = nowMs - saved.savedAtMs
        if (age < 0 || age > VERIFIER_TTL_MS) { store.clear(); return Pending.Stale }
        return if (saved.verifier.isEmpty()) Pending.None else Pending.Valid(saved.verifier)
    }

    // ---- errors ----

    /** Message for a failed POST /auth/google/app/redeem: 400/401 not completed, 404 not on this server yet, 429 rate limit, network. */
    fun redeemError(e: Throwable): String = when {
        e is ApiException -> when (e.status) {
            400, 401 -> GoogleSignInText.BAD_REQUEST
            404 -> GoogleSignInText.NOT_ON_SERVER
            429 -> GoogleSignInText.RATE_LIMITED
            else -> e.message
        }
        e is IOException -> "Cannot reach the server: ${e.message}"
        else -> GoogleSignInText.FAILED
    }
}

data class SavedVerifier(val verifier: String, val savedAtMs: Long)

/** Where the verifier waits while the browser is in front. An interface so the expiry logic is JVM-tested with a fake. */
interface PendingVerifierStore {
    fun save(verifier: String, nowMs: Long)
    fun load(): SavedVerifier?
    fun clear()
}

class InMemoryVerifierStore : PendingVerifierStore {
    @Volatile private var v: SavedVerifier? = null
    override fun save(verifier: String, nowMs: Long) { v = SavedVerifier(verifier, nowMs) }
    override fun load(): SavedVerifier? = v
    override fun clear() { v = null }
}

/** App-private SharedPreferences (allowBackup is off, so it is never backed up). Written with commit() so it is on disk before the browser opens. */
class SharedPrefsVerifierStore(context: Context) : PendingVerifierStore {
    private val prefs = context.applicationContext.getSharedPreferences("google_browser_signin", Context.MODE_PRIVATE)
    override fun save(verifier: String, nowMs: Long) { prefs.edit().putString("verifier", verifier).putLong("saved_at", nowMs).commit() }
    override fun load(): SavedVerifier? {
        val v = prefs.getString("verifier", null) ?: return null
        return SavedVerifier(v, prefs.getLong("saved_at", 0L))
    }
    override fun clear() { prefs.edit().clear().commit() }
}
