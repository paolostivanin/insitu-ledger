package com.insituledger.app.ui.scheduled

import androidx.lifecycle.SavedStateHandle
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.ScheduledTransaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// Characterization tests: written against the pre-refactor
// ScheduledFormViewModel and kept green through the RecurrenceDraft
// extraction. They pin the lenient validation messages, the wire strings and
// the "UNTIL plus max saves max = null" quirk. The Clock / SyncManager
// constructor parameters and the tests below the marker came with the refactor.
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledFormViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private val scheduledRepository: ScheduledRepository = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)

    // 2026-11-13 10:00 in Rome.
    private val clock = Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneId.of("Europe/Rome"))

    private val account = Account(id = 1L, userId = 1L, name = "Checking", currency = "EUR", balance = 0.0)
    private val expenseCategory = Category(2L, 1L, null, "Groceries", "expense", null, null)
    private val incomeCategory = Category(3L, 1L, null, "Salary", "income", null, null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { accountRepository.getAll() } returns flowOf(listOf(account))
        every { categoryRepository.getAll() } returns flowOf(listOf(expenseCategory, incomeCategory))
        every { prefs.userIdFlow } returns flowOf(1L)
        every { prefs.lastUsedAccountIdFlow } returns flowOf(null)
        coEvery {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns -1L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(id: String? = null) = ScheduledFormViewModel(
        SavedStateHandle(if (id != null) mapOf("id" to id) else emptyMap()),
        scheduledRepository,
        accountRepository,
        categoryRepository,
        transactionRepository,
        prefs,
        syncManager,
        clock
    )

    private fun stored(rrule: String, maxOccurrences: Int? = null) = ScheduledTransaction(
        id = 7L, accountId = 1L, categoryId = 2L, userId = 1L, type = "expense",
        amount = 12.5, currency = "EUR", description = "Rent", note = null,
        rrule = rrule, nextOccurrence = "2026-11-20T09:00:00+01:00", active = true,
        maxOccurrences = maxOccurrences
    )

    private fun ScheduledFormViewModel.fillRequired() {
        updateCategoryId(2L)
        updateAmount("12.5")
    }

    @Test
    fun defaultsSaveMonthlyWithoutMax() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                1L, 2L, "expense", 12.5, "EUR", any(), any(),
                rrule = "FREQ=MONTHLY", nextOccurrence = any(), maxOccurrences = null
            )
        }
        assertTrue(vm.uiState.value.saved)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun blankCountSavesNull() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateEndMode("count")
        vm.updateMaxOccurrences("")
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                any(), any(), any(), any(), any(), any(), any(),
                rrule = "FREQ=MONTHLY", nextOccurrence = any(), maxOccurrences = null
            )
        }
    }

    @Test
    fun countSavesMax() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateEndMode("count")
        vm.updateMaxOccurrences("12")
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                any(), any(), any(), any(), any(), any(), any(),
                rrule = "FREQ=MONTHLY", nextOccurrence = any(), maxOccurrences = 12
            )
        }
    }

    @Test
    fun blankEndDateIsRejected() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateEndMode("date")
        vm.save()

        assertEquals("Pick an end date", vm.uiState.value.error)
        assertFalse(vm.uiState.value.saved)
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun zeroCustomIntervalIsRejectedWithExistingMessage() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateFrequency("custom")
        vm.updateCustomInterval("0")
        vm.save()

        assertEquals("Repeat every must be a whole number between 1 and 999", vm.uiState.value.error)
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun missingRequiredFieldsAreRejectedBeforeRecurrenceChecks() = runTest {
        val vm = newViewModel()
        vm.updateEndMode("date")
        vm.save()

        assertEquals("Please fill all required fields", vm.uiState.value.error)
    }

    @Test
    fun customIntervalAndEndDateEncode() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateFrequency("custom")
        vm.updateCustomInterval("3")
        vm.updateCustomUnit(com.insituledger.app.util.Freq.WEEKLY)
        vm.updateEndMode("date")
        vm.updateEndDate("2026-12-31")
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                any(), any(), any(), any(), any(), any(), any(),
                rrule = "FREQ=WEEKLY;INTERVAL=3;UNTIL=20261231T235959Z",
                nextOccurrence = any(), maxOccurrences = null
            )
        }
    }

    @Test
    fun storedCustomUntilRuleRoundTripsByteForByte() = runTest {
        val rule = "FREQ=MONTHLY;INTERVAL=3;UNTIL=20261231T235959Z"
        coEvery { scheduledRepository.getById(7L) } returns stored(rule)
        val vm = newViewModel(id = "7")

        assertEquals("quarterly", vm.uiState.value.frequency)
        assertEquals("date", vm.uiState.value.endMode)
        assertEquals("2026-12-31", vm.uiState.value.endDate)
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.update(
                7L, 1L, 2L, "expense", 12.5, "EUR", "Rent", null,
                rule, "2026-11-20T09:00+01:00", null, true
            )
        }
    }

    @Test
    fun untilPlusMaxSavesNullMax() = runTest {
        coEvery { scheduledRepository.getById(7L) } returns
            stored("FREQ=WEEKLY;UNTIL=20270630T235959Z", maxOccurrences = 5)
        val vm = newViewModel(id = "7")
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.update(
                7L, any(), any(), any(), any(), any(), any(), any(),
                "FREQ=WEEKLY;UNTIL=20270630T235959Z", any(), null, any()
            )
        }
    }

    @Test
    fun storedPresetsRoundTrip() = runTest {
        for (rule in listOf("FREQ=DAILY", "FREQ=WEEKLY", "FREQ=WEEKLY;INTERVAL=2", "FREQ=MONTHLY", "FREQ=MONTHLY;INTERVAL=3", "FREQ=YEARLY", "FREQ=DAILY;INTERVAL=45")) {
            coEvery { scheduledRepository.getById(7L) } returns stored(rule, maxOccurrences = 4)
            val vm = newViewModel(id = "7")
            vm.save()
            coVerify(exactly = 1) {
                scheduledRepository.update(
                    7L, any(), any(), any(), any(), any(), any(), any(),
                    rule, any(), 4, any()
                )
            }
        }
    }

    // ---- Added with the RecurrenceDraft refactor ----

    @Test
    fun defaultStartDateComesFromTheClock() = runTest {
        assertEquals("2026-11-13", newViewModel().uiState.value.nextDate)
    }

    @Test
    fun savingDueStartKicksTheScheduler() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateNextTime("08:00") // today 08:00 < now 10:00
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                any(), any(), any(), any(), any(), any(), any(),
                rrule = "FREQ=MONTHLY", nextOccurrence = "2026-11-13T08:00+01:00", maxOccurrences = null
            )
        }
        verify(exactly = 1) { syncManager.kickScheduler("2026-11-13T08:00+01:00", clock) }
        assertEquals("Schedule created", vm.uiState.value.savedMessage)
    }

    @Test
    fun updateKicksTheSchedulerAndSaysUpdated() = runTest {
        coEvery { scheduledRepository.getById(7L) } returns stored("FREQ=MONTHLY")
        val vm = newViewModel(id = "7")
        vm.save()

        verify(exactly = 1) { syncManager.kickScheduler("2026-11-20T09:00+01:00", clock) }
        assertEquals("Schedule updated", vm.uiState.value.savedMessage)
    }

    @Test
    fun failedValidationDoesNotKick() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateEndMode("date")
        vm.save()

        verify(exactly = 0) { syncManager.kickScheduler(any(), any()) }
    }

    @Test
    fun updateRecurrenceDrivesTheWireString() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateRecurrence { it.copy(presetKey = "custom", customInterval = "45", customUnit = com.insituledger.app.util.Freq.DAILY) }
        assertEquals("custom", vm.uiState.value.frequency)
        vm.save()

        coVerify(exactly = 1) {
            scheduledRepository.create(
                any(), any(), any(), any(), any(), any(), any(),
                rrule = "FREQ=DAILY;INTERVAL=45", nextOccurrence = any(), maxOccurrences = null
            )
        }
    }

    @Test
    fun previewUsesRepeatedAdvance() = runTest {
        val vm = newViewModel()
        vm.updateNextDate("2026-01-31")
        assertEquals(listOf("31 Jan", "28 Feb", "28 Mar"), vm.uiState.value.previewOccurrences)
    }

    @Test
    fun editLoadsOccurrenceCount() = runTest {
        coEvery { scheduledRepository.getById(7L) } returns stored("FREQ=MONTHLY", maxOccurrences = 12).copy(occurrenceCount = 3)
        assertEquals(3, newViewModel(id = "7").uiState.value.occurrenceCount)
    }

    @Test
    fun typeSwitchClearsMismatchedCategory() = runTest {
        val vm = newViewModel()
        vm.updateCategoryId(2L) // expense category
        vm.updateType("expense")
        assertEquals(2L, vm.uiState.value.categoryId)
        vm.updateType("income")
        assertNull(vm.uiState.value.categoryId)
    }

    @Test
    fun descriptionOverByteLimitIsRejected() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateDescription("é".repeat(251)) // 502 UTF-8 bytes, 251 chars
        vm.save()

        assertEquals("Description is too long", vm.uiState.value.descriptionError)
        coVerify(exactly = 0) {
            scheduledRepository.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun noteOverByteLimitIsRejected() = runTest {
        val vm = newViewModel()
        vm.fillRequired()
        vm.updateNote("€".repeat(667)) // 2001 UTF-8 bytes
        vm.save()

        assertEquals("Note is too long", vm.uiState.value.noteError)
        assertFalse(vm.uiState.value.saved)
    }

    @Test
    fun nonFiniteAmountIsRejected() = runTest {
        val vm = newViewModel()
        vm.updateCategoryId(2L)
        vm.updateAmount("NaN")
        vm.save()

        assertEquals("Please fill all required fields", vm.uiState.value.error)
    }
}
