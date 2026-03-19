package com.insituledger.app.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class CategorySummary(
    val category: Category,
    val total: Double
)

enum class DateRangePreset { LAST_WEEK, LAST_MONTH, LAST_3_MONTHS, LAST_YEAR, CUSTOM }

data class ReportsUiState(
    val categories: List<Category> = emptyList(),
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val categoryBreakdown: List<CategorySummary> = emptyList(),
    val dateRangePreset: DateRangePreset = DateRangePreset.LAST_MONTH,
    val customFrom: String = "",
    val customTo: String = "",
    val isLoading: Boolean = true,
    val selectedCategory: Category? = null,
    val selectedCategoryTransactions: List<Transaction> = emptyList(),
    val selectedCategoryTotal: Double = 0.0
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            categoryRepository.getAll().collect { cats ->
                _uiState.update { it.copy(categories = cats) }
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
        val transactions = transactionRepository.getFilteredSync(from, to, null)

        val totalIncome = transactions.filter { it.type == "income" }.sumOf { it.amount }
        val totalExpense = transactions.filter { it.type == "expense" }.sumOf { it.amount }

        val categories = _uiState.value.categories
        val breakdown = transactions
            .groupBy { it.categoryId }
            .mapNotNull { (catId, txns) ->
                val cat = categories.find { it.id == catId } ?: return@mapNotNull null
                CategorySummary(cat, txns.sumOf { it.amount })
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
            DateRangePreset.LAST_WEEK -> now.minusWeeks(1).format(fmt) to now.format(fmt)
            DateRangePreset.LAST_MONTH -> now.minusMonths(1).format(fmt) to now.format(fmt)
            DateRangePreset.LAST_3_MONTHS -> now.minusMonths(3).format(fmt) to now.format(fmt)
            DateRangePreset.LAST_YEAR -> now.minusYears(1).format(fmt) to now.format(fmt)
            DateRangePreset.CUSTOM -> {
                val from = state.customFrom.ifBlank { null }
                val to = state.customTo.ifBlank { null }
                from to to
            }
        }
    }
}
