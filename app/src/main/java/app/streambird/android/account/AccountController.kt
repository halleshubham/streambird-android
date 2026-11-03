package app.streambird.android.account

import app.streambird.android.data.ApiClient
import app.streambird.android.data.ApiException
import app.streambird.android.data.DeletionPreview
import app.streambird.android.data.deleteAccount
import app.streambird.android.data.deletionPreview
import app.streambird.android.data.requestDeletionCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** The three calls account deletion needs; [ApiAccount] is the real one, tests use a fake. */
interface AccountPort {
    suspend fun preview(): DeletionPreview
    suspend fun requestCode()
    suspend fun delete(code: String, confirmCompanyDeletion: Boolean)
}

class ApiAccount(private val api: ApiClient) : AccountPort {
    override suspend fun preview() = api.deletionPreview()
    override suspend fun requestCode() = api.requestDeletionCode()
    override suspend fun delete(code: String, confirmCompanyDeletion: Boolean) = api.deleteAccount(code, confirmCompanyDeletion)
}

enum class DeletePhase {
    /** Fetching the preview. */
    LOADING,
    /** Preview shown, no code requested yet. */
    READY,
    SENDING_CODE,
    /** The code was emailed; waiting for the user to type it. */
    CODE_SENT,
    DELETING,
    /** Deleted; local data cleared. */
    DONE,
    /** The preview could not be loaded (network, server error): retry possible. */
    LOAD_FAILED,
    /** The server has no account-deletion endpoint (404). */
    UNAVAILABLE,
}

data class DeleteState(
    val phase: DeletePhase = DeletePhase.LOADING,
    val preview: DeletionPreview? = null,
    val error: String? = null,
    /** The server answered 409: the company confirmation is required even though the preview did not say so. */
    val serverWantsCompanyConfirm: Boolean = false,
) {
    val needsCompanyConfirm get() = serverWantsCompanyConfirm || preview?.companyDeletionNeedsConfirm == true
    val busy get() = phase == DeletePhase.LOADING || phase == DeletePhase.SENDING_CODE || phase == DeletePhase.DELETING
}

/** A 6-digit code, and the company box ticked when it is required. This is what enables the destructive button. */
fun canDeleteNow(code: String, needsCompanyConfirm: Boolean, confirmed: Boolean) =
    code.length == 6 && code.all { it in '0'..'9' } && (!needsCompanyConfirm || confirmed)

const val UNAVAILABLE_MESSAGE = "Account deletion is not available on this server yet. Contact support@shackyapps.in"
const val BAD_CODE_MESSAGE = "That code is wrong or has expired. Check it, or send a new code."
const val COMPANY_CONFIRM_MESSAGE = "Please tick the box to confirm that your whole company account will be deleted."
const val NOT_ALLOWED_MESSAGE = "This account cannot be deleted in the app. Contact support@shackyapps.in"
const val SESSION_MESSAGE = "Your session expired. Sign out, sign in again and retry."

/**
 * The delete-account flow: LOADING -> READY -> SENDING_CODE -> CODE_SENT -> DELETING -> DONE, with errors that put the user back on the
 * step they were on. [onDeleted] clears the session and local data (same code path as sign out plus caches and preferences); it runs before DONE.
 * Never throws for server/network problems.
 */
class AccountController(private val port: AccountPort, private val onDeleted: suspend () -> Unit) {
    private val _state = MutableStateFlow(DeleteState())
    val state: StateFlow<DeleteState> = _state.asStateFlow()

    suspend fun load() {
        _state.update { DeleteState(phase = DeletePhase.LOADING) }
        try {
            val p = port.preview()
            _state.update { DeleteState(phase = DeletePhase.READY, preview = p) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { DeleteState(phase = failedPhase(e, DeletePhase.LOAD_FAILED), error = messageFor(e, Step.PREVIEW)) }
        }
    }

    suspend fun sendCode() {
        val before = _state.value
        if (before.busy || before.phase == DeletePhase.DONE || before.phase == DeletePhase.UNAVAILABLE || before.preview == null) return
        _state.update { it.copy(phase = DeletePhase.SENDING_CODE, error = null) }
        try {
            port.requestCode()
            _state.update { it.copy(phase = DeletePhase.CODE_SENT, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = failedPhase(e, before.phase), error = messageFor(e, Step.REQUEST)) }
        }
    }

    /** [confirmCompany] is whether the box is ticked; it is only sent when ticked. Invalid input never reaches the server. */
    suspend fun delete(code: String, confirmCompany: Boolean) {
        val before = _state.value
        if (before.phase != DeletePhase.CODE_SENT) return
        if (!canDeleteNow(code, needsCompanyConfirm = false, confirmed = true)) { _state.update { it.copy(error = "Enter the 6-digit code from the email.") }; return }
        if (before.needsCompanyConfirm && !confirmCompany) { _state.update { it.copy(error = COMPANY_CONFIRM_MESSAGE) }; return }
        _state.update { it.copy(phase = DeletePhase.DELETING, error = null) }
        try {
            port.delete(code.trim(), confirmCompany)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val conflict = e is ApiException && e.status == 409
            _state.update { it.copy(phase = failedPhase(e, DeletePhase.CODE_SENT), error = messageFor(e, Step.DELETE), serverWantsCompanyConfirm = it.serverWantsCompanyConfirm || conflict) }
            return
        }
        // The account is gone on the server: whatever happens locally, the user is done.
        runCatching { onDeleted() }
        _state.update { it.copy(phase = DeletePhase.DONE, error = null) }
    }

    private enum class Step { PREVIEW, REQUEST, DELETE }

    private fun failedPhase(e: Exception, fallback: DeletePhase) = if (e is ApiException && e.status == 404) DeletePhase.UNAVAILABLE else fallback

    private fun messageFor(e: Exception, step: Step): String = when {
        e is ApiException && e.status == 404 -> UNAVAILABLE_MESSAGE
        e is ApiException && e.status == 409 -> COMPANY_CONFIRM_MESSAGE
        e is ApiException && e.status == 403 -> NOT_ALLOWED_MESSAGE
        e is ApiException && (e.status == 400 || e.status == 401) && step == Step.DELETE -> BAD_CODE_MESSAGE
        e is ApiException && e.status == 401 -> SESSION_MESSAGE
        e is ApiException -> e.message
        e is IOException -> "Cannot reach the server. Check your connection and try again."
        else -> "Something went wrong. Try again."
    }
}
