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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class DateRangePreset { THIS_WEEK, THIS_MONTH, LAST_WEEK, LAST_MONTH, LAST_3_MONTHS, LAST_YEAR, CUSTOM }

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
            }
        }
    }

    fun setDateRangePreset(preset: DateRangePreset) {
        _uiState.update { it.copy(dateRangePreset = preset) }
        viewModelScope.launch { loadReport() }
    }

    fun setGrouping(grouping: CategoryGrouping) {
        _uiState.update { it.copy(grouping = grouping) }
        viewModelScope.launch { loadReport() }
    }

    /**
     * Free-text search over transaction descriptions, totalled per currency.
     *
     * Deliberately all-time and independent of the date-range preset above:
     * you look a trip up months after it ended, and the default THIS_MONTH
     * would silently return nothing.
     */
    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            // Clear straight away rather than after the debounce — a stale
            // total under an empty box reads as a result for "everything".
            _uiState.update { it.copy(searchSummary = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val rows = transactionRepository.searchSummary(query)
            _uiState.update { it.copy(searchSummary = rows, isSearching = false) }
        }
    }

    fun setCustomDateRange(from: String, to: String) {
        _uiState.update { it.copy(customFrom = from, customTo = to, dateRangePreset = DateRangePreset.CUSTOM) }
        viewModelScope.launch { loadReport() }
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
        val now = LocalDate.now()
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        return when (state.dateRangePreset) {
            DateRangePreset.THIS_WEEK -> {
                val weekStart = now.with(TemporalAdjusters.previousOrSame(weekStartDay))
                weekStart.format(fmt) to now.format(fmt)
            }
            DateRangePreset.THIS_MONTH -> {
                now.withDayOfMonth(1).format(fmt) to now.format(fmt)
            }
            DateRangePreset.LAST_WEEK -> {
                val thisWeekStart = now.with(TemporalAdjusters.previousOrSame(weekStartDay))
                val lastWeekStart = thisWeekStart.minusWeeks(1)
                val lastWeekEnd = thisWeekStart.minusDays(1)
                lastWeekStart.format(fmt) to lastWeekEnd.format(fmt)
            }
            DateRangePreset.LAST_MONTH -> {
                val lastMonth = YearMonth.from(now).minusMonths(1)
                lastMonth.atDay(1).format(fmt) to lastMonth.atEndOfMonth().format(fmt)
            }
            DateRangePreset.LAST_3_MONTHS -> {
                val threeMonthsAgo = YearMonth.from(now).minusMonths(3)
                val lastMonth = YearMonth.from(now).minusMonths(1)
                threeMonthsAgo.atDay(1).format(fmt) to lastMonth.atEndOfMonth().format(fmt)
            }
            DateRangePreset.LAST_YEAR -> {
                val lastYear = now.year - 1
                LocalDate.of(lastYear, 1, 1).format(fmt) to LocalDate.of(lastYear, 12, 31).format(fmt)
            }
            DateRangePreset.CUSTOM -> {
                val from = state.customFrom.ifBlank { null }
                val to = state.customTo.ifBlank { null }
                from to to
            }
        }
    }
}
