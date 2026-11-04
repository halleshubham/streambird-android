package app.streambird.android.data

import app.streambird.android.auth.BrowserSignIn
import app.streambird.android.auth.GoogleSignInText
import app.streambird.android.auth.InMemoryVerifierStore
import app.streambird.android.auth.PendingVerifierStore
import app.streambird.android.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class Stage { LOADING, LOGGED_OUT, PENDING, NEEDS_PHONE, READY }

/** "Continue with Google" progress: IDLE -> (browser open) -> REDEEMING (the code from the deep link is being exchanged: "Signing you in...") -> signed in, or ERROR / IDLE. */
enum class GoogleStage { IDLE, REDEEMING, ERROR }

data class AuthUi(
    val stage: Stage = Stage.LOADING,
    val user: AuthUser? = null,
    val email: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val note: String? = null,
    val server: String = "",
    val google: GoogleStage = GoogleStage.IDLE,
    /** Calm note shown under the Google button (the browser handoff ended without a result or failed). Not an error banner. */
    val googleNote: String? = null,
)

/** Decides which stage a freshly fetched user is in. Pure, unit-tested. */
fun stageFor(user: AuthUser): Stage = when {
    // The number comes first, before approval, like the web's ProtectedRoute: it is how the team can reach someone who is waiting.
    user.needsPhone -> Stage.NEEDS_PHONE
    !user.isApproved -> Stage.PENDING
    else -> Stage.READY
}

object PhoneText {
    const val EMPTY = "Enter your mobile number."
    const val RATE_LIMITED = "Too many tries. Wait a minute and try again."
    const val FAILED = "Could not save your number. Try again."
}

/** Message for a failed PATCH /auth/me/phone. 400 is the server's validation text verbatim ("Enter a valid mobile number, ..."). */
fun phoneServerError(e: Throwable): String = when {
    e is ApiException -> when (e.status) {
        429 -> PhoneText.RATE_LIMITED
        else -> e.message.ifBlank { PhoneText.FAILED }
    }
    e is IOException -> "Cannot reach the server: ${e.message}"
    else -> PhoneText.FAILED
}

