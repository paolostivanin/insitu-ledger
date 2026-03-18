package com.insituledger.app.ui.scheduled

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ScheduledFormUiState(
    val id: Long? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val type: String = "expense",
    val amount: String = "",
    val currency: String = "EUR",
    val description: String = "",
    val frequency: String = "monthly",
    val nextDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val nextTime: String = "09:00",
    val maxOccurrences: String = "",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
) {
    companion object {
        val frequencyMap = mapOf(
            "daily" to "FREQ=DAILY",
            "weekly" to "FREQ=WEEKLY",
            "biweekly" to "FREQ=WEEKLY;INTERVAL=2",
            "monthly" to "FREQ=MONTHLY",
            "quarterly" to "FREQ=MONTHLY;INTERVAL=3",
            "yearly" to "FREQ=YEARLY"
        )
        val frequencyLabels = mapOf(
            "daily" to "Daily",
            "weekly" to "Weekly",
            "biweekly" to "Biweekly",
            "monthly" to "Monthly",
            "quarterly" to "Quarterly",
            "yearly" to "Yearly"
        )
    }

    val rrule: String get() = frequencyMap[frequency] ?: "FREQ=MONTHLY"
    val nextOccurrence: String get() = "${nextDate}T${nextTime}"
}

@HiltViewModel
class ScheduledFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scheduledRepository: ScheduledRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val editId: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
    private val _uiState = MutableStateFlow(ScheduledFormUiState(id = editId))
    val uiState: StateFlow<ScheduledFormUiState> = _uiState.asStateFlow()

    init { loadData() }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                accountRepository.getAll(),
                categoryRepository.getAll()
            ) { accounts, categories -> Pair(accounts, categories) }
            .collect { (accounts, categories) ->
                _uiState.update { it.copy(accounts = accounts, categories = categories) }
                if (editId != null && _uiState.value.isLoading) {
                    val item = scheduledRepository.getById(editId)
                    if (item != null) {
                        val freq = ScheduledFormUiState.frequencyMap.entries
                            .find { it.value == item.rrule }?.key ?: "monthly"
                        val (date, time) = if (item.nextOccurrence.contains("T")) {
                            item.nextOccurrence.split("T", limit = 2)
                                .let { it[0] to it[1] }
                        } else {
                            item.nextOccurrence to "09:00"
                        }
                        _uiState.update {
                            it.copy(
                                accountId = item.accountId, categoryId = item.categoryId,
                                type = item.type, amount = item.amount.toString(),
                                currency = item.currency, description = item.description ?: "",
                                frequency = freq, nextDate = date, nextTime = time,
                                maxOccurrences = item.maxOccurrences?.toString() ?: "",
                                isLoading = false
                            )
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun updateAccountId(id: Long) { _uiState.update { it.copy(accountId = id) } }
    fun updateCategoryId(id: Long) { _uiState.update { it.copy(categoryId = id) } }
    fun updateType(type: String) { _uiState.update { it.copy(type = type) } }
    fun updateAmount(amount: String) { _uiState.update { it.copy(amount = amount) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
    fun updateDescription(desc: String) { _uiState.update { it.copy(description = desc) } }
    fun updateFrequency(frequency: String) { _uiState.update { it.copy(frequency = frequency) } }
    fun updateNextDate(date: String) { _uiState.update { it.copy(nextDate = date) } }
    fun updateNextTime(time: String) { _uiState.update { it.copy(nextTime = time) } }
    fun updateMaxOccurrences(value: String) { _uiState.update { it.copy(maxOccurrences = value) } }

    fun createCategory(name: String, type: String) {
        viewModelScope.launch {
            val id = categoryRepository.create(name, type, null, null, null)
            _uiState.update { it.copy(categoryId = id) }
        }
    }

    fun save() {
        val state = _uiState.value
        val amount = state.amount.toDoubleOrNull()
        if (state.accountId == null || state.categoryId == null || amount == null || amount <= 0 || state.frequency.isBlank()) {
            _uiState.update { it.copy(error = "Please fill all required fields") }
            return
        }
        val maxOcc = state.maxOccurrences.toIntOrNull()?.takeIf { it > 0 }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                if (editId != null) {
                    scheduledRepository.update(
                        editId, state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null },
                        state.rrule, state.nextOccurrence, maxOcc
                    )
                } else {
                    scheduledRepository.create(
                        state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null },
                        state.rrule, state.nextOccurrence, maxOcc
                    )
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
