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
    /** Every category (all levels, both types). */
    val allCategories: List<Category> = emptyList(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    val error: String? = null,
    val saved: Boolean = false
) {
    /** Categories this one may be filed under. */
    val parentOptions: List<Category> get() = categoryParentOptions(allCategories, type, id)

    /** A category with subcategories stays top-level, so the tree never gets a third level. */
    val hasSubcategories: Boolean get() = categoryHasSubcategories(allCategories, id)
}

/**
 * Parents offered by the form: top-level categories of the same type, never
 * the category itself. Only two levels exist, so a subcategory can't become a
 * parent (which used to hide grandchildren) and no cycle can form.
 *
 * Pure and top-level so it can be tested without Hilt.
 */
internal fun categoryParentOptions(all: List<Category>, type: String, selfId: Long?): List<Category> =
    all.filter { it.parentId == null && it.type == type && it.id != selfId }

internal fun categoryHasSubcategories(all: List<Category>, selfId: Long?): Boolean =
    selfId != null && all.any { it.parentId == selfId && it.id != selfId }

/**
 * The parent to keep after switching to [newType]: kept only when it is a
 * known category of that type, otherwise cleared so an income category never
 * ends up filed under an expense one.
 */
internal fun parentAfterTypeChange(all: List<Category>, parentId: Long?, newType: String): Long? =
    parentId?.takeIf { pid -> all.any { it.id == pid && it.type == newType } }

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
                _uiState.update { it.copy(allCategories = cats) }
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

    fun updateName(name: String) { _uiState.update { it.copy(name = name, nameError = null) } }
    fun updateType(type: String) {
        _uiState.update {
            if (it.type == type) it
            else it.copy(type = type, parentId = parentAfterTypeChange(it.allCategories, it.parentId, type))
        }
    }
    fun updateParentId(id: Long?) { _uiState.update { it.copy(parentId = id) } }
    fun updateIcon(icon: String) { _uiState.update { it.copy(icon = icon) } }
    fun updateColor(color: String) { _uiState.update { it.copy(color = color) } }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        if (state.name.isBlank()) {
            _uiState.update { it.copy(nameError = "Name is required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
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
                _uiState.update { it.copy(isSaving = false, error = e.message ?: "Could not save the category") }
            }
        }
    }
}
