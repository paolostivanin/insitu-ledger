package com.insituledger.app.ui.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.remote.dto.SharedAccessDto
import com.insituledger.app.data.repository.SharedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharedUiState(
    val accesses: List<SharedAccessDto> = emptyList(),
    val isLoading: Boolean = true,
    val email: String = "",
    val permission: String = "read",
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SharedViewModel @Inject constructor(
    private val sharedRepository: SharedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = sharedRepository.listSharedAccess()
            _uiState.update { it.copy(accesses = list, isLoading = false) }
        }
    }

    fun updateEmail(email: String) { _uiState.update { it.copy(email = email) } }
    fun updatePermission(permission: String) { _uiState.update { it.copy(permission = permission) } }

    fun add() {
        val state = _uiState.value
        if (state.email.isBlank()) {
            _uiState.update { it.copy(error = "Email is required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            sharedRepository.createSharedAccess(state.email.trim(), state.permission)
                .onSuccess {
                    _uiState.update { it.copy(email = "", isSaving = false) }
                    load()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, error = e.message) }
                }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            sharedRepository.deleteSharedAccess(id)
            load()
        }
    }
}
