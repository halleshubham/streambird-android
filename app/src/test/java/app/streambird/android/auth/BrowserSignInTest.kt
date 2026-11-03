package app.streambird.android.auth

import app.streambird.android.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

class BrowserSignInTest {
    private val urlSafe = Regex("[A-Za-z0-9_-]+")

    @Test fun `verifier is 43 url-safe chars without padding and unique`() {
        val set = (1..50).map { BrowserSignIn.newVerifier() }.toSet()
        assertEquals(50, set.size)
        set.forEach { assertEquals(43, it.length); assertTrue(urlSafe.matches(it)) }
    }

    @Test fun `challenge matches the RFC 7636 S256 test vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", BrowserSignIn.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `challenge is 43 url-safe chars and differs from the verifier`() {
        val v = BrowserSignIn.newVerifier(SecureRandom())
        val c = BrowserSignIn.challengeFor(v)
        assertEquals(43, c.length); assertTrue(urlSafe.matches(c)); assertNotEquals(v, c)
        assertEquals(c, BrowserSignIn.challengeFor(v))
    }

    @Test fun `start url carries only the challenge`() {
        assertEquals("https://streambird.app/api/auth/google/app?challenge=abc-_", BrowserSignIn.startUrl("https://streambird.app/", "abc-_"))
    }

    @Test fun `links with a code`() {
        assertEquals(BrowserSignIn.Link.Code("abc123-_.~"), BrowserSignIn.parseLink("streambird://auth?code=abc123-_.~"))
        assertEquals(BrowserSignIn.Link.Code("x"), BrowserSignIn.parseLink("STREAMBIRD://AUTH/?code=x#frag"))
    }

    @Test fun `links with an error`() {
        assertEquals(BrowserSignIn.Link.Error("google_failed"), BrowserSignIn.parseLink("streambird://auth?error=google_failed"))
        // error wins over a code
        assertEquals(BrowserSignIn.Link.Error("x"), BrowserSignIn.parseLink("streambird://auth?code=abc&error=x"))
    }

    @Test fun `garbage wrong host wrong scheme and missing params`() {
        assertEquals(BrowserSignIn.Link.NotOurs, BrowserSignIn.parseLink(null))
        assertEquals(BrowserSignIn.Link.NotOurs, BrowserSignIn.parseLink(""))
        assertEquals(BrowserSignIn.Link.NotOurs, BrowserSignIn.parseLink("garbage"))
        assertEquals(BrowserSignIn.Link.NotOurs, BrowserSignIn.parseLink("https://streambird.app/auth?code=abc"))
        assertEquals(BrowserSignIn.Link.NotOurs, BrowserSignIn.parseLink("streambird://evil?code=abc"))
        assertEquals(BrowserSignIn.Link.NotOurs, BrowserSignIn.parseLink("streambird://auth.evil.com?code=abc"))
        assertEquals(BrowserSignIn.Link.Malformed, BrowserSignIn.parseLink("streambird://auth"))
        assertEquals(BrowserSignIn.Link.Malformed, BrowserSignIn.parseLink("streambird://auth?"))
        assertEquals(BrowserSignIn.Link.Malformed, BrowserSignIn.parseLink("streambird://auth?code="))
        assertEquals(BrowserSignIn.Link.Malformed, BrowserSignIn.parseLink("streambird://auth?foo=bar"))
        assertEquals(BrowserSignIn.Link.Malformed, BrowserSignIn.parseLink("streambird://auth?code=a<script>"))
        assertEquals(BrowserSignIn.Link.Malformed, BrowserSignIn.parseLink("streambird://auth?code=" + "a".repeat(513)))
    }

    @Test fun `pending verifier expiry`() {
        val store = InMemoryVerifierStore()
        assertEquals(BrowserSignIn.Pending.None, BrowserSignIn.checkPending(store, 1000))
        store.save("v", 1000)
        assertEquals(BrowserSignIn.Pending.Valid("v"), BrowserSignIn.checkPending(store, 1000 + BrowserSignIn.VERIFIER_TTL_MS))
        assertEquals("v", store.load()?.verifier) // still stored after a valid check: the caller clears it after the redeem attempt
        assertEquals(BrowserSignIn.Pending.Stale, BrowserSignIn.checkPending(store, 1000 + BrowserSignIn.VERIFIER_TTL_MS + 1))
        assertNull(store.load()) // stale is cleared
        store.save("v", 5000)
        assertEquals(BrowserSignIn.Pending.Stale, BrowserSignIn.checkPending(store, 1000)) // clock went backwards: do not trust it
    }

    @Test fun `redeem errors`() {
        val bad = "Google sign-in could not be completed. Try again or use the email code."
        assertEquals(bad, BrowserSignIn.redeemError(ApiException(400, "x")))
        assertEquals(bad, BrowserSignIn.redeemError(ApiException(401, "x")))
        assertEquals(GoogleSignInText.NOT_ON_SERVER, BrowserSignIn.redeemError(ApiException(404, "Cannot POST")))
        assertEquals(GoogleSignInText.RATE_LIMITED, BrowserSignIn.redeemError(ApiException(429, "x")))
        assertEquals("Cannot reach the server: dns", BrowserSignIn.redeemError(IOException("dns")))
        assertEquals("Boom", BrowserSignIn.redeemError(ApiException(500, "Boom")))
    }
}
