package com.insituledger.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoriesUiState(
    val incomeCategories: List<Category> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
    val isReadOnly: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = sharedAccessState.selectedOwner
        .flatMapLatest { owner ->
            if (owner != null) {
                val categories = categoryRepository.listFromServer(owner.ownerId)
                flowOf(
                    CategoriesUiState(
                        incomeCategories = categories.filter { c -> c.type == "income" },
                        expenseCategories = categories.filter { c -> c.type == "expense" },
                        isLoading = false,
                        isReadOnly = owner.permission == "read"
                    )
                )
            } else {
                categoryRepository.getAll().map { categories ->
                    CategoriesUiState(
                        incomeCategories = categories.filter { c -> c.type == "income" },
                        expenseCategories = categories.filter { c -> c.type == "expense" },
                        isLoading = false,
                        isReadOnly = false
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoriesUiState())

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { categoryRepository.delete(id) }
    }
}
