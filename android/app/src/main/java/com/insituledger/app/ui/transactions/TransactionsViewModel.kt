package com.insituledger.app.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.BULK_EDIT_MAX_IDS
import com.insituledger.app.data.repository.BulkEditCounts
import com.insituledger.app.data.repository.BulkEditRepository
import com.insituledger.app.data.repository.BulkEditRequest
import com.insituledger.app.data.repository.BulkEditResult
import com.insituledger.app.data.repository.BulkEditSummary
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionFilter
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.reports.DateRangePreset
import com.insituledger.app.ui.reports.resolveRange
import com.insituledger.app.util.FieldLimits
import java.time.DayOfWeek
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L
private const val PAGE_SIZE = 100

const val MSG_TOO_MANY_MATCHES = "More than $BULK_EDIT_MAX_IDS entries match. Narrow the search to select them all."
const val MSG_SELECT_ALL_FAILED = "Couldn't select all matching entries. Try again."
const val MSG_DELETE_FAILED = "Couldn't delete the selected entries"

/** Selected rows a sync renamed, moved, deleted or remapped out of the current filter. */
fun leftOutMessage(n: Int): String =
    if (n == 1) "1 selected entry no longer matches the current filter and was left out"
    else "$n selected entries no longer match the current filter and were left out"

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    /** Schedules by id (soft-deleted included), for the series label on auto-added rows. */
    val schedules: Map<Long, ScheduledTransaction> = emptyMap(),
    val isLoading: Boolean = true,
    /** A reload after the first one: keep showing rows, show a thin progress bar. */
    val isUpdating: Boolean = false,
    val isRefreshing: Boolean = false,
    /** The date preset behind filterFrom/filterTo, for the chip label (null = custom or none). */
    val datePreset: DateRangePreset? = null,
    val filterFrom: String? = null,
    val filterTo: String? = null,
    val filterCategoryId: Long? = null,
    val sortBy: String = "date",
    val sortDir: String = "desc",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    /** Search matches the whole description instead of any part of it. */
    val exactMatch: Boolean = false,
    /** "Added by me": only entries the current user entered. */
    val onlyMine: Boolean = false,
    /** Any account is shared; gates the "Added by me" chip (otherwise every entry is yours). */
    val hasSharedAccounts: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val isSelectionMode: Boolean = false,
    /** Rows matching the current filters, counted when every loaded row is selected and more exist. */
    val matchingCount: Int? = null,
    /** The selection is every row the filters match, not only the loaded ones. */
    val allMatchingSelected: Boolean = false,
    val isSelectingAllMatching: Boolean = false,
    val selectAllError: String? = null,
    val bulkEdit: BulkEditState? = null,
    /** One-shot snackbar text after a bulk edit; cleared by [TransactionsViewModel.clearBulkEditMessage]. */
    val bulkEditMessage: String? = null,
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
    val searchQuery: String = "",
    val exactMatch: Boolean = false,
    val onlyMine: Boolean = false
)

/** The bulk-edit sheet: what is selected, what the user opted to change. */
data class BulkEditState(
    val ids: List<Long>,
    val summary: BulkEditSummary,
    /** Webapp mode: only synced categories may be picked. */
    val syncEnabled: Boolean,
    val renameEnabled: Boolean = false,
    val description: String = "",
    val suggestions: List<DescriptionSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val categoryEnabled: Boolean = false,
    val categoryId: Long? = null,
    val categoryType: String? = null,
    val updateSchedules: Boolean = false,
    val isApplying: Boolean = false,
    val error: String? = null,
    /** Shown at the top of the sheet, e.g. rows left out because they no longer match the filter. */
    val notice: String? = null
) {
    val descriptionError: String?
        get() = if (renameEnabled) FieldLimits.descriptionError(description.trim()) else null

    /** Why the chosen category can't also go to the linked schedules; null when it can. */
    val scheduleConflict: String?
        get() {
            if (!updateSchedules || !categoryEnabled || categoryType == null) return null
            if (summary.linkedScheduleIds.isEmpty()) return null
            if (summary.linkedScheduleOwners.any { it != summary.owner }) {
                return "A linked recurring schedule belongs to a different owner."
            }
            if (summary.linkedScheduleTypes.any { it != categoryType }) {
                return "A linked recurring schedule has a different type than the new category."
            }
            return null
        }

    val canApply: Boolean
        get() {
            if (isApplying || summary.liveCount == 0 || summary.serverBlockedReason != null) return false
            if (!renameEnabled && !categoryEnabled) return false
            if (renameEnabled && (description.isBlank() || descriptionError != null)) return false
            if (categoryEnabled && (summary.categoryBlockedReason != null || categoryId == null)) return false
            return scheduleConflict == null
        }

    /** Only the opted-in fields; a blocked category is never sent. */
    fun toRequest(): BulkEditRequest = BulkEditRequest(
        ids = ids,
        description = if (renameEnabled) description.trim() else null,
        categoryId = if (categoryEnabled && summary.categoryBlockedReason == null) categoryId else null,
        updateSchedules = updateSchedules && summary.linkedScheduleIds.isNotEmpty()
    )
}

