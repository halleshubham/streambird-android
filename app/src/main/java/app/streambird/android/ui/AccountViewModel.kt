package app.streambird.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.streambird.android.account.AccountController
import app.streambird.android.account.ApiAccount
import app.streambird.android.brand.BrandRepository
import app.streambird.android.data.ApiClient
import app.streambird.android.data.AuthRepository
import app.streambird.android.preset.PresetController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Account screen: signed-in email, sign out, and the delete-account flow ([AccountController]). */
@HiltViewModel
class AccountViewModel @Inject constructor(
    api: ApiClient,
    private val auth: AuthRepository,
    private val presets: PresetController,
    private val brand: BrandRepository,
) : ViewModel() {
    val email: String get() = auth.ui.value.user?.email ?: auth.ui.value.email

    val controller = AccountController(ApiAccount(api)) {
        presets.reset(); brand.reset()
        auth.accountDeleted()
    }
    val state = controller.state

    fun load() { viewModelScope.launch { controller.load() } }
    fun sendCode() { viewModelScope.launch { controller.sendCode() } }
    fun delete(code: String, confirmCompany: Boolean) { viewModelScope.launch { controller.delete(code, confirmCompany) } }
    fun logout() { viewModelScope.launch { auth.logout() } }
}
