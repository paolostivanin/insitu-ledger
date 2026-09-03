package com.insituledger.app.ui.reports

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.CurrencySummaryRow
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        coEvery { transactionRepository.searchSummary("valencia") } returns valencia
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
        coEvery { transactionRepository.searchSummary(any()) } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("v")
        vm.setSearchQuery("val")
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        coVerify(exactly = 1) { transactionRepository.searchSummary("valencia") }
        coVerify(exactly = 0) { transactionRepository.searchSummary("v") }
        coVerify(exactly = 0) { transactionRepository.searchSummary("val") }
    }

    @Test
    fun `a blank query never reaches the database`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setSearchQuery("   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { transactionRepository.searchSummary(any()) }
        assertTrue(vm.uiState.value.searchSummary.isEmpty())
        assertFalse(vm.uiState.value.isSearching)
    }

    // A stale total sitting under an emptied box reads as a result for
    // "everything", so clearing must not wait for the debounce.
    @Test
    fun `clearing the query drops the previous totals immediately`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia") } returns valencia
        val vm = viewModel()

        vm.setSearchQuery("valencia")
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.searchSummary.size)

        vm.setSearchQuery("")

        assertTrue(vm.uiState.value.searchSummary.isEmpty())
        assertEquals("", vm.uiState.value.searchQuery)
    }

    // The search is a lookup for something that already happened, so it must
    // not inherit the report's THIS_MONTH default — a past trip would total 0.
    @Test
    fun `search ignores the report date range`() = runTest(dispatcher) {
        coEvery { transactionRepository.searchSummary("valencia") } returns valencia
        val vm = viewModel()

        vm.setDateRangePreset(DateRangePreset.THIS_WEEK)
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.searchSummary.size)
        coVerify(exactly = 1) { transactionRepository.searchSummary("valencia") }
    }
}
