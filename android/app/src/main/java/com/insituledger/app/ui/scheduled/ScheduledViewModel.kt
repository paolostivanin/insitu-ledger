package com.insituledger.app.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.ScheduledTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduledUiState(
    val items: List<ScheduledTransaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val currentUserId: Long? = null
)

@HiltViewModel
class ScheduledViewModel @Inject constructor(
    private val scheduledRepository: ScheduledRepository,
    accountRepository: AccountRepository,
    private val sharedAccessState: SharedAccessState,
    prefs: UserPreferences
) : ViewModel() {

    val uiState: StateFlow<ScheduledUiState> = combine(
        scheduledRepository.getAll(),
        accountRepository.getAll(),
        sharedAccessState.ownerFilter,
        prefs.userIdFlow
    ) { items, accounts, filter, currentUserId ->
        val filteredItems = if (filter == null) items
        else {
            val ownedIds = accounts.filter { it.userId == filter }.map { it.id }.toSet()
            items.filter { it.accountId in ownedIds }
        }
        ScheduledUiState(items = filteredItems, accounts = accounts, isLoading = false, currentUserId = currentUserId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduledUiState())

    suspend fun delete(id: Long): Result<Unit> = runCatching { scheduledRepository.delete(id) }
}
