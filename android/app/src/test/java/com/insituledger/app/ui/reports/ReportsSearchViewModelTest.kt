package com.insituledger.app.ui.reports

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.CurrencySummaryRow
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsSearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val prefs: UserPreferences = mockk(relaxed = true)

    private val valencia = listOf(
        CurrencySummaryRow(currency = "EUR", income = 80.0, expense = 392.0, count = 5),
        CurrencySummaryRow(currency = "USD", income = 0.0, expense = 40.0, count = 1)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { categoryRepository.getAll() } returns flowOf(emptyList())
        every { prefs.weekStartDayFlow } returns flowOf("monday")
        coEvery { transactionRepository.getCategoryBreakdown(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReportsViewModel(transactionRepository, categoryRepository, prefs)

    @Test
    fun `search totals each currency separately and nets them out`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia", any(), any()) } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        val rows = vm.uiState.value.searchSummary
        assertEquals(listOf("EUR", "USD"), rows.map { it.currency })
        assertEquals(-312.0, rows[0].net, 0.001)
        assertEquals(-40.0, rows[1].net, 0.001)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `typing debounces to a single query for the final term`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary(any(), any(), any()) } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("v")
        vm.setSearchQuery("val")
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        coVerify(exactly = 1) { transactionRepository.searchSummary("valencia", any(), any()) }
        coVerify(exactly = 0) { transactionRepository.searchSummary("v", any(), any()) }
        coVerify(exactly = 0) { transactionRepository.searchSummary("val", any(), any()) }
    }

    @Test
    fun `a blank query never reaches the database`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setSearchQuery("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepository.searchSummary(any(), any(), any()) }
        assertTrue(vm.uiState.value.searchSummary.isEmpty())
        assertFalse(vm.uiState.value.isSearching)
    }

    // A stale total sitting under an emptied box reads as a result for
    // "everything", so clearing must not wait for the debounce.
    @Test
    fun `clearing the query drops the previous totals immediately`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia", any(), any()) } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("valencia")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.searchSummary.size)

        vm.setSearchQuery("")

        assertTrue(vm.uiState.value.searchSummary.isEmpty())
        assertEquals("", vm.uiState.value.searchQuery)
    }

    // One date control drives the whole screen, search included, so the chips
    // have to reach the query rather than being silently dropped.
    @Test
    fun `search is scoped by the report date range`() = runTest(dispatcher) {
        val from = slot<String>()
        val to = slot<String>()
        coEvery {
            transactionRepository.searchSummary("valencia", capture(from), capture(to))
        } returns valencia
        val vm = viewModel()

        vm.setDateRangePreset(DateRangePreset.THIS_YEAR)
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.searchSummary.size)
        val expected = resolveRange(DateRangePreset.THIS_YEAR, "", "", DayOfWeek.MONDAY)
        assertEquals(expected.first, from.captured)
        assertEquals(expected.second, to.captured)
    }

    @Test
    fun `all time sends no bounds at all`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia", null, null) } returns valencia
        val vm = viewModel()

        vm.setDateRangePreset(DateRangePreset.ALL_TIME)
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.searchSummary.size)
        coVerify(exactly = 1) { transactionRepository.searchSummary("valencia", null, null) }
    }

    // Tapping a chip is one deliberate action, not typing, so the results
    // update without waiting out the debounce.
    @Test
    fun `changing the range re-runs an active search straight away`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia", any(), any()) } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        vm.setDateRangePreset(DateRangePreset.LAST_YEAR)
        advanceUntilIdle()

        val (from, to) = resolveRange(DateRangePreset.LAST_YEAR, "", "", DayOfWeek.MONDAY)
        coVerify(exactly = 1) { transactionRepository.searchSummary("valencia", from, to) }
    }

    // Chips are used constantly with an empty search box; each tap must not
    // become a LIKE '%%' scan of the whole table.
    @Test
    fun `changing the range with an empty query never reaches the database`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setDateRangePreset(DateRangePreset.THIS_YEAR)
        vm.setCustomDateRange("2026-01-01", "2026-03-01")
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepository.searchSummary(any(), any(), any()) }
        assertFalse(vm.uiState.value.isSearching)
    }

    // Backwards bounds match nothing, which reads as "no such transactions"
    // rather than "you filled the fields in the wrong order".
    @Test
    fun `a reversed custom range is swapped rather than left empty`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia", any(), any()) } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        vm.setCustomDateRange("2026-03-01", "2026-01-01")
        advanceUntilIdle()

        assertEquals("2026-01-01", vm.uiState.value.customFrom)
        assertEquals("2026-03-01", vm.uiState.value.customTo)
        coVerify(exactly = 1) {
            transactionRepository.searchSummary("valencia", "2026-01-01", "2026-03-01")
        }
    }

    // Without a catch the spinner would sit there forever on any DB error.
    @Test
    fun `a failing query clears the spinner instead of hanging`() = runTest(dispatcher) {
        coEvery {
            transactionRepository.searchSummary("valencia", any(), any())
        } throws IllegalStateException("db gone")
        val vm = viewModel()

        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isSearching)
        assertTrue(vm.uiState.value.searchSummary.isEmpty())
    }
}
