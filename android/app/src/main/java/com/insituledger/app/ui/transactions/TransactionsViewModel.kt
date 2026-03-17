package com.insituledger.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
    val filterFrom: String? = null,
    val filterTo: String? = null,
    val filterCategoryId: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _filters = MutableStateFlow(Triple<String?, String?, Long?>(null, null, null))

    init {
        viewModelScope.launch {
            categoryRepository.getAll().collect { cats ->
                _uiState.update { it.copy(categories = cats) }
            }
        }

        viewModelScope.launch {
            _filters.flatMapLatest { (from, to, catId) ->
                transactionRepository.getFiltered(from = from, to = to, categoryId = catId)
            }.collect { txns ->
                _uiState.update { it.copy(transactions = txns, isLoading = false) }
            }
        }
    }

    fun setFilters(from: String?, to: String?, categoryId: Long?) {
        _uiState.update { it.copy(filterFrom = from, filterTo = to, filterCategoryId = categoryId) }
        _filters.value = Triple(from, to, categoryId)
    }

    fun clearFilters() {
        setFilters(null, null, null)
    }

    fun delete(id: Long) {
        viewModelScope.launch { transactionRepository.delete(id) }
    }
}