/** Snackbar text for an applied bulk edit. */
fun bulkEditResultMessage(counts: BulkEditCounts): String {
    fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"
    fun schedules(n: Int) = if (n == 1) "1 schedule" else "$n schedules"
    val parts = mutableListOf("Updated ${entries(counts.updated)}")
    if (counts.unchanged > 0) parts += "${counts.unchanged} already up to date"
    if (counts.skipped > 0) parts += "${counts.skipped} skipped"
    var text = parts.joinToString(", ")
    if (counts.schedulesUpdated > 0) text += " and ${schedules(counts.schedulesUpdated)}"
    return text
}

/**
 * The filter the list actually applies. Exact match only counts with a search
 * term, and "Added by me" only with a user id and a shared account (without
 * sharing every entry is the user's, and the chip is hidden).
 */
internal fun effectiveFilter(
    fs: FilterAndSort,
    ownerFilter: Long?,
    accounts: List<Account>,
    userId: Long?
): TransactionFilter {
    val accountIds: Set<Long>? = ownerFilter?.let { f ->
        accounts.filter { it.userId == f }.map { it.id }.toSet()
    }
    val search = fs.searchQuery.ifBlank { null }
    val onlyMine = fs.onlyMine && userId != null && accounts.any { it.isShared }
    return TransactionFilter(
        from = fs.from,
        to = fs.to,
        categoryId = fs.categoryId,
        search = search,
        exactMatch = fs.exactMatch && search != null,
        accountIds = accountIds,
        onlyMine = onlyMine,
        currentUserId = if (onlyMine) userId else null
    )
}

