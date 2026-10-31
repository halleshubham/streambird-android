package app.streambird.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.streambird.android.brand.BrandRepository
import app.streambird.android.data.ApiException
import app.streambird.android.data.AuthRepository
import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class BrandUiMsg(val text: String, val error: Boolean)

/** Thin Compose-facing wrapper over [BrandRepository]: busy flag, one message, auth-error routing. */
@HiltViewModel
class BrandViewModel @Inject constructor(
    val repo: BrandRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    val state = repo.state
    val selectedKitId = repo.selectedKitId
    val serverUrl: String get() = repo.serverUrl

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _msg = MutableStateFlow<BrandUiMsg?>(null)
    val msg: StateFlow<BrandUiMsg?> = _msg.asStateFlow()

    fun clearMsg() { _msg.value = null }
    fun select(id: String?) = repo.select(id)

    private fun run(okText: String? = null, onDone: (() -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _msg.value = null
            try {
                block()
                okText?.let { _msg.value = BrandUiMsg(it, false) }
                onDone?.invoke()
            } catch (e: ApiException) {
                when {
                    e.isUnauthorized -> auth.expire()
                    e.isPendingApproval -> auth.onPendingApproval()
                    else -> _msg.value = BrandUiMsg(e.message, true)
                }
            } catch (e: IOException) {
                _msg.value = BrandUiMsg(e.message ?: "Network error", true)
            } finally {
                _busy.value = false
            }
        }
    }

    fun refresh() = run { repo.refresh() }
    fun save(original: BrandKit?, draft: KitDraft, channels: Set<String>, onDone: (BrandKit) -> Unit) {
        var saved: BrandKit? = null
        run(onDone = { saved?.let(onDone) }) { saved = repo.saveKit(original, draft, channels) }
    }
    fun deleteKit(id: String, onDone: () -> Unit) = run("Brand kit deleted.", onDone) { repo.deleteKit(id) }
    fun deleteAsset(id: String) = run("File deleted.") { repo.deleteAsset(id) }
    fun upload(uri: Uri, kind: String = "logo", onDone: (String) -> Unit) {
        var id: String? = null
        run(if (kind == "ad") "Ad picture uploaded." else "Logo uploaded.", onDone = { id?.let(onDone) }) { id = repo.uploadPicked(uri, kind).id }
    }
}
