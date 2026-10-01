package com.insituledger.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.isOwnedBy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountsUiState(
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val currentUserId: Long? = null
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val sharedAccessState: SharedAccessState,
    prefs: UserPreferences
) : ViewModel() {

    val uiState: StateFlow<AccountsUiState> = combine(
        accountRepository.getAll(),
        sharedAccessState.ownerFilter,
        prefs.userIdFlow
    ) { accounts, filter, currentUserId ->
        val filtered = if (filter == null) accounts else accounts.filter { it.userId == filter }
        AccountsUiState(accounts = filtered, isLoading = false, currentUserId = currentUserId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())

    suspend fun delete(id: Long): Result<Unit> = runCatching {
        val target = uiState.value.accounts.find { it.id == id }
            ?: error("Account no longer exists")
        check(target.isOwnedBy(uiState.value.currentUserId)) { "Only the owner can delete this account" }
        accountRepository.delete(id)
    }
}
