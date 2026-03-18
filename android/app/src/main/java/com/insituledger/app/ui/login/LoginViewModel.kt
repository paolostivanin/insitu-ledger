package com.insituledger.app.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AuthRepository
import com.insituledger.app.data.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "",
    val login: String = "",
    val password: String = "",
    val totpCode: String = "",
    val showTotp: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.serverUrlFlow.first().let { url ->
                if (url.isNotBlank()) _uiState.update { it.copy(serverUrl = url) }
            }
        }
    }

    fun updateServerUrl(url: String) { _uiState.update { it.copy(serverUrl = url) } }
    fun updateLogin(login: String) { _uiState.update { it.copy(login = login) } }
    fun updatePassword(password: String) { _uiState.update { it.copy(password = password) } }
    fun updateTotpCode(code: String) { _uiState.update { it.copy(totpCode = code) } }

    fun login() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.login.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Please fill all required fields") }
            return
        }
        if (state.showTotp && state.totpCode.isBlank()) {
            _uiState.update { it.copy(error = "Please enter the TOTP code") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authRepository.login(
                serverUrl = state.serverUrl,
                login = state.login,
                password = state.password,
                totpCode = if (state.showTotp) state.totpCode else null
            )
            result.fold(
                onSuccess = { response ->
                    if (response.totpRequired == true && response.token == null) {
                        _uiState.update { it.copy(isLoading = false, showTotp = true) }
                    } else {
                        // Set sync mode to webapp on successful login
                        prefs.saveSyncMode("webapp")
                        syncManager.schedulePeriodicSync()
                        syncManager.triggerImmediateSync()
                        _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Login failed") }
                }
            )
        }
    }
}
