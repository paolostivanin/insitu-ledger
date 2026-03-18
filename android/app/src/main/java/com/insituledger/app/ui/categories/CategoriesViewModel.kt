package com.insituledger.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoriesUiState(
    val incomeCategories: List<Category> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = false
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            sharedAccessState.selectedOwner.collectLatest { owner ->
                _uiState.update { it.copy(isLoading = true) }
                if (owner != null) {
                    val categories = categoryRepository.listFromServer(owner.ownerId)
                    _uiState.update {
                        it.copy(
                            incomeCategories = categories.filter { c -> c.type == "income" },
                            expenseCategories = categories.filter { c -> c.type == "expense" },
                            isLoading = false,
                            isReadOnly = owner.permission == "read"
                        )
                    }
                } else {
                    categoryRepository.getAll().collect { categories ->
                        _uiState.update {
                            it.copy(
                                incomeCategories = categories.filter { c -> c.type == "income" },
                                expenseCategories = categories.filter { c -> c.type == "expense" },
                                isLoading = false,
                                isReadOnly = false
                            )
                        }
                    }
                }
            }
        }
    }

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { categoryRepository.delete(id) }
    }
}
