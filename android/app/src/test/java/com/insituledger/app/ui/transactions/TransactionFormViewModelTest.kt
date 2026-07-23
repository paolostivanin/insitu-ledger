package com.insituledger.app.ui.transactions

import androidx.lifecycle.SavedStateHandle
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

    private fun newViewModel() = TransactionFormViewModel(
        SavedStateHandle(),
        transactionRepository,
        accountRepository,
        categoryRepository,
        scheduledRepository,
        syncManager,
        prefs
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
}
