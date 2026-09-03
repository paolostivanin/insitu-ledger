package com.insituledger.app.ui.transactions

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsSearchViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val sharedAccessState = SharedAccessState()
    private val prefs: UserPreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { categoryRepository.getAll() } returns flowOf(emptyList())
        every { accountRepository.getAll() } returns flowOf(emptyList())
        every { prefs.userIdFlow } returns flowOf(1L)
        every {
            transactionRepository.getSorted(any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TransactionsViewModel(
        transactionRepository, categoryRepository, accountRepository,
        syncManager, sharedAccessState, prefs
    )

    // The bug this replaced: searching swapped in an unfiltered, unsorted,
    // unpaginated query, so an active month/category filter silently vanished.
    @Test
    fun `search keeps the active filters, sort and page limit`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setFilters("2026-08-01", "2026-08-31", 3L)
        vm.setSort("amount", "asc")
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        verify {
            transactionRepository.getSorted(
                from = "2026-08-01", to = "2026-08-31", categoryId = 3L,
                search = "valencia", sortBy = "amount", sortDir = "asc",
                limit = 100, offset = 0
            )
        }
    }

    @Test
    fun `an empty search box queries with no search predicate`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setSearchQuery("")
        advanceUntilIdle()

        verify {
            transactionRepository.getSorted(
                from = null, to = null, categoryId = null, search = null,
                sortBy = "date", sortDir = "desc", limit = 100, offset = 0
            )
        }
    }

    @Test
    fun `loading more pages while searching grows the limit`() = runTest(dispatcher) {
        // A full page is what enables loadMore; the repo returns an empty list
        // by default, so drive hasMore through a filled page instead.
        every {
            transactionRepository.getSorted(any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(List(100) { mockk(relaxed = true) })

        val vm = viewModel()
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        verify {
            transactionRepository.getSorted(
                from = null, to = null, categoryId = null, search = "valencia",
                sortBy = "date", sortDir = "desc", limit = 200, offset = 0
            )
        }
    }

    @Test
    fun `clearing search and filters resets the query but keeps the sort`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setFilters("2026-08-01", "2026-08-31", 3L)
        vm.setSort("amount", "asc")
        vm.setSearchQuery("valencia")
        advanceUntilIdle()

        vm.clearSearchAndFilters()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("", state.searchQuery)
        assertFalse(state.isSearchActive)
        assertNull(state.filterFrom)
        assertNull(state.filterTo)
        assertNull(state.filterCategoryId)
        assertEquals("amount", state.sortBy)

        verify {
            transactionRepository.getSorted(
                from = null, to = null, categoryId = null, search = null,
                sortBy = "amount", sortDir = "asc", limit = 100, offset = 0
            )
        }
    }
}
