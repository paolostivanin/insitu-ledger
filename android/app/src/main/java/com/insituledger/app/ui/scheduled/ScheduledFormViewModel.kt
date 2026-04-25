package com.insituledger.app.ui.scheduled

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.transactions.AccountDisplay
import com.insituledger.app.ui.transactions.DescriptionSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
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
    val note: String = "",
    val frequency: String = "monthly",
    val nextDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val nextTime: String = "09:00",
    val endMode: String = "never", // "never" | "count" | "date"
    val maxOccurrences: String = "",
    val endDate: String = "",
    val active: Boolean = true,
    val accounts: List<Account> = emptyList(),
    val accountDisplays: List<AccountDisplay> = emptyList(),
    val categories: List<Category> = emptyList(),
    val suggestions: List<DescriptionSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val previewOccurrences: List<String> = emptyList(),
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

    val rrule: String
        get() {
            val base = frequencyMap[frequency] ?: "FREQ=MONTHLY"
            return if (endMode == "date" && endDate.isNotBlank()) {
                // Encode end-of-day UTC so the chosen date itself is inclusive.
                val compact = endDate.replace("-", "")
                "$base;UNTIL=${compact}T235959Z"
            } else base
        }

    val nextOccurrence: String get() = "${nextDate}T${nextTime}"
}

