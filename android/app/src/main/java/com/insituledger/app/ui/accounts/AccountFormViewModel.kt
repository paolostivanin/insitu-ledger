package com.insituledger.app.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountFormUiState(
    val id: Long? = null,
    val name: String = "",
    val currency: String = "EUR",
    val balance: String = "0.0",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class AccountFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val editId: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
    private val _uiState = MutableStateFlow(AccountFormUiState(id = editId))
    val uiState: StateFlow<AccountFormUiState> = _uiState.asStateFlow()

    init {
        if (editId != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val account = accountRepository.getById(editId)
                if (account != null) {
                    _uiState.update {
                        it.copy(name = account.name, currency = account.currency,
                            balance = "%.2f".format(account.balance), isLoading = false)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun updateName(name: String) { _uiState.update { it.copy(name = name) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
    fun updateBalance(balance: String) { _uiState.update { it.copy(balance = balance) } }

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(error = "Name is required") }
            return
        }
        val balance = state.balance.toDoubleOrNull() ?: 0.0
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                if (editId != null) {
                    accountRepository.update(editId, state.name, state.currency)
                } else {
                    accountRepository.create(state.name, state.currency, balance)
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
