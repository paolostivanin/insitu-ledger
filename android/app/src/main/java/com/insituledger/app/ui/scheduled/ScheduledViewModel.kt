package com.insituledger.app.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.ScheduledTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduledUiState(
    val items: List<ScheduledTransaction> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = false
)

@HiltViewModel
class ScheduledViewModel @Inject constructor(
    private val scheduledRepository: ScheduledRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduledUiState())
    val uiState: StateFlow<ScheduledUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sharedAccessState.selectedOwner.collectLatest { owner ->
                _uiState.update { it.copy(isLoading = true) }
                if (owner != null) {
                    val items = scheduledRepository.listFromServer(owner.ownerId)
                    _uiState.update { it.copy(items = items, isLoading = false, isReadOnly = owner.permission == "read") }
                } else {
                    scheduledRepository.getAll().collect { items ->
                        _uiState.update { it.copy(items = items, isLoading = false, isReadOnly = false) }
                    }
                }
            }
        }
    }

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { scheduledRepository.delete(id) }
    }
}
