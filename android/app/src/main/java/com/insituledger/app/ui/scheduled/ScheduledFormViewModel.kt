package com.insituledger.app.ui.scheduled

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.transactions.AccountDisplay
import com.insituledger.app.ui.transactions.DescriptionSuggestion
import com.insituledger.app.util.formatAmountForInput
import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.EndMode
import com.insituledger.app.util.FieldLimits
import com.insituledger.app.util.Freq
import com.insituledger.app.util.RecurrenceDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
    val recurrence: RecurrenceDraft = RecurrenceDraft(),
    val nextDate: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
    val nextTime: String = "09:00",
    val active: Boolean = true,
    /** Entries this schedule has already created (edit mode), for "N already created". */
    val occurrenceCount: Int = 0,
    val accounts: List<Account> = emptyList(),
    val accountDisplays: List<AccountDisplay> = emptyList(),
    val categories: List<Category> = emptyList(),
    val suggestions: List<DescriptionSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val previewOccurrences: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val descriptionError: String? = null,
    val noteError: String? = null,
    val saved: Boolean = false,
    /** Confirmation for the caller to show once [saved] flips. */
    val savedMessage: String? = null,
    val deleted: Boolean = false
) {
    // Pre-RecurrenceDraft accessors, kept so existing screens keep compiling.
    val frequency: String get() = recurrence.presetKey
    val customInterval: String get() = recurrence.customInterval
    val customUnit: Freq get() = recurrence.customUnit
    /** "never" | "count" | "date". */
    val endMode: String get() = recurrence.endMode.name.lowercase()
    val maxOccurrences: String get() = recurrence.count
    val endDate: String get() = recurrence.endDate
    val rrule: String get() = recurrence.rrule()
}