/**
 * Email + one-time-code sign-in with the cookie session, mirroring web-app AuthContext / LoginPage:
 * POST /auth/request-code -> POST /auth/verify-code (sets sb_session) -> GET /auth/me. The cookie lives in the
 * persistent jar, so the session survives restarts. Pending accounts get the "studio is being set up" stage and are
 * re-checked every 30 s, like the web page.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiClient,
    private val jar: PersistentCookieJar,
    private val settings: StreamPrefs,
    private val scope: CoroutineScope,
    private val verifiers: PendingVerifierStore = InMemoryVerifierStore(),
) {
    /** Injectable clock for the 10 minute verifier rule (tests). */
    internal var clock: () -> Long = { System.currentTimeMillis() }
    private val _ui = MutableStateFlow(AuthUi(email = settings.lastEmail, server = settings.baseUrl))
    val ui: StateFlow<AuthUi> = _ui.asStateFlow()
    private var pollJob: Job? = null

    init { restore() }

    private fun restore() {
        if (!jar.hasCookie(SESSION_COOKIE)) { _ui.update { it.copy(stage = Stage.LOGGED_OUT) }; return }
        scope.launch { refresh(initial = true) }
    }

    /** GET /auth/me; moves to the matching stage. */
    suspend fun refresh(initial: Boolean = false) {
        try {
            val r = api.me()
            applyUser(r.user)
        } catch (e: ApiException) {
            if (e.isUnauthorized) expire() else _ui.update { it.copy(stage = if (initial) Stage.LOGGED_OUT else it.stage, error = e.message) }
        } catch (e: IOException) {
            _ui.update { it.copy(stage = if (initial) Stage.LOGGED_OUT else it.stage, error = "Cannot reach ${settings.baseUrl}: ${e.message}") }
        }
    }

    private fun applyUser(user: AuthUser) {
        val stage = stageFor(user)
        _ui.update { it.copy(stage = stage, user = user, busy = false, error = null, codeSent = false, googleNote = null, google = GoogleStage.IDLE) }
        pollJob?.cancel()
        if (stage == Stage.PENDING) {
            pollJob = scope.launch { while (isActive) { delay(PENDING_POLL_MS); refresh() } }
        }
    }

    /** Called by data screens when the server answers 401 (session expired/revoked) or 403 "pending approval". */
    fun expire() {
        jar.clear() // writes are applied in order, so no older save can overtake this
        pollJob?.cancel()
        _ui.update { it.copy(stage = Stage.LOGGED_OUT, user = null, codeSent = false, busy = false, note = "Your session expired. Please sign in again.") }
    }

    fun onPendingApproval() {
        scope.launch { refresh() }
    }

    fun setServer(raw: String): Boolean = try {
        val n = normalizeBaseUrl(raw)
        settings.baseUrl = n
        _ui.update { it.copy(server = n, error = null) }
        true
    } catch (e: Exception) {
        _ui.update { it.copy(error = (e as? IllegalArgumentException)?.message?.takeIf { m -> m.startsWith("Use an https") } ?: "That server address is not valid.") }
        false
    }

    fun setEmail(v: String) = _ui.update { it.copy(email = v, error = null) }

    suspend fun requestCode() {
        val email = _ui.value.email.trim()
        if (email.isEmpty()) { _ui.update { it.copy(error = "Enter your email.") }; return }
        _ui.update { it.copy(busy = true, error = null, note = null, googleNote = null) }
        try {
            api.requestCode(email)
            settings.lastEmail = email
            _ui.update { it.copy(busy = false, codeSent = true, note = "We emailed a 6-digit code to $email.") }
        } catch (e: IOException) {
            _ui.update { it.copy(busy = false, error = e.message ?: "Could not send the code.") }
        }
    }

    suspend fun verify(code: String) {
        val email = _ui.value.email.trim()
        _ui.update { it.copy(busy = true, error = null) }
        try {
            val r = api.verifyCode(email, code.trim())
            applyUser(r.user)
        } catch (e: ApiException) {
            // 400 "Incorrect code." / "This code is invalid or has expired." -> shown as-is.
            _ui.update { it.copy(busy = false, error = e.message) }
        } catch (e: IOException) {
            _ui.update { it.copy(busy = false, error = "Cannot reach the server: ${e.message}") }
        }
    }

    private fun googleError(message: String) = _ui.update { it.copy(google = GoogleStage.ERROR, busy = false, error = message) }

    // ---- "Continue with Google" (browser, PKCE-style handoff, see BrowserSignIn) ----

    /** True from the moment the browser was launched until a deep link arrives or the person is seen back on the login screen. */
    @Volatile private var awaitingBrowser = false

    /**
     * Step 1: makes the verifier/challenge pair, persists the verifier with a timestamp (the process may be killed while the browser is in front)
     * and returns the URL to open. Null while another sign-in step is running.
     */
    fun beginBrowserSignIn(): String? {
        val u = _ui.value
        if (u.busy || u.google == GoogleStage.REDEEMING) return null
        val verifier = BrowserSignIn.newVerifier()
        verifiers.save(verifier, clock())
        awaitingBrowser = true
        _ui.update { it.copy(googleNote = null, error = null, note = null, google = GoogleStage.IDLE) }
        Diag.log("google-browser: started")
        return BrowserSignIn.startUrl(settings.baseUrl, BrowserSignIn.challengeFor(verifier))
    }

    /** The browser could not be opened at all. */
    fun browserUnavailable() {
        verifiers.clear()
        awaitingBrowser = false
        _ui.update { it.copy(googleNote = BrowserSignIn.Text.NO_BROWSER) }
    }

    /**
     * Step 2: the app was opened with `streambird://auth?...` ([raw] is the URI string). Code -> POST /auth/google/app/redeem {code, verifier}, applied
     * like every other login ([applyUser]). Other links are ignored; with no pending verifier (or a stale one) a friendly message is shown. The
     * verifier is cleared after any redeem attempt. Neither the code nor the verifier is ever logged.
     */
    suspend fun handleAuthLink(raw: String?) {
        val link = BrowserSignIn.parseLink(raw)
        if (link == BrowserSignIn.Link.NotOurs) return
        val u = _ui.value
        if (u.stage != Stage.LOGGED_OUT && u.stage != Stage.LOADING) { verifiers.clear(); return } // already signed in: nothing to do
        if (u.google == GoogleStage.REDEEMING) return
        when (link) {
            BrowserSignIn.Link.NotOurs -> return
            is BrowserSignIn.Link.Error -> {
                verifiers.clear()
                Diag.log("google-browser: link error=${link.reason.filter { it.isLetterOrDigit() || it == '_' }.take(32)}")
                _ui.update { it.copy(google = GoogleStage.ERROR, busy = false, googleNote = GoogleSignInText.DID_NOT_FINISH) }
            }
            BrowserSignIn.Link.Malformed -> {
                Diag.log("google-browser: link without a usable code")
                _ui.update { it.copy(googleNote = GoogleSignInText.DID_NOT_FINISH) }
            }
            is BrowserSignIn.Link.Code -> redeem(link.code)
        }
    }

    /** Fire-and-forget entry for the activity: runs on the app scope so a finishing activity does not cancel a redeem in flight. */
    fun onAuthLink(raw: String?) {
        if (BrowserSignIn.parseLink(raw) != BrowserSignIn.Link.NotOurs) awaitingBrowser = false // synchronous: the activity's onResume follows onNewIntent
        scope.launch { handleAuthLink(raw) }
    }

    /**
     * The login screen came back to the front (activity resumed). If the browser was launched and no deep link arrived, the person left the Custom Tab
     * with Back (or Google ended without a result): show the calm note once, no error banner. The pending verifier stays until its 10 minute limit.
     */
    fun onReturnedToApp() {
        if (!awaitingBrowser) return
        awaitingBrowser = false
        val u = _ui.value
        if (u.stage != Stage.LOGGED_OUT || u.busy || u.google == GoogleStage.REDEEMING) return
        Diag.log("google-browser: returned without a code")
        _ui.update { it.copy(googleNote = GoogleSignInText.DID_NOT_FINISH) }
    }

    private suspend fun redeem(code: String) {
        val verifier = when (val p = BrowserSignIn.checkPending(verifiers, clock())) {
            is BrowserSignIn.Pending.Valid -> p.verifier
            BrowserSignIn.Pending.None -> { Diag.log("google-browser: link with no pending sign-in"); _ui.update { it.copy(error = BrowserSignIn.Text.NO_PENDING) }; return }
            BrowserSignIn.Pending.Stale -> { Diag.log("google-browser: link after more than 10 minutes"); _ui.update { it.copy(error = BrowserSignIn.Text.STALE) }; return }
        }
        _ui.update { it.copy(google = GoogleStage.REDEEMING, busy = true, error = null, note = null, googleNote = null) }
        try {
            val r = api.googleAppRedeem(code, verifier)
            Diag.log("google-browser: redeem ok")
            applyUser(r.user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _ui.update { it.copy(google = GoogleStage.IDLE, busy = false) }
            throw e
        } catch (e: IOException) {
            Diag.log("google-browser: redeem failed ${(e as? ApiException)?.status ?: e.javaClass.simpleName}")
            googleError(BrowserSignIn.redeemError(e))
        } finally {
            verifiers.clear()
        }
    }

    fun backToEmail() = _ui.update { it.copy(codeSent = false, error = null, note = null) }

    suspend fun logout() {
        runCatching { api.logout() }
        jar.clear()
        jar.flush() // durable before the screen says signed out: an older save must never bring the session back
        pollJob?.cancel()
        _ui.update { it.copy(stage = Stage.LOGGED_OUT, user = null, codeSent = false, note = null, error = null) }
    }

    /**
     * The server deleted the account (DELETE /account answered 204): same teardown as [logout] minus the server call (the session is already gone),
     * plus the preferences that belong to the person (last email, ticker). The server address is kept (it is app configuration, not account data).
     */
    suspend fun accountDeleted() {
        pollJob?.cancel()
        jar.clear()
        settings.lastEmail = ""
        settings.tickerText = ""
        settings.tickerSize = "S"
        jar.flush()
        _ui.update { it.copy(stage = Stage.LOGGED_OUT, user = null, email = "", codeSent = false, busy = false, error = null, note = "Your account was deleted.") }
    }

    /**
     * The phone step for new accounts: PATCH /auth/me/phone {phone}. The answer is the updated user, applied like a login ([applyUser]), so the stage
     * moves to PENDING (waiting for approval) or READY. 400 shows the server's own text inline; 401 ends the session; 429 and network have fixed texts.
     */
    suspend fun savePhone(raw: String) {
        val phone = raw.trim()
        if (phone.isEmpty()) { _ui.update { it.copy(error = PhoneText.EMPTY) }; return }
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null, note = null) }
        try {
            applyUser(api.setPhone(phone).user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _ui.update { it.copy(busy = false) }
            throw e
        } catch (e: ApiException) {
            if (e.isUnauthorized) expire() else _ui.update { it.copy(busy = false, error = phoneServerError(e)) }
        } catch (e: IOException) {
            _ui.update { it.copy(busy = false, error = phoneServerError(e)) }
        }
    }

    /** "Check again" on the pending screen. */
    suspend fun checkAgain() {
        _ui.update { it.copy(busy = true, note = null) }
        refresh()
        _ui.update { if (it.stage == Stage.PENDING) it.copy(busy = false, note = "Checked just now: your studio is still being set up.") else it.copy(busy = false) }
    }

    companion object {
        const val SESSION_COOKIE = "sb_session"
        const val PENDING_POLL_MS = 30_000L
    }
}
