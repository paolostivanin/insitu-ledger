package com.insituledger.app.ui.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.domain.model.ScheduledTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduledUiState(
    val items: List<ScheduledTransaction> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class ScheduledViewModel @Inject constructor(
    private val scheduledRepository: ScheduledRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduledUiState())
    val uiState: StateFlow<ScheduledUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            scheduledRepository.getAll().collect { items ->
                _uiState.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { scheduledRepository.delete(id) }
    }
}
