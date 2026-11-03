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
    /** The person closed the Google sheet: not an error, nothing is shown. */
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
    const val UNAVAILABLE = "Google sign-in is not available on this phone (Google Play services is missing or needs an update). Use the email code."
    const val FAILED = "Google sign-in did not work. Try again or use the email code."
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

/**
 * "Sign in with Google" button flow ([GetSignInWithGoogleOption], the option Google recommends for an explicit button), asking for an ID token whose
 * audience is the server's web client ([serverClientId]). [context] must be an Activity so Google can show its sheet.
 */
class CredentialManagerGoogleProvider(private val context: Context, private val serverClientId: String) : GoogleTokenProvider {
    override suspend fun requestIdToken(nonce: String): GoogleTokenResult {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleTokenResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else GoogleTokenResult.Failed("unexpected credential type")
        } catch (e: GetCredentialException) {
            mapGetCredentialError(e)
        } catch (e: GoogleIdTokenParsingException) {
            GoogleTokenResult.Failed("unreadable Google token")
        }
    }
}
