package com.insituledger.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoriesUiState(
    val incomeCategories: List<Category> = emptyList(),
    val expenseCategories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
    val currentUserId: Long? = null
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    prefs: UserPreferences
) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = combine(
        categoryRepository.getAll(),
        prefs.userIdFlow
    ) { categories, currentUserId ->
        CategoriesUiState(
            incomeCategories = categories.filter { it.type == "income" },
            expenseCategories = categories.filter { it.type == "expense" },
            isLoading = false,
            currentUserId = currentUserId
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CategoriesUiState())

    fun delete(id: Long) {
        // Owner-only operation; backend enforces and the screen hides the
        // affordance for categories belonging to a co-owner.
        viewModelScope.launch { categoryRepository.delete(id) }
    }
}
