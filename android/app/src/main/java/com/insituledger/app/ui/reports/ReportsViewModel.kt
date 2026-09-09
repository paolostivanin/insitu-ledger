package com.insituledger.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.CategoryBreakdownRow
import com.insituledger.app.data.local.db.dao.CurrencySummaryRow
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class CategorySummary(
    val category: Category,
    val total: Double
)

/**
 * Declared in the order the chips are drawn — the chip row iterates `entries`,
 * so this list *is* the ordering. Nothing persists the ordinal, so it is safe
 * to reorder when adding a preset.
 */
enum class DateRangePreset {
    THIS_WEEK, THIS_MONTH, THIS_YEAR,
    LAST_WEEK, LAST_MONTH, LAST_3_MONTHS, LAST_YEAR,
    ALL_TIME, CUSTOM
}

enum class CategoryGrouping { CATEGORY, PARENT }

private const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Build the category breakdown from raw per-(category, type) sums.
 *
 * In PARENT mode each row is attributed to its parent, so a parent's figure is
 * its own transactions plus every child's. `type` stays in the bucket key:
 * the category form lets you parent an income category under an expense one,
 * and merging those into one number would be meaningless.
 *
 * A child whose parent no longer exists falls back to itself rather than
 * vanishing — same rule the backend applies to soft-deleted parents.
 *
 * Pure and top-level so it can be tested without Hilt or a Room instance.
 */
internal fun buildBreakdown(
    rows: List<CategoryBreakdownRow>,
    categories: List<Category>,
    grouping: CategoryGrouping
): List<CategorySummary> {
    val byId = categories.associateBy { it.id }
    return rows
        .mapNotNull { row ->
            val cat = byId[row.categoryId] ?: return@mapNotNull null
            val effective = if (grouping == CategoryGrouping.PARENT) {
                cat.parentId?.let { byId[it] } ?: cat
            } else {
                cat
            }
            Triple(effective, row.type, row.total)
        }
        .groupBy { (effective, type, _) -> effective.id to type }
        .map { (_, group) -> CategorySummary(group.first().first, group.sumOf { it.third }) }
        .sortedByDescending { it.total }
}

data class ReportsUiState(
    val categories: List<Category> = emptyList(),
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val categoryBreakdown: List<CategorySummary> = emptyList(),
    val grouping: CategoryGrouping = CategoryGrouping.CATEGORY,
    val dateRangePreset: DateRangePreset = DateRangePreset.THIS_MONTH,
    val customFrom: String = "",
    val customTo: String = "",
    val isLoading: Boolean = true,
    val selectedCategory: Category? = null,
    val selectedCategoryTransactions: List<Transaction> = emptyList(),
    val selectedCategoryTotal: Double = 0.0,
    val searchQuery: String = "",
    val searchSummary: List<CurrencySummaryRow> = emptyList(),
    val isSearching: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private val categories: StateFlow<List<Category>> = categoryRepository.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val weekStart: StateFlow<DayOfWeek> = prefs.weekStartDayFlow
        .map { day -> if (day == "sunday") DayOfWeek.SUNDAY else DayOfWeek.MONDAY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayOfWeek.MONDAY)

    private var weekStartDay: DayOfWeek = DayOfWeek.MONDAY

    // Cancelled and restarted on every keystroke, so only the pause at the end
    // of typing reaches the database.
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            categories.collect { cats ->
                _uiState.update { it.copy(categories = cats) }
                loadReport()
            }
        }

        viewModelScope.launch {
            weekStart.collect { day ->
                weekStartDay = day
                loadReport()
                // Moves the THIS_WEEK / LAST_WEEK bounds, so an active search
                // has to be re-run against them too.
                runSearch(debounce = false)
            }
        }
    }

    fun setDateRangePreset(preset: DateRangePreset) {
        _uiState.update { it.copy(dateRangePreset = preset) }
        viewModelScope.launch { loadReport() }
        runSearch(debounce = false)
    }

    fun setGrouping(grouping: CategoryGrouping) {
        _uiState.update { it.copy(grouping = grouping) }
        viewModelScope.launch { loadReport() }
    }

    /**
     * Free-text search over transaction descriptions, totalled per currency.
     *
     * Scoped by the same date preset as the charts — one date control drives
     * the whole screen. Looking up a trip that ended months ago is the case
     * that motivated an all-time search, and it is handled by the ALL_TIME
     * preset plus the "Search all dates" button on the empty state.
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        runSearch(debounce = true)
    }

    fun setCustomDateRange(from: String, to: String) {
        // A backwards range matches nothing and reads as "no results" rather
        // than "you filled the fields in the wrong order", so take it as meant.
        val reversed = from.isNotBlank() && to.isNotBlank() && from > to
        val start = if (reversed) to else from
        val end = if (reversed) from else to
        _uiState.update {
            it.copy(customFrom = start, customTo = end, dateRangePreset = DateRangePreset.CUSTOM)
        }
        viewModelScope.launch { loadReport() }
        runSearch(debounce = false)
    }

    /**
     * Run (or re-run) the search summary for the current query and date range.
     *
     * Debounced only when driven by typing — a chip tap is a single deliberate
     * action, so it queries straight away.
     */
    private fun runSearch(debounce: Boolean) {
        searchJob?.cancel()
        if (_uiState.value.searchQuery.isBlank()) {
            // Clear straight away rather than after the debounce — a stale
            // total under an empty box reads as a result for "everything".
            // Also keeps a blank query out of the DB, where it would become
            // LIKE '%%' and total the whole table.
            _uiState.update { it.copy(searchSummary = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            // Read both after the delay: a chip tap mid-debounce must not pair
            // a stale bound with a fresh term.
            val query = _uiState.value.searchQuery
            val (from, to) = resolveDateRange()
            try {
                val rows = transactionRepository.searchSummary(query, from, to)
                ensureActive()
                _uiState.update { it.copy(searchSummary = rows, isSearching = false) }
            } catch (e: CancellationException) {
                // A newer search is already in flight and owns the flag.
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(searchSummary = emptyList(), isSearching = false) }
            }
        }
    }

    fun selectCategory(category: Category) {
        viewModelScope.launch {
            val (from, to) = resolveDateRange()
            val txns = transactionRepository.getFilteredSync(from, to, category.id)
            val total = txns.sumOf { it.amount }
            _uiState.update {
                it.copy(
                    selectedCategory = category,
                    selectedCategoryTransactions = txns,
                    selectedCategoryTotal = total
                )
            }
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(selectedCategory = null, selectedCategoryTransactions = emptyList(), selectedCategoryTotal = 0.0)
        }
    }

    private suspend fun loadReport() {
        _uiState.update { it.copy(isLoading = true) }
        val (from, to) = resolveDateRange()
        val breakdownRows = transactionRepository.getCategoryBreakdown(from, to)

        val totalIncome = breakdownRows.filter { it.type == "income" }.sumOf { it.total }
        val totalExpense = breakdownRows.filter { it.type == "expense" }.sumOf { it.total }

        val breakdown = buildBreakdown(
            breakdownRows,
            _uiState.value.categories,
            _uiState.value.grouping
        )

        _uiState.update {
            it.copy(
                totalIncome = totalIncome,
                totalExpense = totalExpense,
                categoryBreakdown = breakdown,
                isLoading = false
            )
        }
    }

    private fun resolveDateRange(): Pair<String?, String?> {
        val state = _uiState.value
        return resolveRange(state.dateRangePreset, state.customFrom, state.customTo, weekStartDay)
    }
}

