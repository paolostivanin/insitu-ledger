package com.insituledger.app.ui.transactions

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
import com.insituledger.app.util.formatAmountForInput
import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.EndMode
import com.insituledger.app.util.FieldLimits
import com.insituledger.app.util.RecurrenceDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

data class DescriptionSuggestion(
    val description: String,
    val categoryId: Long
)

data class AccountDisplay(
    val account: Account,
    val label: String
)

data class TransactionFormUiState(
    val id: Long? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val type: String = "expense",
    val amount: String = "",
    val currency: String = "EUR",
    val description: String = "",
    val note: String = "",
    // RFC3339 with the device zone's offset (set from the injected clock by
    // the ViewModel), so the backend's future-vs-past check is correct
    // cross-TZ from the very first save.
    val date: String = "",
    val accounts: List<Account> = emptyList(),
    val accountDisplays: List<AccountDisplay> = emptyList(),
    val categories: List<Category> = emptyList(),
    val suggestions: List<DescriptionSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    /** Set = saving creates a recurring schedule starting at [date]; null = no recurrence. */
    val recurrence: RecurrenceDraft? = null,
    /** A one-time schedule was chosen in the sheet (as opposed to a typed future date). */
    val explicitOnce: Boolean = false,
    /** The form date before a schedule was applied; restored by [TransactionFormViewModel.removeSchedule]. */
    val dateBeforeSchedule: String? = null,
    /** null = sheet closed. */
    val scheduleSheet: ScheduleSheetState? = null,
    /** Result of the last successful save, for the caller's confirmation. */
    val outcome: SaveOutcome? = null,
    val amountError: String? = null,
    val descriptionError: String? = null,
    val noteError: String? = null,
    /** The explicit one-time moment passed before saving: offer "Add now". */
    val canAddNow: Boolean = false,
    /** The last picked time fell in a DST gap and was shifted forward. */
    val dstAdjusted: Boolean = false
)

