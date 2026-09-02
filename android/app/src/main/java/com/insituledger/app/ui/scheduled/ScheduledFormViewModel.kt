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
import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.Freq
import com.insituledger.app.util.Rrule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
    val frequency: String = "monthly", // an Rrule.PRESETS key, or Rrule.CUSTOM_KEY
    // Only read when frequency == Rrule.CUSTOM_KEY. String to match the
    // amount / maxOccurrences convention for user-typed numbers.
    val customInterval: String = "2",
    val customUnit: Freq = Freq.MONTHLY,
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
    /** The {freq, interval} pair the frequency controls currently describe. */
    val recurrence: Pair<Freq, Int>
        get() = if (frequency == Rrule.CUSTOM_KEY) {
            // Coerce like Rrule.parse does; save() is what rejects a bad value.
            customUnit to (customInterval.toIntOrNull()?.takeIf { it > 0 } ?: 1)
        } else {
            Rrule.preset(frequency)?.let { it.freq to it.interval } ?: (Freq.MONTHLY to 1)
        }

    val rrule: String
        get() {
            val (freq, interval) = recurrence
            // UNTIL is encoded end-of-day UTC so the chosen date is inclusive.
            val until = if (endMode == "date") endDate else ""
            return Rrule.build(freq, interval, until)
        }

    // Emit RFC3339 with the system zone's offset for the chosen date, so the
    // backend's cross-TZ comparison is correct (no more silent re-routing to
    // scheduled when a traveling user types "now").
    val nextOccurrence: String
        get() = try {
            LocalDateTime.parse("${nextDate}T${nextTime}")
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime()
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (_: Exception) {
            // Defensive fallback: emit naive (legacy) form. Backend accepts it.
            "${nextDate}T${nextTime}"
        }
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
                        // Parse the rrule into its parts rather than matching it
                        // whole: an exact-string lookup silently degrades
                        // anything unrecognized to "monthly", and saving then
                        // overwrites the user's real recurrence.
                        val parsed = Rrule.parse(item.rrule)
                        val freq = Rrule.presetKey(parsed.freq, parsed.interval)
                        val untilDate = parsed.until
                        // .take(5) bounds the time to "HH:mm" so the picker
                        // prefill works even when item.nextOccurrence carries
                        // an offset/seconds tail like "08:41:00+02:00".
                        val (date, time) = if (item.nextOccurrence.contains("T")) {
                            item.nextOccurrence.split("T", limit = 2).let { it[0] to it[1].take(5) }
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
                                frequency = freq,
                                customInterval = if (freq == Rrule.CUSTOM_KEY) parsed.interval.toString() else it.customInterval,
                                customUnit = if (freq == Rrule.CUSTOM_KEY) parsed.freq else it.customUnit,
                                nextDate = date, nextTime = time,
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
    fun updateCustomInterval(value: String) {
        // Digits only, bounded by the length of MAX_INTERVAL — a stray letter
        // would otherwise fall back to 1 and silently save the wrong rule.
        val digits = value.filter { it.isDigit() }.take(Rrule.MAX_INTERVAL.toString().length)
        _uiState.update { it.copy(customInterval = digits) }
        recomputePreview()
    }
    fun updateCustomUnit(unit: Freq) {
        _uiState.update { it.copy(customUnit = unit) }
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
        if (state.frequency == Rrule.CUSTOM_KEY) {
            val interval = state.customInterval.toIntOrNull()
            if (interval == null || interval < 1 || interval > Rrule.MAX_INTERVAL) {
                _uiState.update {
                    it.copy(error = "Repeat every must be a whole number between 1 and ${Rrule.MAX_INTERVAL}")
                }
                return
            }
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
        // parseFlexibleLocalDateTime handles every shape (offset, seconds,
        // naive) without throwing — the strict ofPattern parse silently
        // dropped the preview in edit mode for post-1.18 rows.
        val start = try {
            DateTimeUtil.parseFlexibleLocalDateTime("${state.nextDate}T${state.nextTime}")
        } catch (_: Exception) { return emptyList() }
        val until = if (state.endMode == "date" && state.endDate.isNotBlank()) {
            try { LocalDate.parse(state.endDate).atTime(23, 59, 59) } catch (_: Exception) { null }
        } else null
        val countCap = if (state.endMode == "count") state.maxOccurrences.toIntOrNull()?.takeIf { it > 0 } else null

        // Advance by the real {freq, interval} pair, not the UI key — the old
        // key-based version ignored INTERVAL, so a custom rule previewed wrong.
        val (freq, interval) = state.recurrence
        val out = mutableListOf<LocalDateTime>()
        var current = start
        while (out.size < max) {
            if (until != null && current.isAfter(until)) break
            out += current
            if (countCap != null && out.size >= countCap) break
            current = Rrule.advance(current, freq, interval)
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        return out.map { fmt.format(it) }
    }
}
