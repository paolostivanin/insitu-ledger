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
    val currentUserId: Long? = null
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
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager,
    private val sharedAccessState: SharedAccessState,
    prefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _filterAndSort = MutableStateFlow(FilterAndSort())
    private val _searchInput = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _searchInput.debounce(300).collectLatest { query ->
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

        // Load transactions reactively based on filters, sort, search, and owner filter
        viewModelScope.launch {
            combine(
                _filterAndSort,
                sharedAccessState.ownerFilter,
                accountRepository.getAll()
            ) { fs, filter, accounts -> Triple(fs, filter, accounts) }
                .flatMapLatest { (fs, filter, accounts) ->
                    val accountIds: Set<Long>? = filter?.let { f ->
                        accounts.filter { it.userId == f }.map { it.id }.toSet()
                    }
                    _uiState.update { it.copy(isLoading = true) }
                    val txnFlow = if (fs.searchQuery.isNotBlank()) {
                        transactionRepository.search(fs.searchQuery)
                    } else {
                        transactionRepository.getSorted(
                            from = fs.from, to = fs.to, categoryId = fs.categoryId,
                            sortBy = fs.sortBy, sortDir = fs.sortDir
                        )
                    }
                    txnFlow.map { txns ->
                        if (accountIds == null) txns else txns.filter { it.accountId in accountIds }
                    }
                }
                .collect { txns ->
                    _uiState.update { it.copy(transactions = txns, isLoading = false) }
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
