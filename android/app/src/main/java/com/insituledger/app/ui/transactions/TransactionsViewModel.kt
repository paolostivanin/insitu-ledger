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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

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
    val isReadOnly: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false
)

data class FilterAndSort(
    val from: String? = null,
    val to: String? = null,
    val categoryId: Long? = null,
    val sortBy: String = "date",
    val sortDir: String = "desc",
    val searchQuery: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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
    private val _searchInput = MutableStateFlow("")

    // Categories flow: stops upstream collection when UI is in background
    private val categoriesFlow: StateFlow<Pair<List<Category>, Boolean>> = sharedAccessState.selectedOwner
        .flatMapLatest { owner ->
            if (owner != null) {
                flowOf(categoryRepository.listFromServer(owner.ownerId) to (owner.permission == "read"))
            } else {
                categoryRepository.getAll().map { cats -> cats to false }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList<Category>() to false)

    init {
        viewModelScope.launch {
            _searchInput.debounce(300).collectLatest { query ->
                _filterAndSort.update { it.copy(searchQuery = query) }
            }
        }

        // Sync categories into uiState
        viewModelScope.launch {
            categoriesFlow.collect { (cats, readOnly) ->
                _uiState.update { it.copy(categories = cats, isReadOnly = readOnly) }
            }
        }

        // Load transactions reactively based on filters, sort, search, and shared owner
        viewModelScope.launch {
            combine(
                _filterAndSort,
                sharedAccessState.selectedOwner
            ) { fs, owner -> Pair(fs, owner) }
                .collectLatest { (fs, owner) ->
                    _uiState.update { it.copy(isLoading = true) }
                    if (fs.searchQuery.isNotBlank() && owner == null) {
                        transactionRepository.search(fs.searchQuery).collect { txns ->
                            _uiState.update { it.copy(transactions = txns, isLoading = false) }
                        }
                    } else if (owner != null) {
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

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        _searchInput.value = query
    }

    fun toggleSearch() {
        val newActive = !_uiState.value.isSearchActive
        _uiState.update { it.copy(isSearchActive = newActive, searchQuery = if (!newActive) "" else it.searchQuery) }
        if (!newActive) {
            _searchInput.value = ""
            _filterAndSort.update { it.copy(searchQuery = "") }
        }
    }

    fun toggleSelect(id: Long) {
        _uiState.update {
            val newIds = it.selectedIds.toMutableSet()
            if (newIds.contains(id)) newIds.remove(id) else newIds.add(id)
            it.copy(selectedIds = newIds, isSelectionMode = newIds.isNotEmpty())
        }
    }

    fun selectAll() {
        _uiState.update {
            it.copy(selectedIds = it.transactions.map { t -> t.id }.toSet(), isSelectionMode = true)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedIds = emptySet(), isSelectionMode = false) }
    }

    fun deleteSelected() {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch {
            _uiState.value.selectedIds.forEach { id -> transactionRepository.delete(id) }
            clearSelection()
        }
    }

    fun delete(id: Long) {
        if (sharedAccessState.isReadOnly) return
        viewModelScope.launch { transactionRepository.delete(id) }
    }
}
