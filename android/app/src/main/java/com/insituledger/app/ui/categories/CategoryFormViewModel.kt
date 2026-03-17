package com.insituledger.app.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryFormUiState(
    val id: Long? = null,
    val name: String = "",
    val type: String = "expense",
    val parentId: Long? = null,
    val icon: String = "",
    val color: String = "",
    val allCategories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

@HiltViewModel
class CategoryFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val editId: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
    private val _uiState = MutableStateFlow(CategoryFormUiState(id = editId))
    val uiState: StateFlow<CategoryFormUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            categoryRepository.getAll().collect { cats ->
                _uiState.update { it.copy(allCategories = cats.filter { c -> c.parentId == null }) }
            }
        }
        if (editId != null) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val cat = categoryRepository.getById(editId)
                if (cat != null) {
                    _uiState.update {
                        it.copy(name = cat.name, type = cat.type, parentId = cat.parentId,
                            icon = cat.icon ?: "", color = cat.color ?: "", isLoading = false)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun updateName(name: String) { _uiState.update { it.copy(name = name) } }
    fun updateType(type: String) { _uiState.update { it.copy(type = type) } }
    fun updateParentId(id: Long?) { _uiState.update { it.copy(parentId = id) } }
    fun updateIcon(icon: String) { _uiState.update { it.copy(icon = icon) } }
    fun updateColor(color: String) { _uiState.update { it.copy(color = color) } }

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) {
            _uiState.update { it.copy(error = "Name is required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                if (editId != null) {
                    categoryRepository.update(editId, state.name, state.type, state.parentId,
                        state.icon.ifBlank { null }, state.color.ifBlank { null })
                } else {
                    categoryRepository.create(state.name, state.type, state.parentId,
                        state.icon.ifBlank { null }, state.color.ifBlank { null })
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
