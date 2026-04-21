package com.insituledger.app.ui.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.dto.SharedAccessDto
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedRepository
import com.insituledger.app.domain.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharedUiState(
    val accesses: List<SharedAccessDto> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val email: String = "",
    val selectedAccountId: Long? = null,
    val permission: String = "read",
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SharedViewModel @Inject constructor(
    private val sharedRepository: SharedRepository,
    private val accountRepository: AccountRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val syncMode = prefs.syncModeFlow.first()
            val token = prefs.tokenFlow.first()
            val connected = syncMode == "webapp" && token != null

            if (!connected) {
                _uiState.update { it.copy(isLoading = false, isConnected = false) }
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, isConnected = true, error = null) }
            try {
                val list = sharedRepository.listSharedAccess()
                val accounts = accountRepository.getCached()
                _uiState.update {
                    it.copy(
                        accesses = list,
                        accounts = accounts,
                        isLoading = false,
                        // Preserve a previous selection if still valid; otherwise pick the first.
                        selectedAccountId = it.selectedAccountId
                            ?.takeIf { id -> accounts.any { a -> a.id == id } }
                            ?: accounts.firstOrNull()?.id
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load shared access") }
            }
        }
    }

    fun updateEmail(email: String) { _uiState.update { it.copy(email = email) } }
    fun updateAccount(accountId: Long) { _uiState.update { it.copy(selectedAccountId = accountId) } }
    fun updatePermission(permission: String) { _uiState.update { it.copy(permission = permission) } }

    fun add() {
        val state = _uiState.value
        if (state.email.isBlank()) {
            _uiState.update { it.copy(error = "Email is required") }
            return
        }
        val accountId = state.selectedAccountId
        if (accountId == null) {
            _uiState.update { it.copy(error = "Pick an account to share") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            sharedRepository.createSharedAccess(state.email.trim(), accountId, state.permission)
                .onSuccess {
                    _uiState.update { it.copy(email = "", isSaving = false) }
                    load()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, error = e.message) }
                }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            try {
                sharedRepository.deleteSharedAccess(id)
                load()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed to delete") }
            }
        }
    }
}
