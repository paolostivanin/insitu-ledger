package com.insituledger.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.repository.SyncRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.util.EndMode
import com.insituledger.app.util.Freq
import com.insituledger.app.util.RecurrenceDraft
import com.insituledger.app.util.Rrule
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionFormViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val scheduledRepository: ScheduledRepository = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { accountRepository.getCached() } returns emptyList()
        coEvery { categoryRepository.getCached() } returns emptyList()
        every { prefs.userIdFlow } returns flowOf(1L)
        every { prefs.lastUsedAccountIdFlow } returns flowOf(null)
        coEvery {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1L
        coEvery {
            transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any())
        } returns 1L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // The original tests below build their dates from the real clock, so they
    // run against it; the scheduling tests use a fixed clock.
    private fun newViewModel(
        clock: Clock = Clock.systemDefaultZone(),
        handle: SavedStateHandle = SavedStateHandle()
    ) = TransactionFormViewModel(
        handle,
        transactionRepository,
        accountRepository,
        categoryRepository,
        scheduledRepository,
        syncManager,
        prefs,
        clock
    )

    // PLAN §6.1: the initial form date must carry the device's TZ offset
    // (RFC3339 with offset, or Z) so the backend's future-vs-past routing is
    // correct cross-TZ from the very first save.
    @Test
    fun initialDateCarriesOffset() = runTest {
        val vm = newViewModel()
        val date = vm.uiState.value.date
        assertTrue(
            "date '$date' must end with ±HH:MM or Z",
            date.matches(Regex(".*[+-]\\d{2}:\\d{2}$")) || date.endsWith("Z")
        )
    }

    // Regression for the v1.27.x crash path: save() of a future-dated entry
    // strict-parsed state.date with ofPattern("yyyy-MM-dd'T'HH:mm") at the
    // delayed-check site — an offset-bearing string threw and crashed the
    // save. Post-1.28 it must route to scheduled creation without throwing.
    @Test
    fun saveFutureDatedOffsetStringRoutesToScheduled() = runTest {
        val vm = newViewModel()
        val future = OffsetDateTime.now(ZoneId.systemDefault())
            .plusHours(2)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        vm.updateAccountId(1L)
        vm.updateCategoryId(2L)
        vm.updateAmount("5.0")
        vm.updateDate(future)
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                1L, 2L, "expense", 5.0, any(), any(), any(),
                rrule = "FREQ=DAILY", nextOccurrence = future, maxOccurrences = 1
            )
        }
        coVerify(exactly = 0) {
            transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertTrue(vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    // Counter-case: a past-dated offset string stays on the plain transaction
    // path (no silent re-routing to scheduled).
    @Test
    fun savePastDatedOffsetStringCreatesTransaction() = runTest {
        val vm = newViewModel()
        val past = OffsetDateTime.now(ZoneId.systemDefault())
            .minusHours(2)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        vm.updateAccountId(1L)
        vm.updateCategoryId(2L)
        vm.updateAmount("5.0")
        vm.updateDate(past)
        vm.save()

        coVerify(exactly = 1) {
            transactionRepository.create(1L, 2L, "expense", 5.0, any(), any(), any(), past)
        }
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertTrue(vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    // Selecting a payee with a stable recurring amount pre-fills the amount +
    // currency into the empty form (USD proves the currency came from history).
    @Test
    fun selectSuggestionFillsAmountWhenEmpty() = runTest {
        coEvery { transactionRepository.suggestedAmount("Netflix") } returns (12.99 to "USD")
        val vm = newViewModel()

        vm.selectSuggestion(DescriptionSuggestion("Netflix", 2L))

        assertEquals("12.99", vm.uiState.value.amount)
        assertEquals("USD", vm.uiState.value.currency)
    }

    // A value the user already typed must never be clobbered by the suggestion.
    @Test
    fun selectSuggestionDoesNotClobberTypedAmount() = runTest {
        coEvery { transactionRepository.suggestedAmount("Netflix") } returns (12.99 to "USD")
        val vm = newViewModel()

        vm.updateAmount("50")
        vm.selectSuggestion(DescriptionSuggestion("Netflix", 2L))

        assertEquals("50", vm.uiState.value.amount)
    }

    // No stable recurring amount → the amount field is left untouched.
    @Test
    fun selectSuggestionWithoutStableAmountLeavesFieldEmpty() = runTest {
        coEvery { transactionRepository.suggestedAmount("Groceries") } returns null
        val vm = newViewModel()

        vm.selectSuggestion(DescriptionSuggestion("Groceries", 2L))

        assertTrue(vm.uiState.value.amount.isBlank())
    }

    // ---- Inline scheduling (fixed clock: 2026-11-13 10:00 in Rome) ----

    private val rome = ZoneId.of("Europe/Rome")
    private val fixed = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), rome)
    private val nowStr = "2026-11-13T10:00+01:00"

    private class MutableClock(var instant: Instant, private val zone: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
        override fun instant(): Instant = instant
    }

    private val expense = Category(2L, 1L, null, "Groceries", "expense", null, null)
    private val income = Category(3L, 1L, null, "Salary", "income", null, null)

    private fun scheduledVm(clock: Clock = fixed, sync: SyncManager = syncManager): TransactionFormViewModel {
        val vm = TransactionFormViewModel(
            SavedStateHandle(), transactionRepository, accountRepository, categoryRepository,
            scheduledRepository, sync, prefs, clock
        )
        vm.updateAccountId(1L)
        vm.updateCategoryId(2L)
        vm.updateAmount("5.0")
        return vm
    }

    // A real SyncManager (spied) over a mocked WorkManager in local sync mode,
    // so the wall-clock delay maths is exercised end to end.
    private fun realSyncManager(): SyncManager {
        every { prefs.getSyncModeImmediate() } returns "none"
        val wm: WorkManager = mockk(relaxed = true)
        return spyk(SyncManager(dagger.Lazy { wm }, mockk<SyncRepository>(relaxed = true), prefs))
    }

    private fun verifyNoWrites() {
        coVerify(exactly = 0) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { transactionRepository.update(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun TransactionFormViewModel.applyRecurring(
        date: LocalDate,
        time: LocalTime? = null,
        draft: (RecurrenceDraft) -> RecurrenceDraft = { it }
    ): Boolean {
        openScheduleSheet()
        setSheetMode(ScheduleMode.RECURRING)
        setSheetDate(date)
        if (time != null) setSheetTime(time)
        updateSheetDraft(draft)
        return applySchedule()
    }

    private fun TransactionFormViewModel.applyOneTime(date: LocalDate, time: LocalTime): Boolean {
        openScheduleSheet()
        setSheetMode(ScheduleMode.ONE_TIME)
        setSheetDate(date)
        setSheetTime(time)
        return applySchedule()
    }

    @Test
    fun initialDateComesFromTheClock() = runTest {
        assertEquals(nowStr, scheduledVm().uiState.value.date)
    }

    @Test
    fun normalCreateWritesOnlyATransaction() = runTest {
        val vm = scheduledVm()
        vm.save()

        coVerify(exactly = 1) { transactionRepository.create(1L, 2L, "expense", 5.0, any(), any(), any(), nowStr) }
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { syncManager.kickScheduler(any(), any()) }
        assertEquals(SaveOutcome.Created("expense"), vm.uiState.value.outcome)
        assertEquals("Expense added", vm.uiState.value.outcome?.message)
    }

    @Test
    fun oneTimeScheduleUsesTheExistingFutureDatePath() = runTest {
        val vm = scheduledVm()
        assertTrue(vm.applyOneTime(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0)))

        val state = vm.uiState.value
        assertEquals("2026-11-20T09:00+01:00", state.date)
        assertTrue(state.explicitOnce)
        assertNull(state.recurrence)
        assertNull(state.scheduleSheet)
        assertEquals(FormMode.SCHEDULE_ONCE, formMode(state, vm.now()))

        vm.save()
        coVerify(exactly = 1) {
            scheduledRepository.create(
                1L, 2L, "expense", 5.0, any(), any(), any(),
                rrule = "FREQ=DAILY", nextOccurrence = "2026-11-20T09:00+01:00", maxOccurrences = 1
            )
        }
        coVerify(exactly = 0) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { syncManager.kickScheduler("2026-11-20T09:00+01:00", fixed) }
        assertEquals("Scheduled for Fri, 20 Nov at 09:00", vm.uiState.value.outcome?.message)
    }

    @Test
    fun oneTimeThatPassesBeforeSaveIsRejectedThenAddNowCreates() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-13T09:00:00Z"), rome)
        val vm = scheduledVm(clock)
        assertTrue(vm.applyOneTime(LocalDate.of(2026, 11, 13), LocalTime.of(10, 30)))

        clock.instant = Instant.parse("2026-11-13T09:31:00Z") // 10:31 local
        vm.save()

        assertEquals(TransactionFormViewModel.MSG_TIME_PASSED, vm.uiState.value.error)
        assertTrue(vm.uiState.value.canAddNow)
        assertFalse(vm.uiState.value.saved)
        verifyNoWrites()

        vm.addNow()
        coVerify(exactly = 1) {
            transactionRepository.create(1L, 2L, "expense", 5.0, any(), any(), any(), "2026-11-13T10:31+01:00")
        }
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertFalse(vm.uiState.value.canAddNow)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun recurringMonthlyFutureStartSavesOnlyTheSchedule() = runTest {
        val sync = realSyncManager()
        val vm = scheduledVm(sync = sync)
        assertTrue(vm.applyRecurring(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0)))
        assertEquals("2026-11-20T09:00+01:00", vm.uiState.value.date)
        assertEquals(FormMode.SCHEDULE_RECURRING, formMode(vm.uiState.value, vm.now()))

        vm.save()
        coVerify(exactly = 1) {
            scheduledRepository.create(
                1L, 2L, "expense", 5.0, any(), any(), any(),
                rrule = "FREQ=MONTHLY", nextOccurrence = "2026-11-20T09:00+01:00", maxOccurrences = null
            )
        }
        coVerify(exactly = 0) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { sync.scheduleDelayedScheduledCheck(601_200_000L) }
        verify(exactly = 0) { sync.triggerImmediateScheduledCheck() }
        assertEquals("Repeats monthly from Fri, 20 Nov", vm.uiState.value.outcome?.message)
    }

    @Test
    fun recurringPastStartBackfillsThroughTheScheduler() = runTest {
        val sync = realSyncManager()
        val vm = scheduledVm(sync = sync)
        assertTrue(vm.applyRecurring(LocalDate.of(2026, 11, 6)) { it.copy(presetKey = "weekly") })
        assertEquals(FormMode.ADD_AND_REPEAT, formMode(vm.uiState.value, vm.now()))

        vm.save()
        coVerify(exactly = 1) {
            scheduledRepository.create(
                1L, 2L, "expense", 5.0, any(), any(), any(),
                rrule = "FREQ=WEEKLY", nextOccurrence = "2026-11-06T10:00+01:00", maxOccurrences = null
            )
        }
        coVerify(exactly = 0) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { sync.kickScheduler("2026-11-06T10:00+01:00", fixed) }
        verify(exactly = 1) { sync.triggerImmediateScheduledCheck() }
        verify(exactly = 1) { sync.scheduleDelayedSync(90_000L) }
        assertEquals("Repeats weekly, 2 entries added", vm.uiState.value.outcome?.message)
    }

    @Test
    fun sheetShowsPastEntriesAndCatchUpEnd() = runTest {
        val vm = scheduledVm()
        vm.openScheduleSheet()
        vm.updateSheetDraft { it.copy(presetKey = "weekly") }
        vm.setSheetDate(LocalDate.of(2026, 11, 6))

        var sheet = vm.uiState.value.scheduleSheet!!
        assertNull(sheet.error)
        assertEquals(
            listOf(LocalDateTime.of(2026, 11, 6, 10, 0), LocalDateTime.of(2026, 11, 13, 10, 0)),
            sheet.pastEntries
        )
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), sheet.upcoming.first())
        assertFalse(sheet.endsDuringCatchUp)

        vm.updateSheetDraft { it.copy(endMode = EndMode.COUNT, count = "2") }
        sheet = vm.uiState.value.scheduleSheet!!
        assertTrue(sheet.endsDuringCatchUp)
        assertTrue(sheet.upcoming.isEmpty())
    }

    @Test
    fun customIntervalCountAndEndDateEncode() = runTest {
        val cases = listOf<Pair<(RecurrenceDraft) -> RecurrenceDraft, Pair<String, Int?>>>(
            { d: RecurrenceDraft -> d.copy(presetKey = Rrule.CUSTOM_KEY, customInterval = "3", customUnit = Freq.WEEKLY) } to
                ("FREQ=WEEKLY;INTERVAL=3" to null),
            { d: RecurrenceDraft -> d.copy(endMode = EndMode.COUNT, count = "12") } to ("FREQ=MONTHLY" to 12),
            { d: RecurrenceDraft -> d.copy(endMode = EndMode.DATE, endDate = "2026-12-31") } to
                ("FREQ=MONTHLY;UNTIL=20261231T235959Z" to null)
        )
        for ((edit, expected) in cases) {
            val vm = scheduledVm()
            assertTrue(vm.applyRecurring(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0), edit))
            vm.save()
            coVerify(exactly = 1) {
                scheduledRepository.create(
                    any(), any(), any(), any(), any(), any(), any(),
                    rrule = expected.first, nextOccurrence = "2026-11-20T09:00+01:00", maxOccurrences = expected.second
                )
            }
        }
    }

    @Test
    fun invalidSheetInputKeepsTheSheetOpenAndRecurrenceNull() = runTest {
        val cases = listOf<Pair<(RecurrenceDraft) -> RecurrenceDraft, String>>(
            { d: RecurrenceDraft -> d.copy(endMode = EndMode.COUNT, count = "0") } to RecurrenceDraft.MSG_COUNT,
            { d: RecurrenceDraft -> d.copy(endMode = EndMode.COUNT, count = "1000") } to RecurrenceDraft.MSG_COUNT,
            { d: RecurrenceDraft -> d.copy(presetKey = Rrule.CUSTOM_KEY, customInterval = "0") } to RecurrenceDraft.MSG_INTERVAL,
            { d: RecurrenceDraft -> d.copy(presetKey = Rrule.CUSTOM_KEY, customInterval = "") } to RecurrenceDraft.MSG_INTERVAL,
            { d: RecurrenceDraft -> d.copy(endMode = EndMode.DATE, endDate = "2026-11-19") } to RecurrenceDraft.MSG_END_BEFORE_START,
            { d: RecurrenceDraft -> d.copy(endMode = EndMode.DATE) } to RecurrenceDraft.MSG_PICK_END_DATE
        )
        for ((edit, message) in cases) {
            val vm = scheduledVm()
            assertFalse(vm.applyRecurring(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0), edit))
            assertEquals(message, vm.uiState.value.scheduleSheet?.error)
            assertNull(vm.uiState.value.recurrence)
            assertEquals(nowStr, vm.uiState.value.date)
        }
    }

    @Test
    fun tooManyPastEntriesIsASheetError() = runTest {
        val vm = scheduledVm()
        assertFalse(vm.applyRecurring(LocalDate.of(2025, 1, 1)) { it.copy(presetKey = "daily") })
        assertEquals(RecurrenceDraft.MSG_TOO_MANY_PAST, vm.uiState.value.scheduleSheet?.error)
    }

    @Test
    fun oneTimeMustBeInTheFuture() = runTest {
        val vm = scheduledVm()
        vm.openScheduleSheet()
        vm.setSheetMode(ScheduleMode.ONE_TIME) // prefilled with now
        assertEquals(TransactionFormViewModel.MSG_ONE_TIME_PAST, vm.uiState.value.scheduleSheet?.error)
        assertFalse(vm.applySchedule())

        vm.setSheetTime(LocalTime.of(10, 1))
        assertNull(vm.uiState.value.scheduleSheet?.error)
    }

    @Test
    fun quickPicksKeepTheTimeOfDay() = runTest {
        val vm = scheduledVm()
        vm.openScheduleSheet()
        vm.applyQuickPick(QuickPick.TOMORROW)
        var sheet = vm.uiState.value.scheduleSheet!!
        assertEquals(ScheduleMode.ONE_TIME, sheet.mode)
        assertEquals(LocalDate.of(2026, 11, 14), sheet.date)
        assertEquals(LocalTime.of(10, 0), sheet.time)
        assertNull(sheet.error)

        vm.applyQuickPick(QuickPick.IN_A_WEEK)
        assertEquals(LocalDate.of(2026, 11, 20), vm.uiState.value.scheduleSheet!!.date)
        vm.applyQuickPick(QuickPick.IN_A_MONTH)
        sheet = vm.uiState.value.scheduleSheet!!
        assertEquals(LocalDate.of(2026, 12, 13), sheet.date)
        assertTrue(vm.applySchedule())
        assertEquals("2026-12-13T10:00+01:00", vm.uiState.value.date)
    }

    @Test
    fun openingPrefillsFromTheForm() = runTest {
        val vm = scheduledVm()
        vm.openScheduleSheet()
        var sheet = vm.uiState.value.scheduleSheet!!
        assertEquals(ScheduleMode.RECURRING, sheet.mode)
        assertEquals(RecurrenceDraft(), sheet.draft)
        assertFalse(sheet.isEditingExisting)
        vm.dismissScheduleSheet()

        vm.setDate(LocalDate.of(2026, 11, 25)) // typed future date
        vm.openScheduleSheet()
        sheet = vm.uiState.value.scheduleSheet!!
        assertEquals(ScheduleMode.ONE_TIME, sheet.mode)
        assertEquals(LocalDate.of(2026, 11, 25), sheet.date)
        assertTrue(sheet.isEditingExisting)
        vm.dismissScheduleSheet()

        assertTrue(vm.applyRecurring(LocalDate.of(2026, 11, 20)) { it.copy(presetKey = "weekly") })
        vm.openScheduleSheet()
        sheet = vm.uiState.value.scheduleSheet!!
        assertEquals(ScheduleMode.RECURRING, sheet.mode)
        assertEquals("weekly", sheet.draft.presetKey)
        assertEquals(LocalDate.of(2026, 11, 20), sheet.date)
        assertTrue(sheet.isEditingExisting)
    }

    @Test
    fun dismissKeepsTheFormUnchanged() = runTest {
        val vm = scheduledVm()
        vm.updateDescription("Rent")
        vm.updateNote("November")
        vm.openScheduleSheet()
        vm.setSheetDate(LocalDate.of(2026, 12, 1))
        vm.updateSheetDraft { it.copy(presetKey = "weekly") }
        vm.dismissScheduleSheet()

        val state = vm.uiState.value
        assertNull(state.scheduleSheet)
        assertNull(state.recurrence)
        assertEquals(nowStr, state.date)
        assertEquals("Rent", state.description)
        assertEquals("November", state.note)
        assertEquals("5.0", state.amount)
    }

    @Test
    fun removeRestoresAPastPreScheduleDate() = runTest {
        val vm = scheduledVm()
        vm.setDate(LocalDate.of(2026, 11, 10))
        assertEquals("2026-11-10T10:00+01:00", vm.uiState.value.date)
        assertTrue(vm.applyRecurring(LocalDate.of(2026, 11, 20)))
        assertEquals("2026-11-10T10:00+01:00", vm.uiState.value.dateBeforeSchedule)

        vm.removeSchedule()
        val state = vm.uiState.value
        assertNull(state.recurrence)
        assertFalse(state.explicitOnce)
        assertEquals("2026-11-10T10:00+01:00", state.date)
        assertEquals(FormMode.ADD, formMode(state, vm.now()))

        vm.save()
        coVerify(exactly = 1) {
            transactionRepository.create(1L, 2L, "expense", 5.0, any(), any(), any(), "2026-11-10T10:00+01:00")
        }
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun removeAfterAFutureOneTimeGoesToNow() = runTest {
        val vm = scheduledVm()
        vm.setDate(LocalDate.of(2026, 11, 25))
        assertTrue(vm.applyOneTime(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0)))
        vm.removeSchedule()

        assertEquals(nowStr, vm.uiState.value.date)
        assertFalse(vm.uiState.value.explicitOnce)
        assertNull(vm.uiState.value.dateBeforeSchedule)
    }

    @Test
    fun editModeCannotScheduleAndUpdatesOnly() = runTest {
        coEvery { transactionRepository.getById(5L) } returns Transaction(
            id = 5L, accountId = 1L, categoryId = 2L, userId = 1L, type = "expense", amount = 7.0,
            currency = "EUR", description = "Old", note = null, date = "2026-11-20T09:00:00+01:00"
        )
        val vm = TransactionFormViewModel(
            SavedStateHandle(mapOf("id" to "5")), transactionRepository, accountRepository,
            categoryRepository, scheduledRepository, syncManager, prefs, fixed
        )
        vm.openScheduleSheet()
        assertNull(vm.uiState.value.scheduleSheet)
        assertEquals(FormMode.EDIT, formMode(vm.uiState.value, vm.now()))

        vm.save()
        coVerify(exactly = 1) {
            transactionRepository.update(5L, 1L, 2L, "expense", 7.0, "EUR", "Old", null, "2026-11-20T09:00:00+01:00")
        }
        coVerify(exactly = 0) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertEquals(SaveOutcome.Updated, vm.uiState.value.outcome)
        assertEquals("Transaction updated", vm.uiState.value.outcome?.message)
    }

    @Test
    fun doubleSaveWritesOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery {
            transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers { gate.await(); 1L }
        val vm = scheduledVm()

        vm.save()
        assertTrue(vm.uiState.value.isSaving)
        vm.save()
        gate.complete(Unit)

        coVerify(exactly = 1) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(vm.uiState.value.saved)
        vm.save()
        coVerify(exactly = 1) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun garbageDateIsRejectedWithoutWriting() = runTest {
        val vm = scheduledVm()
        vm.updateDate("garbage")
        vm.save()

        assertEquals(TransactionFormViewModel.MSG_INVALID_DATE, vm.uiState.value.error)
        assertFalse(vm.uiState.value.saved)
        verifyNoWrites()
    }

    @Test
    fun descriptionOver500BytesIsRejected() = runTest {
        val vm = scheduledVm()
        vm.updateDescription("é".repeat(250) + "a") // 251 chars, 501 bytes
        vm.save()

        assertEquals("Description is too long", vm.uiState.value.descriptionError)
        verifyNoWrites()

        vm.updateDescription("é".repeat(250)) // exactly 500 bytes
        assertNull(vm.uiState.value.descriptionError)
        vm.save()
        coVerify(exactly = 1) { transactionRepository.create(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun noteOver2000BytesIsRejected() = runTest {
        val vm = scheduledVm()
        vm.updateNote("€".repeat(667)) // 2001 bytes
        vm.save()
        assertEquals("Note is too long", vm.uiState.value.noteError)
        verifyNoWrites()
    }

    @Test
    fun nonFiniteOrNonPositiveAmountIsAFieldError() = runTest {
        for (bad in listOf("Infinity", "NaN", "0", "-3", "abc")) {
            val vm = scheduledVm()
            vm.updateAmount(bad)
            vm.save()
            assertEquals(bad, TransactionFormViewModel.MSG_AMOUNT, vm.uiState.value.amountError)
        }
        verifyNoWrites()
    }

    @Test
    fun typeSwitchClearsAMismatchedCategory() = runTest {
        coEvery { categoryRepository.getCached() } returns listOf(expense, income)
        val vm = scheduledVm()
        vm.updateType("income")
        assertNull(vm.uiState.value.categoryId)

        vm.updateCategoryId(3L)
        vm.updateType("income")
        assertEquals(3L, vm.uiState.value.categoryId)
        vm.updateType("expense")
        assertNull(vm.uiState.value.categoryId)
    }

    @Test
    fun pickerEditsFixTheOffsetAndFlagDstGaps() = runTest {
        val vm = scheduledVm()
        vm.setDate(LocalDate.of(2026, 7, 14))
        assertEquals("2026-07-14T10:00+02:00", vm.uiState.value.date)
        assertFalse(vm.uiState.value.dstAdjusted)

        vm.setDate(LocalDate.of(2026, 3, 29))
        vm.setTime(LocalTime.of(2, 30))
        assertEquals("2026-03-29T03:30+02:00", vm.uiState.value.date)
        assertTrue(vm.uiState.value.dstAdjusted)

        vm.setTime(LocalTime.of(9, 15))
        assertEquals("2026-03-29T09:15+02:00", vm.uiState.value.date)
        assertFalse(vm.uiState.value.dstAdjusted)
    }

    @Test
    fun manualPickerEditClearsExplicitOnce() = runTest {
        val vm = scheduledVm()
        assertTrue(vm.applyOneTime(LocalDate.of(2026, 11, 20), LocalTime.of(9, 0)))
        vm.setTime(LocalTime.of(11, 0))
        assertFalse(vm.uiState.value.explicitOnce)
        assertEquals("2026-11-20T11:00+01:00", vm.uiState.value.date)
        // Still a future date, so still a one-time schedule via the typed path.
        assertEquals(FormMode.SCHEDULE_ONCE, formMode(vm.uiState.value, vm.now()))
    }

    @Test
    fun formModeCoversEveryCase() {
        val now = OffsetDateTime.parse("2026-11-13T10:00+01:00")
        val base = TransactionFormUiState(date = "2026-11-13T10:00+01:00")
        assertEquals(FormMode.ADD, formMode(base, now))
        assertEquals(FormMode.SCHEDULE_ONCE, formMode(base.copy(date = "2026-11-13T10:01+01:00"), now))
        assertEquals(FormMode.SCHEDULE_ONCE, formMode(base.copy(explicitOnce = true), now))
        assertEquals(FormMode.ADD_AND_REPEAT, formMode(base.copy(recurrence = RecurrenceDraft()), now))
        assertEquals(
            FormMode.ADD_AND_REPEAT,
            formMode(base.copy(recurrence = RecurrenceDraft(), date = "2026-11-06T10:00+01:00"), now)
        )
        assertEquals(
            FormMode.SCHEDULE_RECURRING,
            formMode(base.copy(recurrence = RecurrenceDraft(), date = "2026-11-20T09:00+01:00"), now)
        )
        assertEquals(FormMode.EDIT, formMode(base.copy(id = 5L, date = "2026-11-20T09:00+01:00"), now))
        assertEquals(FormMode.ADD, formMode(base.copy(date = "garbage"), now))

        assertEquals("Add expense", FormMode.ADD.primaryLabel("expense"))
        assertEquals("Add income", FormMode.ADD.primaryLabel("income"))
        assertEquals("Schedule expense", FormMode.SCHEDULE_ONCE.primaryLabel("expense"))
        assertEquals("Schedule income", FormMode.SCHEDULE_RECURRING.primaryLabel("income"))
        assertEquals("Add & repeat", FormMode.ADD_AND_REPEAT.primaryLabel("expense"))
        assertEquals("Save changes", FormMode.EDIT.primaryLabel("income"))
    }

    @Test
    fun saveOutcomeMessages() {
        val start = LocalDateTime.of(2026, 11, 20, 9, 0)
        assertEquals("Income added", SaveOutcome.Created("income").message)
        assertEquals("Scheduled for Sat, 14 Nov at 09:00", SaveOutcome.ScheduledOnce(LocalDateTime.of(2026, 11, 14, 9, 0)).message)
        assertEquals("Repeats monthly, first entry added", SaveOutcome.ScheduledRecurring("Monthly", true, start).message)
        assertEquals("Repeats every 3 weeks from Fri, 20 Nov", SaveOutcome.ScheduledRecurring("Every 3 weeks", false, start).message)
        assertNotNull(SaveOutcome.Updated.message)
    }
}
