package app.streambird.android.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import app.streambird.android.data.ApiException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/** What asking Google for an ID token ended in. The Android implementation is [CredentialManagerGoogleProvider]; tests use a fake. */
sealed interface GoogleTokenResult {
    data class Token(val idToken: String) : GoogleTokenResult
    /**
     * The Google sheet ended without a token. Credential Manager ALSO reports "Google rejected this app" (wrong package / SHA-1 registration) as
     * a cancellation, so this is ambiguous: the app shows a calm note ([GoogleSignInText.DID_NOT_FINISH]) that points to the browser option.
     */
    data object Cancelled : GoogleTokenResult
    /** No Google account on the phone (or none usable). */
    data object NoAccount : GoogleTokenResult
    /** Google Play services / Credential Manager is missing, disabled or too old. */
    data object Unavailable : GoogleTokenResult
    data class Failed(val detail: String? = null) : GoogleTokenResult
}

/** The one place that talks to Credential Manager; everything else depends on this interface so it can be unit-tested. */
fun interface GoogleTokenProvider {
    suspend fun requestIdToken(nonce: String): GoogleTokenResult
}

object GoogleSignInText {
    const val NO_ACCOUNT = "No Google account on this phone. Add one in Settings or use the email code."
    const val UNAVAILABLE = "Google sign-in is not available on this phone (Google Play services is missing or needs an update). Use \"Sign in with Google in your browser\" below or the email code."
    const val FAILED = "Google sign-in did not work. Try again, use \"Sign in with Google in your browser\" below, or the email code."
    /** Shown for a cancelled attempt: the app cannot tell "backed out" from "Google rejected the app". Also for `streambird://auth?error=...`. */
    const val DID_NOT_FINISH = "Google sign-in did not finish. If you chose an account and nothing happened, use \"Sign in with Google in your browser\" below, or the email code."
    const val BROWSER_LABEL = "Sign in with Google in your browser"
    const val NOT_ON_SERVER = "Google sign-in is not available on this server yet. Use the email code."
    const val BAD_REQUEST = "Google sign-in could not be completed. Try again or use the email code."
    const val REJECTED = "Google could not verify this account. Try again or use the email code."
    const val RATE_LIMITED = "Too many sign-in attempts. Wait a minute and try again."
}

/** A fresh random nonce (32 bytes, URL-safe base64, no padding) per attempt: replay-protection hygiene, Credential Manager puts it into the ID token. */
fun newGoogleNonce(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32).also(random::nextBytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Message for a failed token exchange with the server (POST /auth/google/mobile). 400/401/404/429 per docs/API-CONTRACT.md, anything else verbatim. */
fun googleServerError(e: Throwable): String = when {
    e is ApiException -> when (e.status) {
        400 -> GoogleSignInText.BAD_REQUEST
        401 -> GoogleSignInText.REJECTED
        404 -> GoogleSignInText.NOT_ON_SERVER
        429 -> GoogleSignInText.RATE_LIMITED
        else -> e.message
    }
    e is IOException -> "Cannot reach the server: ${e.message}"
    else -> GoogleSignInText.FAILED
}

/** Credential Manager exception -> result. Pure so it is unit-tested with the real exception classes. */
fun mapGetCredentialError(e: GetCredentialException): GoogleTokenResult = when (e) {
    is GetCredentialCancellationException -> GoogleTokenResult.Cancelled
    is NoCredentialException -> GoogleTokenResult.NoAccount
    is GetCredentialProviderConfigurationException -> GoogleTokenResult.Unavailable
    else -> GoogleTokenResult.Failed(e.type)
}

/** The note shown under the Google button for a Credential Manager outcome that is not a token (null for [GoogleTokenResult.Token]). Pure. */
fun googleNoteFor(result: GoogleTokenResult): String? = when (result) {
    is GoogleTokenResult.Token -> null
    GoogleTokenResult.Cancelled -> GoogleSignInText.DID_NOT_FINISH
    GoogleTokenResult.NoAccount -> GoogleSignInText.NO_ACCOUNT
    GoogleTokenResult.Unavailable -> GoogleSignInText.UNAVAILABLE
    is GoogleTokenResult.Failed -> GoogleSignInText.FAILED
}

/** Pure helpers for the diagnostic line written per Credential Manager attempt (never contains the ID token). */
object GoogleDiag {
    const val QUICK_MS = 1500L

    fun resultName(r: GoogleTokenResult): String = when (r) {
        is GoogleTokenResult.Token -> "Token"
        GoogleTokenResult.Cancelled -> "Cancelled"
        GoogleTokenResult.NoAccount -> "NoAccount"
        GoogleTokenResult.Unavailable -> "Unavailable"
        is GoogleTokenResult.Failed -> "Failed"
    }

    private val JWT_LIKE = Regex("eyJ[A-Za-z0-9_-]{8,}(\\.[A-Za-z0-9_-]*)*")

    /** Single-line, at most 200 chars, and any JWT-looking run (eyJ...) is masked so a token can never reach the log. */
    fun safeMessage(m: String?): String? = m?.replace(JWT_LIKE, "[token]")?.replace(Regex("\\s+"), " ")?.trim()?.take(200)?.ifEmpty { null }

    /** "quick" = back within 1.5 s (typically Google refusing the app right after the account was picked); "slow" = the person took their time. */
    fun timing(elapsedMs: Long): String = if (elapsedMs < QUICK_MS) "quick(<1.5s)" else "slow"

    fun attemptLine(result: GoogleTokenResult, exception: Throwable?, elapsedMs: Long): String = buildString {
        append("google-signin: result=").append(resultName(result))
        if (result is GoogleTokenResult.Failed) result.detail?.let { append(" detail=").append(safeMessage(it)) }
        if (exception != null) {
            append(" exception=").append(exception.javaClass.simpleName)
            if (exception is GetCredentialException) append(" type=").append(safeMessage(exception.type))
            append(" message=").append(safeMessage(exception.message))
        }
        append(" elapsedMs=").append(elapsedMs).append(" timing=").append(timing(elapsedMs))
    }
}

/**
 * "Sign in with Google" button flow ([GetSignInWithGoogleOption], the option Google recommends for an explicit button), asking for an ID token whose
 * audience is the server's web client ([serverClientId]). [context] must be an Activity so Google can show its sheet.
 * Every attempt writes one line to the diagnostic event log ([GoogleDiag.attemptLine]) so a "nothing happened" report can be told apart.
 */
class CredentialManagerGoogleProvider(
    private val context: Context,
    private val serverClientId: String,
    private val log: (String) -> Unit = { app.streambird.android.diag.Diag.log(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : GoogleTokenProvider {
    override suspend fun requestIdToken(nonce: String): GoogleTokenResult {
        val started = clock()
        var cause: Throwable? = null
        val result = try {
            val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else GoogleTokenResult.Failed("unexpected credential type")
        } catch (e: GetCredentialException) {
            cause = e
            mapGetCredentialError(e)
        } catch (e: GoogleIdTokenParsingException) {
            cause = e
            GoogleTokenResult.Failed("unreadable Google token")
        } catch (e: kotlinx.coroutines.CancellationException) {
            log(GoogleDiag.attemptLine(GoogleTokenResult.Cancelled, e, clock() - started))
            throw e
        }
        log(GoogleDiag.attemptLine(result, cause, clock() - started))
        return result
    }
}
