package com.insituledger.app.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.ScheduledTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduledUiState(
    val items: List<ScheduledTransaction> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScheduledViewModel @Inject constructor(
    private val scheduledRepository: ScheduledRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    val uiState: StateFlow<ScheduledUiState> = sharedAccessState.selectedOwner
        .flatMapLatest { owner ->
            if (owner != null) {
                val items = scheduledRepository.listFromServer(owner.ownerId)
                // Screen-level "read-only" disables the FAB; per-row write checks
                // happen at delete time against the item's accountId.
                val anyWrite = owner.accounts.any { it.permission == "write" }
                flowOf(ScheduledUiState(items = items, isLoading = false, isReadOnly = !anyWrite))
            } else {
                scheduledRepository.getAll().map { items ->
                    ScheduledUiState(items = items, isLoading = false, isReadOnly = false)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScheduledUiState())

    fun delete(id: Long) {
        val item = uiState.value.items.find { it.id == id } ?: return
        if (!sharedAccessState.canWrite(item.accountId)) return
        viewModelScope.launch { scheduledRepository.delete(id) }
    }
}
