package com.insituledger.app.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
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

    fun delete(id: Long) {
        // Owner-only mutations: silently ignore deletes targeting accounts the
        // current user merely co-owns (the UI hides the affordance, but guard
        // here in case of stale state). The backend also enforces this.
        val current = uiState.value
        val target = current.accounts.find { it.id == id } ?: return
        if (target.userId != current.currentUserId) return
        viewModelScope.launch { accountRepository.delete(id) }
    }
}