/**
 * Resolve a preset to the bare `YYYY-MM-DD` bounds the queries expect. Both
 * bounds are inclusive; `null` means unbounded.
 *
 * The "this …" presets stop at today rather than at the end of the period, the
 * same way the web does (`frontend/src/lib/reportPeriod.ts`). Future-dated rows
 * — only reachable by editing a date forward, CSV import or restore, since the
 * create path converts them to scheduled entries — therefore fall outside them
 * and show up under ALL_TIME.
 *
 * Pure and top-level so it can be tested without Hilt or a Room instance.
 */
internal fun resolveRange(
    preset: DateRangePreset,
    customFrom: String,
    customTo: String,
    weekStart: DayOfWeek,
    today: LocalDate = LocalDate.now()
): Pair<String?, String?> {
    val fmt = DateTimeFormatter.ISO_LOCAL_DATE
    return when (preset) {
        DateRangePreset.THIS_WEEK -> {
            val weekStartDate = today.with(TemporalAdjusters.previousOrSame(weekStart))
            weekStartDate.format(fmt) to today.format(fmt)
        }
        DateRangePreset.THIS_MONTH -> {
            today.withDayOfMonth(1).format(fmt) to today.format(fmt)
        }
        DateRangePreset.THIS_YEAR -> {
            LocalDate.of(today.year, 1, 1).format(fmt) to today.format(fmt)
        }
        DateRangePreset.LAST_WEEK -> {
            val thisWeekStart = today.with(TemporalAdjusters.previousOrSame(weekStart))
            val lastWeekStart = thisWeekStart.minusWeeks(1)
            val lastWeekEnd = thisWeekStart.minusDays(1)
            lastWeekStart.format(fmt) to lastWeekEnd.format(fmt)
        }
        DateRangePreset.LAST_MONTH -> {
            val lastMonth = YearMonth.from(today).minusMonths(1)
            lastMonth.atDay(1).format(fmt) to lastMonth.atEndOfMonth().format(fmt)
        }
        DateRangePreset.LAST_3_MONTHS -> {
            val threeMonthsAgo = YearMonth.from(today).minusMonths(3)
            val lastMonth = YearMonth.from(today).minusMonths(1)
            threeMonthsAgo.atDay(1).format(fmt) to lastMonth.atEndOfMonth().format(fmt)
        }
        DateRangePreset.LAST_YEAR -> {
            val lastYear = today.year - 1
            LocalDate.of(lastYear, 1, 1).format(fmt) to LocalDate.of(lastYear, 12, 31).format(fmt)
        }
        DateRangePreset.ALL_TIME -> null to null
        DateRangePreset.CUSTOM -> customFrom.ifBlank { null } to customTo.ifBlank { null }
    }
}