@HiltViewModel
class ScheduledFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val scheduledRepository: ScheduledRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val prefs: UserPreferences,
    private val syncManager: SyncManager,
    private val clock: Clock
) : ViewModel() {

    private val editId: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
    private val _uiState = MutableStateFlow(
        ScheduledFormUiState(id = editId, nextDate = LocalDate.now(clock).toString())
    )
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
                        val draft = RecurrenceDraft.fromStored(item.rrule, item.maxOccurrences)
                        // .take(5) bounds the time to "HH:mm" so the picker
                        // prefill works even when item.nextOccurrence carries
                        // an offset/seconds tail like "08:41:00+02:00".
                        val (date, time) = if (item.nextOccurrence.contains("T")) {
                            item.nextOccurrence.split("T", limit = 2).let { it[0] to it[1].take(5) }
                        } else {
                            item.nextOccurrence to "09:00"
                        }
                        _uiState.update {
                            it.copy(
                                accountId = item.accountId, categoryId = item.categoryId,
                                type = item.type, amount = formatAmountForInput(item.amount),
                                currency = item.currency, description = item.description ?: "",
                                note = item.note ?: "",
                                recurrence = draft,
                                nextDate = date, nextTime = time,
                                active = item.active,
                                occurrenceCount = item.occurrenceCount,
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
    fun updateType(type: String) {
        _uiState.update {
            // A category of the other type can't be saved with this type.
            val categoryType = it.categories.find { c -> c.id == it.categoryId }?.type
            it.copy(type = type, categoryId = if (categoryType != null && categoryType != type) null else it.categoryId)
        }
    }
    fun updateAmount(amount: String) { _uiState.update { it.copy(amount = amount) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
    fun updateNote(note: String) { _uiState.update { it.copy(note = note, noteError = null) } }
    fun updateActive(value: Boolean) { _uiState.update { it.copy(active = value) } }

    /** Edit the recurrence as a whole (the shared recurrence editor's entry point). */
    fun updateRecurrence(transform: (RecurrenceDraft) -> RecurrenceDraft) {
        _uiState.update { it.copy(recurrence = transform(it.recurrence)) }
        recomputePreview()
    }

    fun updateFrequency(frequency: String) = updateRecurrence { it.copy(presetKey = frequency) }
    fun updateCustomInterval(value: String) {
        // Digits only, bounded by the length of MAX_INTERVAL — a stray letter
        // would otherwise fall back to 1 and silently save the wrong rule.
        updateRecurrence { it.copy(customInterval = RecurrenceDraft.sanitizeNumber(value)) }
    }
    fun updateCustomUnit(unit: Freq) = updateRecurrence { it.copy(customUnit = unit) }
    fun updateNextDate(date: String) {
        _uiState.update { it.copy(nextDate = date) }
        recomputePreview()
    }
    fun updateNextTime(time: String) {
        _uiState.update { it.copy(nextTime = time) }
        recomputePreview()
    }
    fun updateMaxOccurrences(value: String) = updateRecurrence { it.copy(count = value) }
    /** [mode] is "never" | "count" | "date". */
    fun updateEndMode(mode: String) = updateRecurrence {
        it.copy(endMode = EndMode.entries.find { e -> e.name.equals(mode, ignoreCase = true) } ?: EndMode.NEVER)
    }
    fun updateEndDate(date: String) = updateRecurrence { it.copy(endDate = date) }

    private var autocompleteJob: Job? = null

    fun updateDescription(desc: String) {
        _uiState.update { it.copy(description = desc, descriptionError = null) }
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
                descriptionError = null,
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

    /** Delete the schedule being edited (entries already created stay). */
    fun delete() {
        val id = editId ?: return
        viewModelScope.launch {
            try {
                scheduledRepository.delete(id)
                _uiState.update { it.copy(deleted = true, savedMessage = "Schedule deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Could not delete schedule") }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.saved) return
        val amount = state.amount.toDoubleOrNull()
        if (state.accountId == null || state.categoryId == null || amount == null || !amount.isFinite() || amount <= 0 || state.frequency.isBlank()) {
            _uiState.update { it.copy(error = "Please fill all required fields") }
            return
        }
        // Lenient on purpose: this form keeps its historical messages, their
        // order and its rules (blank count = never; UNTIL wins over a max).
        val now = LocalDateTime.now(clock)
        val recurrenceError = state.recurrence.validate(start = startOrNull(state) ?: now, now = now, strict = false)
        if (recurrenceError != null) {
            _uiState.update { it.copy(error = recurrenceError) }
            return
        }
        val descriptionError = FieldLimits.descriptionError(state.description)
        val noteError = FieldLimits.noteError(state.note)
        if (descriptionError != null || noteError != null) {
            _uiState.update {
                it.copy(descriptionError = descriptionError, noteError = noteError, error = descriptionError ?: noteError)
            }
            return
        }

        val rrule = state.recurrence.rrule()
        val maxOcc = state.recurrence.maxOccurrencesLenient()
        val nextOccurrence = nextOccurrence(state)
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                prefs.saveLastUsedAccountId(state.accountId)
                if (editId != null) {
                    scheduledRepository.update(
                        editId, state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null },
                        state.note.ifBlank { null },
                        rrule, nextOccurrence, maxOcc, state.active
                    )
                } else {
                    scheduledRepository.create(
                        state.accountId, state.categoryId, state.type,
                        amount, state.currency, state.description.ifBlank { null },
                        state.note.ifBlank { null },
                        rrule, nextOccurrence, maxOcc
                    )
                }
                // Wake the scheduler so a due start is materialized now rather
                // than at the next 15-minute periodic check.
                syncManager.kickScheduler(nextOccurrence, clock)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saved = true,
                        savedMessage = if (editId != null) "Schedule updated" else "Schedule created"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    private fun startOrNull(state: ScheduledFormUiState): LocalDateTime? =
        try {
            LocalDateTime.of(LocalDate.parse(state.nextDate), LocalTime.parse(state.nextTime))
        } catch (_: Exception) { null }

    // RFC3339 with the device zone's offset for the chosen wall-clock moment,
    // so the backend's cross-TZ comparison is correct.
    private fun nextOccurrence(state: ScheduledFormUiState): String {
        val start = startOrNull(state)
            // Defensive fallback: emit the naive (legacy) form. Backend accepts it.
            ?: return "${state.nextDate}T${state.nextTime}"
        return DateTimeUtil.composeFormDate(start.toLocalDate(), start.toLocalTime(), clock.zone)
    }

    private fun recomputePreview() {
        val s = _uiState.value
        // parseFlexibleLocalDateTime handles every shape (offset, seconds,
        // naive) without throwing — the strict ofPattern parse silently
        // dropped the preview in edit mode for post-1.18 rows.
        val start = try {
            DateTimeUtil.parseFlexibleLocalDateTime("${s.nextDate}T${s.nextTime}", clock.zone)
        } catch (_: Exception) {
            _uiState.update { it.copy(previewOccurrences = emptyList()) }
            return
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        val list = s.recurrence.upcoming(start, 3).map { fmt.format(it) }
        _uiState.update { it.copy(previewOccurrences = list) }
    }
}