@HiltViewModel
class TransactionFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val scheduledRepository: ScheduledRepository,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences,
    private val clock: Clock
) : ViewModel() {

    companion object {
        const val MSG_REQUIRED = "Please fill all required fields with valid values"
        const val MSG_AMOUNT = "Enter an amount greater than 0"
        const val MSG_INVALID_DATE = "That date isn't valid. Pick it again."
        const val MSG_ONE_TIME_PAST = "Pick a time in the future"
        const val MSG_TIME_PASSED = "That time has passed"
        private const val UPCOMING_PREVIEW = 3
    }

    private val editId: Long? = savedStateHandle.get<String>("id")?.toLongOrNull()
    private val zone: ZoneId get() = clock.zone
    private val _uiState = MutableStateFlow(TransactionFormUiState(id = editId, date = nowFormDate()))
    val uiState: StateFlow<TransactionFormUiState> = _uiState.asStateFlow()

    init { loadData() }

    /** The clock's current moment, for [formMode] tickers in the UI. */
    fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    private fun nowFormDate(): String {
        val now = LocalDateTime.now(clock)
        return DateTimeUtil.composeFormDate(now.toLocalDate(), now.toLocalTime(), zone)
    }

    private fun loadData() {
        viewModelScope.launch {
            val accounts: List<Account>
            val categories: List<Category>

            coroutineScope {
                val accountsDeferred = async { accountRepository.getCached() }
                val categoriesDeferred = async { categoryRepository.getCached() }
                accounts = accountsDeferred.await()
                categories = categoriesDeferred.await()
            }

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

            if (editId != null) {
                val txn = transactionRepository.getById(editId)
                if (txn != null) {
                    _uiState.update {
                        it.copy(
                            accountId = txn.accountId, categoryId = txn.categoryId,
                            type = txn.type, amount = formatAmountForInput(txn.amount),
                            currency = txn.currency, description = txn.description ?: "",
                            note = txn.note ?: "",
                            date = txn.date, isLoading = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } else {
                // Default to last-used account
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
    fun updateAmount(amount: String) { _uiState.update { it.copy(amount = amount, amountError = null) } }
    fun updateCurrency(currency: String) { _uiState.update { it.copy(currency = currency) } }
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
        // Reuse a stable recurring amount for this payee, but never overwrite a
        // value the user has already typed.
        viewModelScope.launch {
            val current = _uiState.value.amount
            if (current.isBlank() || current.toDoubleOrNull() == 0.0) {
                transactionRepository.suggestedAmount(suggestion.description)?.let { (amt, ccy) ->
                    _uiState.update {
                        if (it.amount.isBlank() || it.amount.toDoubleOrNull() == 0.0) {
                            it.copy(amount = formatAmount(amt), currency = ccy, amountError = null)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    private fun formatAmount(amount: Double): String =
        java.math.BigDecimal.valueOf(amount)
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    fun dismissSuggestions() {
        _uiState.update { it.copy(showSuggestions = false) }
    }

    fun updateNote(note: String) { _uiState.update { it.copy(note = note, noteError = null) } }

    /**
     * Raw setter, kept for callers that already hold a full form-date string.
     * Prefer [setDate] / [setTime], which fix the offset across DST.
     */
    fun updateDate(date: String) {
        _uiState.update { it.copy(date = date, explicitOnce = false, canAddNow = false, dstAdjusted = false) }
    }

    /** The form's own date picker. Clears an explicit one-time choice. */
    fun setDate(date: LocalDate) = editDate { original ->
        val time = DateTimeUtil.parseFormDateOrNull(original, zone)?.toLocalTime() ?: LocalTime.now(clock)
        DateTimeUtil.withDatePart(original, date, zone) to LocalDateTime.of(date, time)
    }

    /** The form's own time picker. Clears an explicit one-time choice. */
    fun setTime(time: LocalTime) = editDate { original ->
        val date = DateTimeUtil.parseFormDateOrNull(original, zone)?.toLocalDate() ?: LocalDate.now(clock)
        DateTimeUtil.withTimePart(original, time, zone) to LocalDateTime.of(date, time)
    }

    // [change] returns the new form date and the wall clock that was asked
    // for; when they differ the time fell in a DST gap and was shifted.
    private inline fun editDate(change: (String) -> Pair<String, LocalDateTime>) {
        val (newDate, requested) = change(_uiState.value.date)
        val actual = DateTimeUtil.parseFormDateOrNull(newDate, zone)?.toLocalDateTime()
        _uiState.update {
            it.copy(
                date = newDate,
                explicitOnce = false,
                canAddNow = false,
                dstAdjusted = actual != null && actual != requested.truncatedTo(ChronoUnit.MINUTES)
            )
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

    fun createCategory(name: String, type: String) {
        viewModelScope.launch {
            val id = categoryRepository.create(name, type, null, null, null)
            val newCategory = Category(id = id, userId = 0, parentId = null, name = name, type = type, icon = null, color = null, isLocalOnly = true)
            val updatedCategories = _uiState.value.categories + newCategory
            _uiState.update { it.copy(categories = updatedCategories, categoryId = id) }
        }
    }

    // ---- Schedule sheet -------------------------------------------------

    /**
     * Opens the sheet pre-filled from the form: the current recurrence if
     * there is one; One-time at the form date if that is an explicit one-time
     * or a future date; otherwise Recurring (Monthly, never ends) starting at
     * the form date — past dates included, which back-fills missed entries.
     * No-op when editing an existing transaction.
     */
    fun openScheduleSheet() {
        if (editId != null) return
        val state = _uiState.value
        val now = now()
        val parsed = DateTimeUtil.parseFormDateOrNull(state.date, zone)
        val wall = (parsed ?: now).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        val future = parsed != null && parsed.isAfter(now)
        val sheet = when {
            state.recurrence != null -> ScheduleSheetState(
                mode = ScheduleMode.RECURRING, date = wall.toLocalDate(), time = wall.toLocalTime(),
                draft = state.recurrence, isEditingExisting = true
            )
            state.explicitOnce || future -> ScheduleSheetState(
                mode = ScheduleMode.ONE_TIME, date = wall.toLocalDate(), time = wall.toLocalTime(),
                isEditingExisting = true
            )
            else -> ScheduleSheetState(
                mode = ScheduleMode.RECURRING, date = wall.toLocalDate(), time = wall.toLocalTime()
            )
        }
        _uiState.update { it.copy(scheduleSheet = recompute(sheet)) }
    }

    fun setSheetMode(mode: ScheduleMode) = updateSheet { it.copy(mode = mode) }
    fun setSheetDate(date: LocalDate) = updateSheet { it.copy(date = date) }
    fun setSheetTime(time: LocalTime) = updateSheet { it.copy(time = time.truncatedTo(ChronoUnit.MINUTES)) }
    fun updateSheetDraft(transform: (RecurrenceDraft) -> RecurrenceDraft) = updateSheet { it.copy(draft = transform(it.draft)) }

    /** "Tomorrow" / "In a week" / "In a month" at the sheet's time of day, as a one-time schedule. */
    fun applyQuickPick(pick: QuickPick) {
        val today = LocalDate.now(clock)
        val date = when (pick) {
            QuickPick.TOMORROW -> today.plusDays(1)
            QuickPick.IN_A_WEEK -> today.plusWeeks(1)
            QuickPick.IN_A_MONTH -> today.plusMonths(1)
        }
        updateSheet { it.copy(mode = ScheduleMode.ONE_TIME, date = date) }
    }

    /**
     * Validates strictly and, if valid, applies the sheet to the form: the
     * form date becomes the chosen moment (One-time) or the start (Recurring).
     * Returns false, leaving the sheet open with its error, when invalid.
     */
    fun applySchedule(): Boolean {
        val state = _uiState.value
        val sheet = state.scheduleSheet?.let(::recompute) ?: return false
        if (sheet.error != null) {
            _uiState.update { it.copy(scheduleSheet = sheet) }
            return false
        }
        val newDate = DateTimeUtil.composeFormDate(sheet.date, sheet.time, zone)
        _uiState.update {
            it.copy(
                date = newDate,
                recurrence = if (sheet.mode == ScheduleMode.RECURRING) sheet.draft else null,
                // One-time saves through the existing future-date path.
                explicitOnce = sheet.mode == ScheduleMode.ONE_TIME,
                dateBeforeSchedule = it.dateBeforeSchedule ?: it.date,
                scheduleSheet = null,
                dstAdjusted = sheet.dstAdjusted,
                canAddNow = false,
                error = null
            )
        }
        return true
    }

    /** Back, swipe, scrim or Cancel: discards only the sheet's draft. */
    fun dismissScheduleSheet() {
        _uiState.update { it.copy(scheduleSheet = null) }
    }

    /**
     * Clears the schedule. The date goes back to what it was before the
     * schedule if that is not in the future, otherwise to now — removing
     * always yields an immediate entry.
     */
    fun removeSchedule() {
        val now = now()
        _uiState.update {
            val restored = it.dateBeforeSchedule
                ?.takeIf { d -> DateTimeUtil.parseFormDateOrNull(d, zone)?.isAfter(now) == false }
            it.copy(
                recurrence = null,
                explicitOnce = false,
                date = restored ?: nowFormDate(),
                dateBeforeSchedule = null,
                scheduleSheet = null,
                canAddNow = false,
                dstAdjusted = false,
                error = null
            )
        }
    }

    /** After "That time has passed": record the entry now instead. */
    fun addNow() {
        if (editId != null || !_uiState.value.canAddNow) return
        _uiState.update {
            it.copy(
                date = nowFormDate(),
                explicitOnce = false,
                dateBeforeSchedule = null,
                canAddNow = false,
                dstAdjusted = false,
                error = null
            )
        }
        save()
    }

    private inline fun updateSheet(change: (ScheduleSheetState) -> ScheduleSheetState) {
        val sheet = _uiState.value.scheduleSheet ?: return
        _uiState.update { it.copy(scheduleSheet = recompute(change(sheet))) }
    }

    private fun recompute(sheet: ScheduleSheetState): ScheduleSheetState {
        val nowLocal = LocalDateTime.now(clock)
        // The moment actually saved: a DST-gap time shifts forward.
        val effective = ZonedDateTime.of(sheet.date, sheet.time, zone)
        val start = effective.toLocalDateTime()
        val dstAdjusted = DateTimeUtil.isInDstGap(sheet.date, sheet.time, zone)
        return when (sheet.mode) {
            ScheduleMode.ONE_TIME -> sheet.copy(
                error = if (effective.toInstant().isAfter(clock.instant())) null else MSG_ONE_TIME_PAST,
                upcoming = listOf(start),
                pastEntries = emptyList(),
                endsDuringCatchUp = false,
                dstAdjusted = dstAdjusted
            )
            ScheduleMode.RECURRING -> {
                val past = sheet.draft.pastOccurrences(start, nowLocal, RecurrenceDraft.MAX_PAST_OCCURRENCES + 1)
                val upcoming = sheet.draft.upcoming(start, UPCOMING_PREVIEW, after = nowLocal)
                sheet.copy(
                    error = sheet.draft.validate(start, nowLocal, strict = true),
                    upcoming = upcoming,
                    pastEntries = past,
                    endsDuringCatchUp = past.isNotEmpty() && upcoming.isEmpty(),
                    dstAdjusted = dstAdjusted
                )
            }
        }
    }

    // ---- Save -----------------------------------------------------------

    private sealed interface Write {
        data object Update : Write
        data object Create : Write
        data class Once(val at: LocalDateTime) : Write
        data class Recurring(val draft: RecurrenceDraft, val start: LocalDateTime, val pastEntries: Int) : Write
    }

    /**
     * Exactly one write per save, routed in this order: editing → update;
     * recurrence → schedule; explicit one-time that has passed → error with
     * "Add now"; future date → one-time schedule (the existing path,
     * `FREQ=DAILY` with max 1); otherwise → transaction.
     */
    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.saved) return

        val amount = state.amount.toDoubleOrNull()
        val amountError = if (amount == null || !amount.isFinite() || amount <= 0) MSG_AMOUNT else null
        val descriptionError = FieldLimits.descriptionError(state.description)
        val noteError = FieldLimits.noteError(state.note)
        val missing = state.accountId == null || state.categoryId == null
        if (missing || amount == null || amountError != null || descriptionError != null || noteError != null) {
            _uiState.update {
                it.copy(
                    amountError = amountError,
                    descriptionError = descriptionError,
                    noteError = noteError,
                    error = if (missing || amountError != null) MSG_REQUIRED else descriptionError ?: noteError
                )
            }
            return
        }
        val accountId = state.accountId!!
        val categoryId = state.categoryId!!

        val parsed = DateTimeUtil.parseFormDateOrNull(state.date, zone)
        if (parsed == null) {
            _uiState.update { it.copy(error = MSG_INVALID_DATE) }
            return
        }
        val now = now()
        val nowLocal = LocalDateTime.now(clock)
        val recurrence = state.recurrence
        val write: Write = when {
            editId != null -> Write.Update
            recurrence != null -> {
                val start = parsed.toLocalDateTime()
                val error = recurrence.validate(start, nowLocal, strict = true)
                if (error != null) {
                    _uiState.update { it.copy(error = error) }
                    return
                }
                val past = recurrence.pastOccurrences(start, nowLocal, RecurrenceDraft.MAX_PAST_OCCURRENCES + 1).size
                Write.Recurring(recurrence, start, past)
            }
            state.explicitOnce && !parsed.isAfter(now) -> {
                _uiState.update { it.copy(error = MSG_TIME_PASSED, canAddNow = true) }
                return
            }
            parsed.isAfter(now) -> Write.Once(parsed.toLocalDateTime())
            else -> Write.Create
        }

        _uiState.update {
            it.copy(isSaving = true, error = null, canAddNow = false, amountError = null, descriptionError = null, noteError = null)
        }
        val description = state.description.ifBlank { null }
        val note = state.note.ifBlank { null }
        viewModelScope.launch {
            try {
                // Persist last-used account
                prefs.saveLastUsedAccountId(accountId)

                val outcome: SaveOutcome = when (write) {
                    Write.Update -> {
                        transactionRepository.update(
                            editId!!, accountId, categoryId, state.type,
                            amount, state.currency, description, note, state.date
                        )
                        SaveOutcome.Updated
                    }
                    is Write.Recurring -> {
                        scheduledRepository.create(
                            accountId, categoryId, state.type,
                            amount, state.currency, description, note,
                            rrule = write.draft.rrule(),
                            nextOccurrence = state.date,
                            maxOccurrences = write.draft.maxOccurrences()
                        )
                        syncManager.kickScheduler(state.date, clock)
                        SaveOutcome.ScheduledRecurring(
                            summary = write.draft.copy(endMode = EndMode.NEVER).summary(write.start, Locale.ENGLISH),
                            startsNow = write.pastEntries > 0,
                            start = write.start,
                            pastEntries = write.pastEntries
                        )
                    }
                    is Write.Once -> {
                        scheduledRepository.create(
                            accountId, categoryId, state.type,
                            amount, state.currency, description, note,
                            rrule = "FREQ=DAILY", nextOccurrence = state.date, maxOccurrences = 1
                        )
                        // Wakes the local worker at that moment (wall-clock delay).
                        syncManager.kickScheduler(state.date, clock)
                        SaveOutcome.ScheduledOnce(write.at)
                    }
                    Write.Create -> {
                        transactionRepository.create(
                            accountId, categoryId, state.type,
                            amount, state.currency, description, note, state.date
                        )
                        SaveOutcome.Created(state.type)
                    }
                }
                _uiState.update { it.copy(isSaving = false, saved = true, outcome = outcome) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }
}
