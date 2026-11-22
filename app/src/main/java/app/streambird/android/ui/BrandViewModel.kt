package app.streambird.android.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.streambird.android.brand.BrandRepository
import app.streambird.android.brand.KitDeleteText
import app.streambird.android.brand.UploadKeys
import app.streambird.android.brand.UploadState
import app.streambird.android.data.ApiException
import app.streambird.android.data.AuthRepository
import app.streambird.android.data.BrandKit
import app.streambird.android.data.KitDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _msg = MutableStateFlow<BrandUiMsg?>(null)
    val msg: StateFlow<BrandUiMsg?> = _msg.asStateFlow()

    private val _uploading = MutableStateFlow<UploadState?>(null)
    /** The file being uploaded right now (null = none). */
    val uploading: StateFlow<UploadState?> = _uploading.asStateFlow()
    private var uploadJob: Job? = null

    fun clearMsg() { _msg.value = null }
    fun select(id: String?) = repo.select(id)

    private fun run(okText: String? = null, onDone: (() -> Unit)? = null, cancelText: String? = null, onFinally: (() -> Unit)? = null, block: suspend () -> Unit): Job {
        return viewModelScope.launch {
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
            } catch (e: CancellationException) {
                cancelText?.let { _msg.value = BrandUiMsg(it, false) }
                throw e
            } catch (e: IOException) {
                _msg.value = BrandUiMsg(e.message ?: "Network error", true)
            } finally {
                // Success, failure (the error message above stays) and cancel all end the loader; it can never be left on.
                onFinally?.invoke()
                _busy.value = false
            }
        }
    }

    fun refresh() = run { repo.refresh() }
    fun save(original: BrandKit?, draft: KitDraft, channels: Set<String>, onDone: (BrandKit) -> Unit) {
        var saved: BrandKit? = null
        run(onDone = { saved?.let(onDone) }) { saved = repo.saveKit(original, draft, channels) }
    }
    fun deleteKit(id: String, name: String, onDone: () -> Unit = {}) = run(KitDeleteText.done(name), onDone) { repo.deleteKit(id) }
    fun deleteAsset(id: String) = run("File deleted.") { repo.deleteAsset(id) }
    /**
     * Uploads the picked file. [uploading] is non-null (slot key, file name, bytes sent) from the start until the upload succeeded, failed or was
     * cancelled, in every case cleared in `finally`; it lives in the ViewModel so it survives rotation and screen changes. One upload at a time.
     */
    fun upload(uri: Uri, kind: String = "logo", slotKey: String = defaultSlotKey(kind), onDone: (String) -> Unit) {
        if (_uploading.value != null) return
        _uploading.value = UploadState(slotKey, kind)
        var id: String? = null
        uploadJob = run(
            uploadedText(kind), onDone = { id?.let(onDone) }, cancelText = "Upload cancelled.", onFinally = { _uploading.value = null; uploadJob = null },
        ) {
            id = repo.uploadPicked(uri, kind,
                onName = { n -> _uploading.update { it?.withName(n) } },
                onProgress = { sent, total -> _uploading.update { it?.withProgress(sent, total) } },
            ).id
        }
    }

    /** The Cancel button of the loader: aborts the HTTP call (nothing is stored), clears the loader and says "Upload cancelled.". */
    fun cancelUpload() { uploadJob?.cancel() }

    private fun uploadedText(kind: String) = when (kind) { "ad" -> "Ad picture uploaded."; "wallpaper" -> "Wallpaper uploaded."; "thumbnail" -> "Thumbnail uploaded."; else -> "Logo uploaded." }
    private fun defaultSlotKey(kind: String) = if (kind == "ad") UploadKeys.LIBRARY_AD else UploadKeys.LIBRARY_LOGO
}
