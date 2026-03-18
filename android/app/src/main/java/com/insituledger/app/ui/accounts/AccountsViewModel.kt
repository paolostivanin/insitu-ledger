package com.insituledger.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = false
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountsUiState())
    val uiState: StateFlow<AccountsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sharedAccessState.selectedOwner.collectLatest { owner ->
                _uiState.update { it.copy(isLoading = true) }
                if (owner != null) {
                    val accounts = accountRepository.listFromServer(owner.ownerId)
                    _uiState.update { it.copy(accounts = accounts, isLoading = false, isReadOnly = owner.permission == "read") }
                } else {
                    accountRepository.getAll().collect { accounts ->
                        _uiState.update { it.copy(accounts = accounts, isLoading = false, isReadOnly = false) }
                    }
                }
            }
        }
    }

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { accountRepository.delete(id) }
    }
}