private data class PageRequest(
    val fs: FilterAndSort,
    val ownerFilter: Long?,
    val accounts: List<Account>,
    val userId: Long?,
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
    private val prefs: UserPreferences,
    scheduledRepository: ScheduledRepository,
    private val bulkEditRepository: BulkEditRepository
) : ViewModel() {

    private val weekStart: StateFlow<DayOfWeek> = prefs.weekStartDayFlow
        .map { day -> if (day == "sunday") DayOfWeek.SUNDAY else DayOfWeek.MONDAY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DayOfWeek.MONDAY)
    private var hasLoaded = false

    private val _uiState = MutableStateFlow(TransactionsUiState())
    val uiState: StateFlow<TransactionsUiState> = _uiState.asStateFlow()

    private val _filterAndSort = MutableStateFlow(FilterAndSort())
    private val _searchInput = MutableStateFlow("")
    // Pages requested. Increments via loadMore(); resets to 1 whenever filters,
    // sort, or search change. Multiplied by PAGE_SIZE for the LIMIT.
    private val _pageCount = MutableStateFlow(1)

    // The filter behind the rows on screen. Select-all-matching and the
    // banner count use it, and a change clears the selection.
    private var currentFilter: TransactionFilter? = null
    // Bumped by every selection change (toggle, select all, clear): an
    // in-flight select-all-matching or count started for an earlier selection
    // must not land on a newer one.
    private var selectionToken = 0
    private var bulkAutocompleteJob: Job? = null

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
                prefs.userIdFlow,
                scheduledRepository.getAllById()
            ) { cats, accounts, currentUserId, schedules ->
                val anyShared = accounts.any { it.isShared }
                _uiState.update {
                    it.copy(
                        categories = cats, accounts = accounts, currentUserId = currentUserId, schedules = schedules,
                        hasSharedAccounts = anyShared,
                        // The chip disappears with the last share; don't leave
                        // an invisible filter switched on.
                        onlyMine = it.onlyMine && anyShared
                    )
                }
                if (!anyShared && _filterAndSort.value.onlyMine) {
                    _pageCount.value = 1
                    _filterAndSort.update { it.copy(onlyMine = false) }
                }
            }.collect()
        }

        // Load transactions reactively based on filters, sort, search, owner filter,
        // and the current page count. The growing-LIMIT pattern keeps the Flow
        // reactive (newly-inserted transactions still appear without re-pagination).
        viewModelScope.launch {
            combine(
                _filterAndSort,
                sharedAccessState.ownerFilter.onEach {
                    _pageCount.value = 1
                    clearSelection()
                },
                accountRepository.getAll(),
                prefs.userIdFlow,
                _pageCount
            ) { fs, filter, accounts, userId, pages -> PageRequest(fs, filter, accounts, userId, pages) }
                .flatMapLatest { req ->
                    val filter = effectiveFilter(req.fs, req.ownerFilter, req.accounts, req.userId)
                    // A selection only means something for the filter it was
                    // made under (select-all-matching especially). Sort and
                    // paging keep it.
                    if (filter != currentFilter) {
                        if (currentFilter != null) clearSelection()
                        currentFilter = filter
                    }
                    // Only the very first load swaps in the loading state; later
                    // reloads (filters, sort, paging) keep the rows and scroll.
                    _uiState.update { if (hasLoaded) it.copy(isUpdating = true) else it.copy(isLoading = true) }
                    val limit = req.pages * PAGE_SIZE
                    // Search is one more predicate on the same query, so it stacks
                    // with the date/category filters and the sort instead of
                    // replacing them, and it paginates like everything else.
                    val txnFlow = transactionRepository.getSorted(
                        from = filter.from, to = filter.to, categoryId = filter.categoryId,
                        search = filter.search,
                        sortBy = req.fs.sortBy, sortDir = req.fs.sortDir,
                        limit = limit + 1, offset = 0, accountIds = filter.accountIds,
                        exactMatch = filter.exactMatch,
                        onlyMineUserId = filter.currentUserId
                    )
                    txnFlow.map { txns ->
                        val visible = txns.take(limit)
                        // An extra row determines whether another page is available.
                        visible to (txns.size > limit)
                    }
                }
                .collect { (txns, more) ->
                    hasLoaded = true
                    _uiState.update {
                        it.copy(transactions = txns, isLoading = false, isUpdating = false, isLoadingMore = false, hasMore = more)
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
        _uiState.update { it.copy(datePreset = null, onlyMine = false) }
        _filterAndSort.update { it.copy(onlyMine = false) }
        setFilters(null, null, null)
    }

    /** Apply a date preset (null = any time); the category filter is kept. */
    fun setDatePreset(preset: DateRangePreset?) {
        val (from, to) = if (preset == null) null to null
        else resolveRange(preset, "", "", weekStart.value)
        _uiState.update { it.copy(datePreset = preset) }
        setFilters(from, to, _uiState.value.filterCategoryId)
    }

    /** Apply an explicit inclusive range ("yyyy-MM-dd"). */
    fun setCustomRange(from: String, to: String) {
        _uiState.update { it.copy(datePreset = DateRangePreset.CUSTOM) }
        setFilters(from, to, _uiState.value.filterCategoryId)
    }

    /** Filter by category (null = all); the date range is kept. */
    fun setCategoryFilter(categoryId: Long?) {
        val s = _uiState.value
        setFilters(s.filterFrom, s.filterTo, categoryId)
    }

    /** Search the whole description ("Stipendio" but not "Stipendio Lisa"). */
    fun setExactMatch(exact: Boolean) {
        _uiState.update { it.copy(exactMatch = exact) }
        _pageCount.value = 1
        _filterAndSort.update { it.copy(exactMatch = exact) }
    }

    /** "Added by me". Ignored (and the chip hidden) while no account is shared. */
    fun setOnlyMine(onlyMine: Boolean) {
        val value = onlyMine && _uiState.value.hasSharedAccounts
        _uiState.update { it.copy(onlyMine = value) }
        _pageCount.value = 1
        _filterAndSort.update { it.copy(onlyMine = value) }
    }

    // Search and filters narrow the same query, so the empty state offers to
    // drop both at once rather than making the user find each control.
    fun clearSearchAndFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "", isSearchActive = false, exactMatch = false, onlyMine = false,
                filterFrom = null, filterTo = null, filterCategoryId = null, datePreset = null
            )
        }
        _searchInput.value = ""
        _pageCount.value = 1
        _filterAndSort.update {
            FilterAndSort(sortBy = it.sortBy, sortDir = it.sortDir)
        }
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
        // The user is hand-picking now: a select-all-matching or count still
        // running for the previous selection must not overwrite this.
        selectionToken++
        _uiState.update {
            val newIds = it.selectedIds.toMutableSet()
            if (newIds.contains(id)) newIds.remove(id) else newIds.add(id)
            it.copy(
                selectedIds = newIds,
                isSelectionMode = newIds.isNotEmpty(),
                allMatchingSelected = false,
                isSelectingAllMatching = false,
                matchingCount = null,
                selectAllError = null
            )
        }
    }

    /** Selects the loaded rows; when more exist, counts the matches for the banner. */
    fun selectAll() {
        selectionToken++
        val token = selectionToken
        _uiState.update {
            it.copy(
                selectedIds = it.transactions.map { t -> t.id }.toSet(),
                isSelectionMode = true,
                allMatchingSelected = false,
                isSelectingAllMatching = false,
                matchingCount = null,
                selectAllError = null
            )
        }
        val filter = currentFilter ?: return
        if (!_uiState.value.hasMore) return
        viewModelScope.launch {
            val count = try {
                transactionRepository.countMatching(filter)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Only the banner's number is missing; "Select all matching"
                // still works without it.
                null
            }
            if (count != null && filter == currentFilter && token == selectionToken) {
                _uiState.update { it.copy(matchingCount = count) }
            }
        }
    }

    /**
     * Selects every row the current filters match, loaded or not, using the
     * same WHERE clause as the list. Discarded if the filters or the selection
     * changed while it ran; refused above the bulk-edit cap.
     */
    fun selectAllMatching() {
        val filter = currentFilter ?: return
        val token = selectionToken
        _uiState.update { it.copy(isSelectingAllMatching = true, selectAllError = null) }
        viewModelScope.launch {
            val ids = try {
                transactionRepository.getMatchingIds(filter, BULK_EDIT_MAX_IDS + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == selectionToken) {
                    _uiState.update { it.copy(isSelectingAllMatching = false, selectAllError = MSG_SELECT_ALL_FAILED) }
                }
                return@launch
            }
            if (filter != currentFilter || token != selectionToken) {
                // A newer filter or selection owns the state now (and already
                // reset isSelectingAllMatching).
                return@launch
            }
            if (ids.size > BULK_EDIT_MAX_IDS) {
                _uiState.update { it.copy(isSelectingAllMatching = false, selectAllError = MSG_TOO_MANY_MATCHES) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    selectedIds = ids.toSet(),
                    isSelectionMode = ids.isNotEmpty(),
                    allMatchingSelected = ids.isNotEmpty(),
                    matchingCount = ids.size,
                    isSelectingAllMatching = false
                )
            }
        }
    }

    fun clearSelection() {
        selectionToken++
        bulkAutocompleteJob?.cancel()
        _uiState.update {
            it.copy(
                selectedIds = emptySet(), isSelectionMode = false,
                matchingCount = null, allMatchingSelected = false,
                isSelectingAllMatching = false, selectAllError = null,
                // The sheet edits the selection; without one it has nothing to apply to.
                bulkEdit = if (it.bulkEdit?.isApplying == true) it.bulkEdit else null
            )
        }
    }

    /**
     * Deletes the selection, minus any row that no longer matches the current
     * filter, in one transaction with one sync after it.
     */
    fun deleteSelected() {
        val selected = _uiState.value.selectedIds
        if (selected.isEmpty()) return
        val filter = filterForSelection()
        viewModelScope.launch {
            val message = try {
                val kept = transactionRepository.filterIds(filter, selected)
                transactionRepository.deleteMany(kept)
                (selected.size - kept.size).takeIf { it > 0 }?.let(::leftOutMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                MSG_DELETE_FAILED
            }
            clearSelection()
            if (message != null) _uiState.update { it.copy(bulkEditMessage = message) }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { transactionRepository.delete(id) }
    }

    // ---- Bulk edit ----

    fun openBulkEdit() {
        val selected = _uiState.value.selectedIds
        if (selected.isEmpty()) return
        val filter = filterForSelection()
        val token = selectionToken
        viewModelScope.launch {
            val (ids, summary) = try {
                val kept = transactionRepository.filterIds(filter, selected)
                kept to (if (kept.isEmpty()) null else bulkEditRepository.summarize(kept))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(bulkEditMessage = "Couldn't read the selected entries") }
                return@launch
            }
            // The selection moved on while it was read.
            if (token != selectionToken || _uiState.value.selectedIds != selected) return@launch
            val leftOut = selected.size - ids.size
            if (leftOut > 0) {
                // Narrow the selection to what the sheet will edit.
                selectionToken++
                _uiState.update {
                    it.copy(
                        selectedIds = ids.toSet(),
                        isSelectionMode = ids.isNotEmpty(),
                        allMatchingSelected = false,
                        isSelectingAllMatching = false,
                        matchingCount = null
                    )
                }
            }
            val notice = if (leftOut > 0) leftOutMessage(leftOut).replaceFirstChar { it.uppercase() } + "." else null
            if (summary == null) {
                // Nothing left to edit: no sheet, just say why.
                _uiState.update { it.copy(bulkEditMessage = notice) }
                return@launch
            }
            _uiState.update {
                it.copy(bulkEdit = BulkEditState(ids = ids, summary = summary, syncEnabled = isSyncEnabled(), notice = notice))
            }
        }
    }

    fun dismissBulkEdit() {
        bulkAutocompleteJob?.cancel()
        _uiState.update { if (it.bulkEdit?.isApplying == true) it else it.copy(bulkEdit = null) }
    }

    fun setBulkRenameEnabled(enabled: Boolean) = updateBulk { it.copy(renameEnabled = enabled, error = null) }

    fun setBulkDescription(description: String) {
        updateBulk { it.copy(description = description, error = null) }
        bulkAutocompleteJob?.cancel()
        if (description.length < 2) {
            updateBulk { it.copy(suggestions = emptyList(), showSuggestions = false) }
            return
        }
        bulkAutocompleteJob = viewModelScope.launch {
            delay(200)
            val results = try {
                transactionRepository.autocomplete(description)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            updateBulk {
                it.copy(
                    suggestions = results.map { (d, c) -> DescriptionSuggestion(d, c) },
                    showSuggestions = results.isNotEmpty()
                )
            }
        }
    }

    /** Takes the suggested name only; the category is a separate, explicit choice. */
    fun selectBulkSuggestion(suggestion: DescriptionSuggestion) {
        bulkAutocompleteJob?.cancel()
        updateBulk {
            it.copy(description = suggestion.description, suggestions = emptyList(), showSuggestions = false, error = null)
        }
    }

    fun dismissBulkSuggestions() = updateBulk { it.copy(showSuggestions = false) }

    fun setBulkCategoryEnabled(enabled: Boolean) = updateBulk { it.copy(categoryEnabled = enabled, error = null) }

    fun setBulkCategory(categoryId: Long) {
        val type = _uiState.value.categories.find { it.id == categoryId }?.type
        updateBulk { it.copy(categoryId = categoryId, categoryType = type, error = null) }
    }

    fun setBulkUpdateSchedules(update: Boolean) = updateBulk { it.copy(updateSchedules = update, error = null) }

    fun applyBulkEdit() {
        val state = _uiState.value.bulkEdit ?: return
        if (!state.canApply) return
        bulkAutocompleteJob?.cancel()
        val filter = filterForSelection()
        updateBulk { it.copy(isApplying = true, error = null, showSuggestions = false) }
        viewModelScope.launch {
            var leftOut = 0
            // null = every selected row has left the filter: nothing to edit.
            val result: BulkEditResult? = try {
                // A sync since the sheet opened may have moved rows out of the
                // filter; edit only what still matches.
                val kept = transactionRepository.filterIds(filter, state.ids)
                leftOut = state.ids.size - kept.size
                if (kept.isEmpty()) null
                else bulkEditRepository.apply(state.toRequest().copy(ids = kept))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BulkEditResult.Rejected(e.message ?: "Couldn't apply the changes")
            }
            when (result) {
                null -> {
                    _uiState.update { it.copy(bulkEdit = null, bulkEditMessage = leftOutMessage(leftOut)) }
                    clearSelection()
                }
                is BulkEditResult.Applied -> {
                    var message = bulkEditResultMessage(result.counts)
                    if (leftOut > 0) message += ". " + leftOutMessage(leftOut).replaceFirstChar { it.uppercase() }
                    _uiState.update { it.copy(bulkEdit = null, bulkEditMessage = message) }
                    clearSelection()
                }
                is BulkEditResult.Rejected -> {
                    // Nothing was written: keep the sheet and the selection so
                    // the user can adjust and retry. If the selection went away
                    // meanwhile (filters changed), close the sheet instead.
                    val message = result.message.replaceFirstChar { it.uppercase() }
                    _uiState.update {
                        if (it.bulkEdit != null && it.isSelectionMode) {
                            it.copy(bulkEdit = it.bulkEdit.copy(isApplying = false, error = message))
                        } else {
                            it.copy(bulkEdit = null, bulkEditMessage = message)
                        }
                    }
                }
            }
        }
    }

    fun clearBulkEditMessage() {
        _uiState.update { it.copy(bulkEditMessage = null) }
    }

    private fun isSyncEnabled() = prefs.getSyncModeImmediate() == "webapp"

    // The filter the selection was made under. Before the first load there is
    // none; every live row then counts.
    private fun filterForSelection(): TransactionFilter = currentFilter ?: TransactionFilter()

    private inline fun updateBulk(crossinline transform: (BulkEditState) -> BulkEditState) {
        _uiState.update { s -> s.bulkEdit?.let { s.copy(bulkEdit = transform(it)) } ?: s }
    }
}
