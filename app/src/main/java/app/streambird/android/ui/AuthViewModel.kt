package app.streambird.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.streambird.android.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(private val repo: AuthRepository) : ViewModel() {
    val ui = repo.ui
    fun setEmail(v: String) = repo.setEmail(v)
    fun setServer(v: String) = repo.setServer(v)
    fun sendCode() { viewModelScope.launch { repo.requestCode() } }
    fun verify(code: String) { viewModelScope.launch { repo.verify(code) } }
    fun back() = repo.backToEmail()
    fun checkAgain() { viewModelScope.launch { repo.checkAgain() } }
    fun logout() { viewModelScope.launch { repo.logout() } }
}
