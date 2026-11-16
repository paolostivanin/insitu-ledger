package com.insituledger.app.ui.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.dto.SharedAccessDto
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedRepository
import com.insituledger.app.domain.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val isSaving: Boolean = false,
    val error: String? = null,
    // One-shot feedback (add / remove results), shown as a snackbar and then
    // cleared via clearMessage(). Kept apart from [error], which belongs to
    // the add form.
    val message: String? = null
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
            val result = try {
                sharedRepository.createSharedAccess(state.email.trim(), accountId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result
                .onSuccess {
                    _uiState.update { it.copy(email = "", isSaving = false, message = "Co-owner added") }
                    load()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, error = e.message ?: "Couldn't add co-owner") }
                }
        }
    }

    fun delete(id: Long) {
        val access = _uiState.value.accesses.find { it.id == id }
        viewModelScope.launch {
            try {
                if (sharedRepository.deleteSharedAccess(id)) {
                    val removed = access?.let { "Removed ${it.guestName}'s access to ${it.accountName}" } ?: "Access removed"
                    _uiState.update { it.copy(message = removed) }
                } else {
                    _uiState.update { it.copy(message = "Couldn't remove access. Try again.") }
                }
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = e.message ?: "Couldn't remove access") }
            }
        }
    }

    fun clearMessage() { _uiState.update { it.copy(message = null) } }
}
