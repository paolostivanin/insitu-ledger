package com.insituledger.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class ReportsUiState(
    val categories: List<Category> = emptyList(),
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val categoryBreakdown: List<CategorySummary> = emptyList(),
    val dateRangePreset: DateRangePreset = DateRangePreset.THIS_MONTH,
    val customFrom: String = "",
    val customTo: String = "",
    val isLoading: Boolean = true,
    val selectedCategory: Category? = null,
    val selectedCategoryTransactions: List<Transaction> = emptyList(),
    val selectedCategoryTotal: Double = 0.0
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

        val categories = _uiState.value.categories
        val breakdown = breakdownRows
            .groupBy { it.categoryId }
            .mapNotNull { (catId, rows) ->
                val cat = categories.find { it.id == catId } ?: return@mapNotNull null
                CategorySummary(cat, rows.sumOf { it.total })
            }
            .sortedByDescending { it.total }

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
