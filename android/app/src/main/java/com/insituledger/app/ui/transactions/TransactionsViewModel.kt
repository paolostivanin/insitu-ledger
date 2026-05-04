package com.insituledger.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val PAGE_SIZE = 100

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val filterFrom: String? = null,
    val filterTo: String? = null,
    val filterCategoryId: Long? = null,
    val sortBy: String = "date",
    val sortDir: String = "desc",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    val currentUserId: Long? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

data class FilterAndSort(
    val from: String? = null,
    val to: String? = null,
    val categoryId: Long? = null,
    val sortBy: String = "date",
    val sortDir: String = "desc",
    val searchQuery: String = ""
)

private data class PageRequest(
    val fs: FilterAndSort,
    val ownerFilter: Long?,
    val accounts: List<Account>,
    val pages: Int
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager,
    private val sharedAccessState: SharedAccessState,
    prefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _filterAndSort = MutableStateFlow(FilterAndSort())
    private val _searchInput = MutableStateFlow("")
    // Pages requested. Increments via loadMore(); resets to 1 whenever filters,
    // sort, or search change. Multiplied by PAGE_SIZE for the LIMIT.
    private val _pageCount = MutableStateFlow(1)

    init {
        viewModelScope.launch {
            _searchInput.debounce(300).collectLatest { query ->
                _pageCount.value = 1
                _filterAndSort.update { it.copy(searchQuery = query) }
            }
        }

        viewModelScope.launch {
            combine(
                categoryRepository.getAll(),
                accountRepository.getAll(),
                prefs.userIdFlow
            ) { cats, accounts, currentUserId ->
                _uiState.update {
                    it.copy(categories = cats, accounts = accounts, currentUserId = currentUserId)
                }
            }.collect()
        }

        // Load transactions reactively based on filters, sort, search, owner filter,
        // and the current page count. The growing-LIMIT pattern keeps the Flow
        // reactive (newly-inserted transactions still appear without re-pagination).
        viewModelScope.launch {
            combine(
                _filterAndSort,
                sharedAccessState.ownerFilter,
                accountRepository.getAll(),
                _pageCount
            ) { fs, filter, accounts, pages -> PageRequest(fs, filter, accounts, pages) }
                .flatMapLatest { req ->
                    val accountIds: Set<Long>? = req.ownerFilter?.let { f ->
                        req.accounts.filter { it.userId == f }.map { it.id }.toSet()
                    }
                    _uiState.update { it.copy(isLoading = true) }
                    val limit = req.pages * PAGE_SIZE
                    val txnFlow = if (req.fs.searchQuery.isNotBlank()) {
                        // Search ignores pagination — relatively bounded result set.
                        transactionRepository.search(req.fs.searchQuery)
                    } else {
                        transactionRepository.getSorted(
                            from = req.fs.from, to = req.fs.to, categoryId = req.fs.categoryId,
                            sortBy = req.fs.sortBy, sortDir = req.fs.sortDir,
                            limit = limit, offset = 0
                        )
                    }
                    txnFlow.map { txns ->
                        val visible = if (accountIds == null) txns else txns.filter { it.accountId in accountIds }
                        // hasMore is true when this page filled the LIMIT entirely
                        // (and we're not in search mode, which doesn't paginate).
                        val isSearch = req.fs.searchQuery.isNotBlank()
                        val more = !isSearch && txns.size >= limit
                        visible to more
                    }
                }
                .collect { (txns, more) ->
                    _uiState.update {
                        it.copy(transactions = txns, isLoading = false, isLoadingMore = false, hasMore = more)
                    }
                }
        }
    }

    fun setFilters(from: String?, to: String?, categoryId: Long?) {
        _uiState.update { it.copy(filterFrom = from, filterTo = to, filterCategoryId = categoryId) }
        _pageCount.value = 1
        _filterAndSort.update { it.copy(from = from, to = to, categoryId = categoryId) }
    }

    fun clearFilters() {
        setFilters(null, null, null)
    }

    fun setSort(sortBy: String, sortDir: String) {
        _uiState.update { it.copy(sortBy = sortBy, sortDir = sortDir) }
        _pageCount.value = 1
        _filterAndSort.update { it.copy(sortBy = sortBy, sortDir = sortDir) }
    }

    fun loadMore() {
        if (!_uiState.value.hasMore || _uiState.value.isLoadingMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        _pageCount.update { it + 1 }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            syncManager.syncNow()
            _uiState.update { it.copy(isRefreshing = false) }
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
            _pageCount.value = 1
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
        viewModelScope.launch {
            _uiState.value.selectedIds.forEach { id ->
                transactionRepository.delete(id)
            }
            clearSelection()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { transactionRepository.delete(id) }
    }
}
