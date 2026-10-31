package app.streambird.android.data

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

data class AuthUi(
    val stage: Stage = Stage.LOADING,
    val user: AuthUser? = null,
    val email: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val note: String? = null,
    val server: String = "",
)

/** Decides which stage a freshly fetched user is in. Pure, unit-tested. */
fun stageFor(user: AuthUser): Stage = when {
    !user.isApproved -> Stage.PENDING
    user.needsPhone -> Stage.NEEDS_PHONE
    else -> Stage.READY
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
) {
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
        _ui.update { it.copy(stage = stage, user = user, busy = false, error = null, codeSent = false) }
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
        _ui.update { it.copy(busy = true, error = null, note = null) }
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

    fun backToEmail() = _ui.update { it.copy(codeSent = false, error = null, note = null) }

    suspend fun logout() {
        runCatching { api.logout() }
        jar.clear()
        jar.flush() // durable before the screen says signed out: an older save must never bring the session back
        pollJob?.cancel()
        _ui.update { it.copy(stage = Stage.LOGGED_OUT, user = null, codeSent = false, note = null, error = null) }
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
