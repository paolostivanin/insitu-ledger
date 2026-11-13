package com.insituledger.app.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.ui.common.primaryCurrency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountFormUiState(
    val id: Long? = null,
    val name: String = "",
    val currency: String = "EUR",
    /** Opening balance as typed (new accounts only). Blank saves as 0. */
    val balance: String = "",
    /** Current balance of the account being edited. */
    val currentBalance: Double? = null,
    /** Most common currency across all accounts (it uses the preferred display symbol). */
    val primaryCurrency: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    val balanceError: String? = null,
    val error: String? = null,
    val saved: Boolean = false
)

/** Parses a typed opening balance; blank means 0, a comma decimal separator is accepted. */
internal fun parseOpeningBalance(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0.0
    return trimmed.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

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
                val primary = try { primaryCurrency(accountRepository.getCached()) } catch (_: Exception) { null }
                if (account != null) {
                    _uiState.update {
                        it.copy(name = account.name, currency = account.currency,
                            balance = "%.2f".format(account.balance), currentBalance = account.balance,
                            primaryCurrency = primary, isLoading = false)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun updateName(name: String) { _uiState.update { it.copy(name = name, nameError = null) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
    fun updateBalance(balance: String) { _uiState.update { it.copy(balance = balance, balanceError = null) } }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        val nameError = if (state.name.isBlank()) "Name is required" else null
        val balance = if (editId == null) parseOpeningBalance(state.balance) else 0.0
        val balanceError = if (balance == null) "Enter an amount, e.g. 120.50" else null
        if (nameError != null || balanceError != null) {
            _uiState.update { it.copy(nameError = nameError, balanceError = balanceError) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                if (editId != null) {
                    accountRepository.update(editId, state.name, state.currency)
                } else {
                    accountRepository.create(state.name, state.currency, balance ?: 0.0)
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Could not save the account") }
            }
        }
    }
}
