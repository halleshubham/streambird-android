package app.streambird.android.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import app.streambird.android.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `notes per result`() {
        assertEquals(null, googleNoteFor(GoogleTokenResult.Token("t")))
        assertEquals(GoogleSignInText.DID_NOT_FINISH, googleNoteFor(GoogleTokenResult.Cancelled))
        assertEquals(GoogleSignInText.NO_ACCOUNT, googleNoteFor(GoogleTokenResult.NoAccount))
        assertEquals(GoogleSignInText.UNAVAILABLE, googleNoteFor(GoogleTokenResult.Unavailable))
        assertEquals(GoogleSignInText.FAILED, googleNoteFor(GoogleTokenResult.Failed()))
        assertEquals("Google sign-in did not finish. If you chose an account and nothing happened, use \"Sign in with Google in your browser\" below, or the email code.", GoogleSignInText.DID_NOT_FINISH)
    }

    @Test fun `diagnostic line for a quick cancellation names class type message and timing`() {
        val e = GetCredentialCancellationException("activity is cancelled by the user.")
        val line = GoogleDiag.attemptLine(mapGetCredentialError(e), e, 820)
        assertTrue(line, line.startsWith("google-signin: result=Cancelled exception=GetCredentialCancellationException type="))
        assertTrue(line, line.contains("message=activity is cancelled by the user."))
        assertTrue(line, line.endsWith("elapsedMs=820 timing=quick(<1.5s)"))
        assertTrue(GoogleDiag.attemptLine(GoogleTokenResult.Cancelled, e, 9000).endsWith("elapsedMs=9000 timing=slow"))
        assertEquals("quick(<1.5s)", GoogleDiag.timing(1499)); assertEquals("slow", GoogleDiag.timing(1500))
    }

    @Test fun `diagnostic line covers every result and never contains a token`() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.c2lnbmF0dXJl"
        assertTrue(GoogleDiag.attemptLine(GoogleTokenResult.Token(jwt), null, 5).startsWith("google-signin: result=Token elapsedMs=5"))
        assertFalse(GoogleDiag.attemptLine(GoogleTokenResult.Token(jwt), null, 5).contains("eyJ"))
        val leaky = GetCredentialCustomException("x.y", "bad token $jwt here")
        val line = GoogleDiag.attemptLine(mapGetCredentialError(leaky), leaky, 100)
        assertFalse(line, line.contains("eyJ")); assertTrue(line, line.contains("[token]"))
        assertTrue(GoogleDiag.attemptLine(GoogleTokenResult.NoAccount, NoCredentialException(), 1).contains("result=NoAccount exception=NoCredentialException"))
        assertTrue(GoogleDiag.attemptLine(GoogleTokenResult.Unavailable, null, 1).contains("result=Unavailable"))
        assertTrue(GoogleDiag.attemptLine(GoogleTokenResult.Failed("unexpected credential type"), null, 1).contains("detail=unexpected credential type"))
        val multi = GoogleDiag.safeMessage("a\nb   c" + "x".repeat(400))!!
        assertFalse(multi.contains('\n')); assertEquals(200, multi.length)
        assertEquals(null, GoogleDiag.safeMessage("   ")); assertEquals(null, GoogleDiag.safeMessage(null))
    }
}
