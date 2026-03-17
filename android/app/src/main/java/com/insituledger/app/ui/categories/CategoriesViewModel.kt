package com.insituledger.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoriesUiState(
    val incomeCategories: List<Category> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoriesUiState())
    val uiState: StateFlow<CategoriesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            categoryRepository.getAll().collect { categories ->
                _uiState.update {
                    it.copy(
                        incomeCategories = categories.filter { c -> c.type == "income" },
                        expenseCategories = categories.filter { c -> c.type == "expense" },
                        isLoading = false
                    )
                }
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { categoryRepository.delete(id) }
    }
}
