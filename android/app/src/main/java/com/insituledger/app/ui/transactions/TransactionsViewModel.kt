package com.insituledger.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
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
    val isRefreshing: Boolean = false,
    val filterFrom: String? = null,
    val filterTo: String? = null,
    val filterCategoryId: Long? = null,
    val sortBy: String = "date",
    val sortDir: String = "desc",
    val isReadOnly: Boolean = false
)

data class FilterAndSort(
    val from: String? = null,
    val to: String? = null,
    val categoryId: Long? = null,
    val sortBy: String = "date",
    val sortDir: String = "desc"
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val syncManager: SyncManager,
    private val sharedAccessState: SharedAccessState
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _filterAndSort = MutableStateFlow(FilterAndSort())

    init {
        // Load categories - either from local DB or server depending on shared state
        viewModelScope.launch {
            sharedAccessState.selectedOwner.collectLatest { owner ->
                if (owner != null) {
                    val cats = categoryRepository.listFromServer(owner.ownerId)
                    _uiState.update { it.copy(categories = cats, isReadOnly = owner.permission == "read") }
                } else {
                    categoryRepository.getAll().collect { cats ->
                        _uiState.update { it.copy(categories = cats, isReadOnly = false) }
                    }
                }
            }
        }

        // Load transactions reactively based on filters, sort, and shared owner
        viewModelScope.launch {
            combine(
                _filterAndSort,
                sharedAccessState.selectedOwner
            ) { fs, owner -> Pair(fs, owner) }
                .collectLatest { (fs, owner) ->
                    _uiState.update { it.copy(isLoading = true) }
                    if (owner != null) {
                        val txns = transactionRepository.listFromServer(
                            ownerId = owner.ownerId,
                            from = fs.from, to = fs.to, categoryId = fs.categoryId,
                            sortBy = fs.sortBy, sortDir = fs.sortDir
                        )
                        _uiState.update { it.copy(transactions = txns, isLoading = false) }
                    } else {
                        transactionRepository.getSorted(
                            from = fs.from, to = fs.to, categoryId = fs.categoryId,
                            sortBy = fs.sortBy, sortDir = fs.sortDir
                        ).collect { txns ->
                            _uiState.update { it.copy(transactions = txns, isLoading = false) }
                        }
                    }
                }
        }
    }

    fun setFilters(from: String?, to: String?, categoryId: Long?) {
        _uiState.update { it.copy(filterFrom = from, filterTo = to, filterCategoryId = categoryId) }
        _filterAndSort.update { it.copy(from = from, to = to, categoryId = categoryId) }
    }

    fun clearFilters() {
        setFilters(null, null, null)
    }

    fun setSort(sortBy: String, sortDir: String) {
        _uiState.update { it.copy(sortBy = sortBy, sortDir = sortDir) }
        _filterAndSort.update { it.copy(sortBy = sortBy, sortDir = sortDir) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val owner = sharedAccessState.selectedOwner.value
            if (owner != null) {
                val fs = _filterAndSort.value
                val txns = transactionRepository.listFromServer(
                    ownerId = owner.ownerId,
                    from = fs.from, to = fs.to, categoryId = fs.categoryId,
                    sortBy = fs.sortBy, sortDir = fs.sortDir
                )
                _uiState.update { it.copy(transactions = txns, isRefreshing = false) }
            } else {
                syncManager.syncNow()
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { transactionRepository.delete(id) }
    }
}
