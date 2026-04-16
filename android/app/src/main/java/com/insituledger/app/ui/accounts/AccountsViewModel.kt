package com.insituledger.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> = sharedAccessState.selectedOwner
        .flatMapLatest { owner ->
            if (owner != null) {
                val accounts = accountRepository.listFromServer(owner.ownerId)
                flowOf(AccountsUiState(accounts = accounts, isLoading = false, isReadOnly = owner.permission == "read"))
            } else {
                accountRepository.getAll().map { accounts ->
                    AccountsUiState(accounts = accounts, isLoading = false, isReadOnly = false)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { accountRepository.delete(id) }
    }
}