@HiltViewModel
class ScheduledFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scheduledRepository: ScheduledRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val prefs: UserPreferences
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
                val currentUserId = prefs.userIdFlow.first()
                val displays = accounts.map { acct ->
                    AccountDisplay(
                        account = acct,
                        label = when {
                            acct.isShared && acct.userId != currentUserId && acct.ownerName.isNotEmpty() ->
                                "${acct.name} (shared by ${acct.ownerName})"
                            else -> acct.name
                        }
                    )
                }
                _uiState.update { it.copy(accounts = accounts, accountDisplays = displays, categories = categories) }
                if (editId != null && _uiState.value.isLoading) {
                    val item = scheduledRepository.getById(editId)
                    if (item != null) {
                        // Strip optional ;UNTIL=... segment for frequency lookup; remember the date.
                        val withoutUntil = item.rrule.split(";")
                            .filterNot { it.startsWith("UNTIL=") }
                            .joinToString(";")
                        val freq = ScheduledFormUiState.frequencyMap.entries
                            .find { it.value == withoutUntil }?.key ?: "monthly"
                        val untilDate = item.rrule.split(";")
                            .firstOrNull { it.startsWith("UNTIL=") }
                            ?.removePrefix("UNTIL=")
                            ?.let { extractIsoDate(it) }
                            ?: ""
                        val (date, time) = if (item.nextOccurrence.contains("T")) {
                            item.nextOccurrence.split("T", limit = 2).let { it[0] to it[1] }
                        } else {
                            item.nextOccurrence to "09:00"
                        }
                        val endMode = when {
                            untilDate.isNotEmpty() -> "date"
                            item.maxOccurrences != null -> "count"
                            else -> "never"
                        }
                        _uiState.update {
                            it.copy(
                                accountId = item.accountId, categoryId = item.categoryId,
                                type = item.type, amount = item.amount.toString(),
                                currency = item.currency, description = item.description ?: "",
                                note = item.note ?: "",
                                frequency = freq, nextDate = date, nextTime = time,
                                endMode = endMode,
                                maxOccurrences = item.maxOccurrences?.toString() ?: "",
                                endDate = untilDate,
                                active = item.active,
                                isLoading = false
                            )
                        }
                        recomputePreview()
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                } else if (_uiState.value.isLoading) {
                    // New schedule: default to last-used account.
                    val lastAccountId = prefs.lastUsedAccountIdFlow.first()
                    val defaultAccount = if (lastAccountId != null && accounts.any { it.id == lastAccountId }) {
                        lastAccountId
                    } else {
                        accounts.firstOrNull()?.id
                    }
                    _uiState.update {
                        it.copy(
                            accountId = defaultAccount,
                            currency = accounts.find { a -> a.id == defaultAccount }?.currency ?: "EUR",
                            isLoading = false
                        )
                    }
                    recomputePreview()
                }
            }
        }
    }

    fun updateAccountId(id: Long) {
        _uiState.update { it.copy(accountId = id) }
        viewModelScope.launch { prefs.saveLastUsedAccountId(id) }
    }
    fun updateCategoryId(id: Long) { _uiState.update { it.copy(categoryId = id) } }
    fun updateType(type: String) { _uiState.update { it.copy(type = type) } }
    fun updateAmount(amount: String) { _uiState.update { it.copy(amount = amount) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
    fun updateNote(note: String) { _uiState.update { it.copy(note = note) } }
    fun updateActive(value: Boolean) { _uiState.update { it.copy(active = value) } }

    fun updateFrequency(frequency: String) {
        _uiState.update { it.copy(frequency = frequency) }
        recomputePreview()
    }
    fun updateNextDate(date: String) {
        _uiState.update { it.copy(nextDate = date) }
        recomputePreview()
    }
    fun updateNextTime(time: String) {
        _uiState.update { it.copy(nextTime = time) }
        recomputePreview()
    }
    fun updateMaxOccurrences(value: String) {
        _uiState.update { it.copy(maxOccurrences = value) }
        recomputePreview()
    }
    fun updateEndMode(mode: String) {
        _uiState.update { it.copy(endMode = mode) }
        recomputePreview()
    }
    fun updateEndDate(date: String) {
        _uiState.update { it.copy(endDate = date) }
        recomputePreview()
    }

    private var autocompleteJob: Job? = null

    fun updateDescription(desc: String) {
        _uiState.update { it.copy(description = desc) }
        autocompleteJob?.cancel()
        if (desc.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
            return
        }
        autocompleteJob = viewModelScope.launch {
            delay(200)
            try {
                val results = transactionRepository.autocomplete(desc)
                _uiState.update {
                    it.copy(
                        suggestions = results.map { (d, c) -> DescriptionSuggestion(d, c) },
                        showSuggestions = results.isNotEmpty()
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
            }
        }
    }

    fun selectSuggestion(suggestion: DescriptionSuggestion) {
        _uiState.update {
            val derivedType = it.categories.find { c -> c.id == suggestion.categoryId }?.type ?: it.type
            it.copy(
                description = suggestion.description,
                categoryId = suggestion.categoryId,
                type = derivedType,
                suggestions = emptyList(),
                showSuggestions = false
            )
        }
    }

    fun dismissSuggestions() {
        _uiState.update { it.copy(showSuggestions = false) }
    }

    fun createCategory(name: String, type: String) {
        viewModelScope.launch {
            val id = categoryRepository.create(name, type, null, null, null)
            val newCategory = Category(id = id, userId = 0, parentId = null, name = name, type = type, icon = null, color = null, isLocalOnly = true)
            val updatedCategories = _uiState.value.categories + newCategory
            _uiState.update { it.copy(categories = updatedCategories, categoryId = id) }
        }
    }

    fun createAccount(name: String, currency: String) {
        viewModelScope.launch {
            val id = accountRepository.create(name, currency, 0.0)
            val currentUserId = prefs.userIdFlow.first()
            val newAccount = Account(
                id = id, userId = currentUserId ?: 0,
                name = name, currency = currency, balance = 0.0, isLocalOnly = true
            )
            val updatedAccounts = _uiState.value.accounts + newAccount
            val updatedDisplays = updatedAccounts.map { acct ->
                AccountDisplay(
                    account = acct,
                    label = when {
                        acct.isShared && acct.userId != currentUserId && acct.ownerName.isNotEmpty() ->
                            "${acct.name} (shared by ${acct.ownerName})"
                        else -> acct.name
                    }
                )
            }
            _uiState.update {
                it.copy(
                    accounts = updatedAccounts,
                    accountDisplays = updatedDisplays,
                    accountId = id,
                    currency = currency
                )
            }
            prefs.saveLastUsedAccountId(id)
        }
    }

    fun save() {
        val state = _uiState.value
        val amount = state.amount.toDoubleOrNull()
        if (state.accountId == null || state.categoryId == null || amount == null || amount <= 0 || state.frequency.isBlank()) {
            _uiState.update { it.copy(error = "Please fill all required fields") }
            return
        }
        val maxOcc = if (state.endMode == "count") state.maxOccurrences.toIntOrNull()?.takeIf { it > 0 } else null
        if (state.endMode == "date" && state.endDate.isBlank()) {
            _uiState.update { it.copy(error = "Pick an end date") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                prefs.saveLastUsedAccountId(state.accountId)
                if (editId != null) {
                    scheduledRepository.update(
                        editId, state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null },
                        state.note.ifBlank { null },
                        state.rrule, state.nextOccurrence, maxOcc, state.active
                    )
                } else {
                    scheduledRepository.create(
                        state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null },
                        state.note.ifBlank { null },
                        state.rrule, state.nextOccurrence, maxOcc
                    )
                }
                _uiState.update { it.copy(isSaving = false, saved = true) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    private fun recomputePreview() {
        val s = _uiState.value
        val list = computeUpcoming(s, max = 3)
        _uiState.update { it.copy(previewOccurrences = list) }
    }

    private fun computeUpcoming(state: ScheduledFormUiState, max: Int): List<String> {
        val start = try {
            LocalDateTime.parse("${state.nextDate}T${state.nextTime}", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        } catch (_: Exception) { return emptyList() }
        val until = if (state.endMode == "date" && state.endDate.isNotBlank()) {
            try { LocalDate.parse(state.endDate).atTime(23, 59, 59) } catch (_: Exception) { null }
        } else null
        val countCap = if (state.endMode == "count") state.maxOccurrences.toIntOrNull()?.takeIf { it > 0 } else null

        val out = mutableListOf<LocalDateTime>()
        var current = start
        while (out.size < max) {
            if (until != null && current.isAfter(until)) break
            out += current
            if (countCap != null && out.size >= countCap) break
            current = advance(current, state.frequency)
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        return out.map { fmt.format(it) }
    }

    private fun advance(t: LocalDateTime, frequency: String): LocalDateTime = when (frequency) {
        "daily" -> t.plusDays(1)
        "weekly" -> t.plusWeeks(1)
        "biweekly" -> t.plusWeeks(2)
        "monthly" -> t.plusMonths(1)
        "quarterly" -> t.plusMonths(3)
        "yearly" -> t.plusYears(1)
        else -> t.plusMonths(1)
    }

    private fun extractIsoDate(until: String): String {
        // Tolerate 20261231T235959Z, 20261231, 2026-12-31T..., 2026-12-31
        val core = until.substringBefore("T")
        return if (core.length == 8 && core.all { it.isDigit() }) {
            "${core.substring(0, 4)}-${core.substring(4, 6)}-${core.substring(6, 8)}"
        } else core
    }
}
