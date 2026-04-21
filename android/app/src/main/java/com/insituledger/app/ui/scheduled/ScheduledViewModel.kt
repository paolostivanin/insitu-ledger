package com.insituledger.app.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val isLoading: Boolean = true
)

@HiltViewModel
class ScheduledViewModel @Inject constructor(
    private val scheduledRepository: ScheduledRepository,
    accountRepository: AccountRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    val uiState: StateFlow<ScheduledUiState> = combine(
        scheduledRepository.getAll(),
        accountRepository.getAll(),
        sharedAccessState.ownerFilter
    ) { items, accounts, filter ->
        val filteredItems = if (filter == null) items
        else {
            val ownedIds = accounts.filter { it.userId == filter }.map { it.id }.toSet()
            items.filter { it.accountId in ownedIds }
        }
        ScheduledUiState(items = filteredItems, accounts = accounts, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduledUiState())

    fun delete(id: Long) {
        viewModelScope.launch { scheduledRepository.delete(id) }
    }
}
