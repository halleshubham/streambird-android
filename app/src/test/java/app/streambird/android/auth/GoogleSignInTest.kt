package app.streambird.android.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import app.streambird.android.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class GoogleSignInTest {
    @Test fun `nonce is 43 url-safe chars and different every time`() {
        val a = newGoogleNonce(); val b = newGoogleNonce()
        assertEquals(43, a.length) // 32 bytes, unpadded base64
        assertTrue(Regex("[A-Za-z0-9_-]+").matches(a))
        assertNotEquals(a, b)
    }

    @Test fun `server errors map per contract`() {
        assertEquals(GoogleSignInText.BAD_REQUEST, googleServerError(ApiException(400, "idToken must be a string")))
        assertEquals(GoogleSignInText.REJECTED, googleServerError(ApiException(401, "Invalid token")))
        assertEquals("Google sign-in is not available on this server yet. Use the email code.", googleServerError(ApiException(404, "Cannot POST /api/auth/google/mobile")))
        assertEquals(GoogleSignInText.RATE_LIMITED, googleServerError(ApiException(429, "ThrottlerException: Too Many Requests")))
        assertEquals("Boom", googleServerError(ApiException(500, "Boom")))
    }

    @Test fun `network errors name the problem`() {
        assertEquals("Cannot reach the server: timeout", googleServerError(SocketTimeoutException("timeout")))
        assertEquals("Cannot reach the server: dns", googleServerError(IOException("dns")))
        assertEquals(GoogleSignInText.FAILED, googleServerError(IllegalStateException("x")))
    }

    @Test fun `credential manager exceptions map to results`() {
        assertEquals(GoogleTokenResult.Cancelled, mapGetCredentialError(GetCredentialCancellationException()))
        assertEquals(GoogleTokenResult.NoAccount, mapGetCredentialError(NoCredentialException()))
        assertEquals(GoogleTokenResult.Unavailable, mapGetCredentialError(GetCredentialProviderConfigurationException()))
        assertTrue(mapGetCredentialError(GetCredentialInterruptedException()) is GoogleTokenResult.Failed)
        assertTrue(mapGetCredentialError(GetCredentialCustomException("x.y", "m")) is GoogleTokenResult.Failed)
    }
}
