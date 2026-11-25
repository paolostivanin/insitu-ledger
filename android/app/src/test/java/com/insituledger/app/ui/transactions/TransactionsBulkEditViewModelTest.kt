package com.insituledger.app.ui.transactions

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.BulkEditCounts
import com.insituledger.app.data.repository.BulkEditRepository
import com.insituledger.app.data.repository.BulkEditRequest
import com.insituledger.app.data.repository.BulkEditResult
import com.insituledger.app.data.repository.BulkEditSummary
import com.insituledger.app.data.repository.CategoryRepository
import com.insituledger.app.data.repository.ScheduledRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionFilter
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsBulkEditViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val transactionRepository: TransactionRepository = mockk(relaxed = true)
    private val categoryRepository: CategoryRepository = mockk(relaxed = true)
    private val accountRepository: AccountRepository = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)
    private val sharedAccessState = SharedAccessState()
    private val prefs: UserPreferences = mockk(relaxed = true)
    private val scheduledRepository: ScheduledRepository = mockk(relaxed = true)
    private val bulkEditRepository: BulkEditRepository = mockk(relaxed = true)

    private val ownAccount = Account(1, ME, "Main", "EUR", 0.0)
    private val sharedAccount = Account(2, ME, "Family", "EUR", 0.0, isShared = true)
    private val accounts = MutableStateFlow(listOf(ownAccount))

    // The (exactMatch, onlyMineUserId) of every list query, in order.
    private val queries = mutableListOf<Pair<Boolean, Long?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { categoryRepository.getAll() } returns flowOf(listOf(
            Category(10, ME, null, "Salary", "income", null, null),
            Category(11, ME, null, "Bonus", "income", null, null)
        ))
        every { accountRepository.getAll() } returns accounts
        every { prefs.userIdFlow } returns flowOf(ME)
        every { scheduledRepository.getAllById() } returns flowOf(emptyMap())
        stubRows(emptyList())
        // By default every selected row still matches the filter.
        coEvery { transactionRepository.filterIds(any(), any()) } answers { secondArg<Collection<Long>>().distinct() }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stubRows(rows: List<Transaction>) {
        every {
            transactionRepository.getSorted(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            queries += (arg<Boolean>(9) to arg<Long?>(10))
            flowOf(rows)
        }
    }

    private fun txn(id: Long) = Transaction(id, 1, 10, ME, "income", 10.0, "EUR", "Stipendio", null, "2026-10-27")

    private fun viewModel() = TransactionsViewModel(
        transactionRepository, categoryRepository, accountRepository,
        syncManager, sharedAccessState, prefs, scheduledRepository, bulkEditRepository
    )

    private fun summary(
        ids: Int = 2,
        types: Set<String> = setOf("income"),
        owners: Set<Long> = setOf(ME),
        linked: List<Long> = emptyList(),
        linkedTypes: Set<String> = emptySet(),
        linkedOwners: Set<Long> = emptySet()
    ) = BulkEditSummary(
        requested = ids, liveCount = ids, types = types, owners = owners, commonCategoryId = 10,
        linkedScheduleIds = linked, linkedScheduleTypes = linkedTypes, linkedScheduleOwners = linkedOwners,
        currentUserId = ME
    )

    // ---- Exact match and "Added by me" reach the query ----

    @Test
    fun `exact match reaches the query with the search term`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setSearchQuery("Stipendio")
        advanceUntilIdle()
        vm.setExactMatch(true)
        advanceUntilIdle()

        verify {
            transactionRepository.getSorted(
                search = "Stipendio", exactMatch = true, limit = 101, offset = 0
            )
        }
    }

    @Test
    fun `exact match without a search term leaves the query alone`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setExactMatch(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.exactMatch)
        assertTrue("no exact clause without a term: $queries", queries.none { it.first })
    }

    @Test
    fun `added by me reaches the query with the current user id`() = runTest(dispatcher) {
        accounts.value = listOf(ownAccount, sharedAccount)
        val vm = viewModel()
        assertTrue(vm.uiState.value.hasSharedAccounts)

        vm.setOnlyMine(true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.onlyMine)
        assertEquals(ME, queries.last().second)
    }

    @Test
    fun `added by me is hidden and ignored without shared accounts`() = runTest(dispatcher) {
        val vm = viewModel()
        assertFalse(vm.uiState.value.hasSharedAccounts)

        vm.setOnlyMine(true)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.onlyMine)
        assertTrue(queries.none { it.second != null })
    }

    @Test
    fun `added by me resets when the last share goes away`() = runTest(dispatcher) {
        accounts.value = listOf(ownAccount, sharedAccount)
        val vm = viewModel()
        vm.setOnlyMine(true)
        advanceUntilIdle()
        assertEquals(ME, queries.last().second)

        accounts.value = listOf(ownAccount, sharedAccount.copy(isShared = false))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hasSharedAccounts)
        assertFalse("no hidden filter left on", vm.uiState.value.onlyMine)
        assertNull(queries.last().second)

        // Sharing comes back: the filter stays off until chosen again.
        accounts.value = listOf(ownAccount, sharedAccount)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.onlyMine)
        assertNull(queries.last().second)
    }

    // ---- Selection lifetime ----

    @Test
    fun `selection clears when filters, search, exact match or added by me change`() = runTest(dispatcher) {
        accounts.value = listOf(ownAccount, sharedAccount)
        val vm = viewModel()
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "date filter" to { vm.setFilters("2026-10-01", "2026-10-31", null) },
            "category filter" to { vm.setCategoryFilter(10) },
            "search" to { vm.setSearchQuery("Stipendio") },
            "exact match" to { vm.setExactMatch(true) },
            "added by me" to { vm.setOnlyMine(true) },
            "clear filters" to { vm.clearFilters() },
            "close search" to { vm.toggleSearch(); vm.toggleSearch() }
        )
        // toggleSearch twice: opening is not a change, closing drops the term.
        vm.toggleSearch()
        for ((label, action) in actions) {
            vm.toggleSelect(1)
            vm.toggleSelect(2)
            assertEquals(setOf(1L, 2L), vm.uiState.value.selectedIds)

            action()
            advanceUntilIdle()

            assertEquals("after $label", emptySet<Long>(), vm.uiState.value.selectedIds)
            assertFalse("after $label", vm.uiState.value.isSelectionMode)
        }
    }

    @Test
    fun `selection survives sort and paging`() = runTest(dispatcher) {
        stubRows(List(101) { txn(it + 1L) })
        val vm = viewModel()
        advanceUntilIdle()
        vm.toggleSelect(1)
        vm.toggleSelect(2)

        vm.setSort("amount", "asc")
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()

        assertEquals(setOf(1L, 2L), vm.uiState.value.selectedIds)
        verify { transactionRepository.getSorted(sortBy = "amount", sortDir = "asc", limit = 201, offset = 0) }
    }

    // ---- Select all matching ----

    @Test
    fun `select all matching uses the list filter and selects every match`() = runTest(dispatcher) {
        accounts.value = listOf(ownAccount, sharedAccount)
        val ids = (1L..250L).toList()
        coEvery { transactionRepository.getMatchingIds(any(), any()) } returns ids
        val vm = viewModel()
        vm.setFilters("2026-01-01", "2026-12-31", 10)
        vm.setSearchQuery(" Stipendio ")
        vm.setExactMatch(true)
        vm.setOnlyMine(true)
        advanceUntilIdle()

        vm.selectAllMatching()
        advanceUntilIdle()

        coVerify {
            transactionRepository.getMatchingIds(
                TransactionFilter(
                    from = "2026-01-01", to = "2026-12-31", categoryId = 10, search = " Stipendio ",
                    exactMatch = true, accountIds = null, onlyMine = true, currentUserId = ME
                ),
                5001
            )
        }
        val state = vm.uiState.value
        assertEquals(ids.toSet(), state.selectedIds)
        assertTrue(state.isSelectionMode)
        assertTrue(state.allMatchingSelected)
        assertEquals(250, state.matchingCount)
        assertFalse(state.isSelectingAllMatching)
    }

    @Test
    fun `select all matching discards a result that lands after the filter changed`() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<Long>>()
        coEvery { transactionRepository.getMatchingIds(any(), any()) } coAnswers { pending.await() }
        val vm = viewModel()
        vm.setSearchQuery("Stipendio")
        advanceUntilIdle()

        vm.selectAllMatching()
        assertTrue(vm.uiState.value.isSelectingAllMatching)
        vm.setSearchQuery("Stipendio Lisa")
        advanceUntilIdle()
        pending.complete(listOf(1L, 2L, 3L))
        advanceUntilIdle()

        assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)
        assertFalse(vm.uiState.value.allMatchingSelected)
        assertFalse(vm.uiState.value.isSelectingAllMatching)
    }

    @Test
    fun `select all matching discards a result that lands after the selection was cleared`() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<Long>>()
        coEvery { transactionRepository.getMatchingIds(any(), any()) } coAnswers { pending.await() }
        val vm = viewModel()
        vm.toggleSelect(1)

        vm.selectAllMatching()
        vm.clearSelection()
        pending.complete(listOf(1L, 2L, 3L))
        advanceUntilIdle()

        assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)
        assertFalse(vm.uiState.value.isSelectionMode)
    }

    @Test
    fun `select all matching refuses more than 5000`() = runTest(dispatcher) {
        coEvery { transactionRepository.getMatchingIds(any(), any()) } returns (1L..5001L).toList()
        val vm = viewModel()
        vm.toggleSelect(7)

        vm.selectAllMatching()
        advanceUntilIdle()

        assertEquals("selection untouched", setOf(7L), vm.uiState.value.selectedIds)
        assertFalse(vm.uiState.value.allMatchingSelected)
        assertEquals(MSG_TOO_MANY_MATCHES, vm.uiState.value.selectAllError)
    }

    @Test
    fun `exactly 5000 matches can be selected`() = runTest(dispatcher) {
        coEvery { transactionRepository.getMatchingIds(any(), any()) } returns (1L..5000L).toList()
        val vm = viewModel()

        vm.selectAllMatching()
        advanceUntilIdle()

        assertEquals(5000, vm.uiState.value.selectedIds.size)
        assertNull(vm.uiState.value.selectAllError)
    }

    @Test
    fun `select all counts the matches when more rows exist`() = runTest(dispatcher) {
        stubRows(List(101) { txn(it + 1L) })
        coEvery { transactionRepository.countMatching(any()) } returns 340
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()

        assertEquals(100, vm.uiState.value.selectedIds.size)
        assertEquals(340, vm.uiState.value.matchingCount)
    }

    // ---- Apply ----

    private suspend fun openWithRename(vm: TransactionsViewModel, ids: List<Long>, name: String) {
        coEvery { bulkEditRepository.summarize(any()) } returns summary(ids = ids.size)
        ids.forEach(vm::toggleSelect)
        vm.openBulkEdit()
        assertNotNull(vm.uiState.value.bulkEdit)
        vm.setBulkRenameEnabled(true)
        vm.setBulkDescription(name)
    }

    @Test
    fun `apply success clears the selection and reports the counts`() = runTest(dispatcher) {
        coEvery { bulkEditRepository.apply(any<BulkEditRequest>()) } returns
            BulkEditResult.Applied(BulkEditCounts(updated = 2, unchanged = 1, skipped = 0))
        val vm = viewModel()
        openWithRename(vm, listOf(1, 2, 3), "  Stipendio ")

        vm.applyBulkEdit()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            bulkEditRepository.apply(BulkEditRequest(ids = listOf(1, 2, 3), description = "Stipendio"))
        }
        val state = vm.uiState.value
        assertEquals(emptySet<Long>(), state.selectedIds)
        assertFalse(state.isSelectionMode)
        assertNull(state.bulkEdit)
        assertEquals("Updated 2 entries, 1 already up to date", state.bulkEditMessage)
        vm.clearBulkEditMessage()
        assertNull(vm.uiState.value.bulkEditMessage)
    }

    @Test
    fun `apply rejection keeps the selection and shows the error in the sheet`() = runTest(dispatcher) {
        coEvery { bulkEditRepository.apply(any<BulkEditRequest>()) } returns
            BulkEditResult.Rejected("category type does not match the selected entries")
        val vm = viewModel()
        openWithRename(vm, listOf(1, 2), "Stipendio")

        vm.applyBulkEdit()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(setOf(1L, 2L), state.selectedIds)
        assertTrue(state.isSelectionMode)
        val sheet = state.bulkEdit!!
        assertEquals("Category type does not match the selected entries", sheet.error)
        assertFalse(sheet.isApplying)
        assertNull(state.bulkEditMessage)
    }

    @Test
    fun `apply is not sent when nothing is opted in`() = runTest(dispatcher) {
        coEvery { bulkEditRepository.summarize(any()) } returns summary()
        val vm = viewModel()
        vm.toggleSelect(1)
        vm.openBulkEdit()

        vm.applyBulkEdit()
        advanceUntilIdle()

        coVerify(exactly = 0) { bulkEditRepository.apply(any<BulkEditRequest>()) }
    }

    @Test
    fun `a filter change closes the sheet with the selection`() = runTest(dispatcher) {
        val vm = viewModel()
        openWithRename(vm, listOf(1, 2), "Stipendio")

        vm.setCategoryFilter(11)
        advanceUntilIdle()

        assertNull(vm.uiState.value.bulkEdit)
        assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun `picking a category records its type for the schedule check`() = runTest(dispatcher) {
        coEvery { bulkEditRepository.summarize(any()) } returns summary()
        val vm = viewModel()
        vm.toggleSelect(1)
        vm.openBulkEdit()

        vm.setBulkCategoryEnabled(true)
        vm.setBulkCategory(11)

        val sheet = vm.uiState.value.bulkEdit!!
        assertEquals(11L, sheet.categoryId)
        assertEquals("income", sheet.categoryType)
        assertTrue(sheet.canApply)
        assertEquals(BulkEditRequest(ids = listOf(1), categoryId = 11), sheet.toRequest())
    }

    // ---- Selection races and failures ----

    @Test
    fun toggleDuringSelectAllMatchingDiscardsResult() = runTest(dispatcher) {
        val pending = CompletableDeferred<List<Long>>()
        coEvery { transactionRepository.getMatchingIds(any(), any()) } coAnswers { pending.await() }
        val vm = viewModel()
        vm.toggleSelect(1)
        vm.toggleSelect(2)

        vm.selectAllMatching()
        assertTrue(vm.uiState.value.isSelectingAllMatching)
        // The user unticks a row while the query runs.
        vm.toggleSelect(2)
        assertFalse(vm.uiState.value.isSelectingAllMatching)
        pending.complete(listOf(1L, 2L, 3L))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("the unticked row stays out", setOf(1L), state.selectedIds)
        assertFalse(state.allMatchingSelected)
        assertNull(state.matchingCount)
        assertFalse(state.isSelectingAllMatching)

        // So the next delete can't reach the excluded row.
        vm.deleteSelected()
        advanceUntilIdle()
        coVerify(exactly = 1) { transactionRepository.deleteMany(listOf(1L)) }
    }

    @Test
    fun selectAllDuringSelectAllMatchingDiscardsResult() = runTest(dispatcher) {
        stubRows(List(101) { txn(it + 1L) })
        val pending = CompletableDeferred<List<Long>>()
        coEvery { transactionRepository.getMatchingIds(any(), any()) } coAnswers { pending.await() }
        coEvery { transactionRepository.countMatching(any()) } returns 340
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectAllMatching()
        vm.selectAll()
        pending.complete((1L..340L).toList())
        advanceUntilIdle()

        assertEquals(100, vm.uiState.value.selectedIds.size)
        assertFalse(vm.uiState.value.allMatchingSelected)
        assertEquals(340, vm.uiState.value.matchingCount)
    }

    @Test
    fun selectAllMatchingFailureResetsState() = runTest(dispatcher) {
        coEvery { transactionRepository.getMatchingIds(any(), any()) } throws IllegalStateException("db closed")
        val vm = viewModel()
        vm.toggleSelect(7)

        vm.selectAllMatching()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isSelectingAllMatching)
        assertEquals(MSG_SELECT_ALL_FAILED, state.selectAllError)
        assertEquals("selection untouched", setOf(7L), state.selectedIds)
        assertFalse(state.allMatchingSelected)
    }

    @Test
    fun countFailureLeavesTheBannerWithoutANumber() = runTest(dispatcher) {
        stubRows(List(101) { txn(it + 1L) })
        coEvery { transactionRepository.countMatching(any()) } throws IllegalStateException("db closed")
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectAll()
        advanceUntilIdle()

        assertEquals(100, vm.uiState.value.selectedIds.size)
        assertNull(vm.uiState.value.matchingCount)
    }

    // ---- The selection is re-checked against the filter ----

    @Test
    fun openBulkEditDropsRowsNoLongerMatchingFilter() = runTest(dispatcher) {
        coEvery { transactionRepository.filterIds(any(), any()) } returns listOf(1L, 3L)
        coEvery { bulkEditRepository.summarize(any()) } returns summary(ids = 2)
        val vm = viewModel()
        listOf(1L, 2L, 3L).forEach(vm::toggleSelect)

        vm.openBulkEdit()
        advanceUntilIdle()

        coVerify(exactly = 1) { bulkEditRepository.summarize(listOf(1L, 3L)) }
        val state = vm.uiState.value
        assertEquals(setOf(1L, 3L), state.selectedIds)
        val sheet = state.bulkEdit!!
        assertEquals(listOf(1L, 3L), sheet.ids)
        assertEquals("1 selected entry no longer matches the current filter and was left out.".replaceFirstChar { it.uppercase() }, sheet.notice)
    }

    @Test
    fun openBulkEditWithNothingLeftOpensNoSheet() = runTest(dispatcher) {
        coEvery { transactionRepository.filterIds(any(), any()) } returns emptyList()
        val vm = viewModel()
        listOf(1L, 2L).forEach(vm::toggleSelect)

        vm.openBulkEdit()
        advanceUntilIdle()

        assertNull(vm.uiState.value.bulkEdit)
        assertFalse(vm.uiState.value.isSelectionMode)
        assertEquals(leftOutMessage(2).replaceFirstChar { it.uppercase() } + ".", vm.uiState.value.bulkEditMessage)
        coVerify(exactly = 0) { bulkEditRepository.summarize(any()) }
    }

    @Test
    fun applyDropsRowsNoLongerMatchingFilter() = runTest(dispatcher) {
        coEvery { bulkEditRepository.apply(any<BulkEditRequest>()) } returns
            BulkEditResult.Applied(BulkEditCounts(updated = 2, unchanged = 0, skipped = 0))
        val vm = viewModel()
        vm.setSearchQuery("Paga")
        advanceUntilIdle()
        openWithRename(vm, listOf(1, 2, 3), "Stipendio")
        // A sync renamed entry 2 while the sheet was open: it left the search.
        coEvery { transactionRepository.filterIds(any(), any()) } returns listOf(1L, 3L)

        vm.applyBulkEdit()
        advanceUntilIdle()

        coVerify { transactionRepository.filterIds(match { it.search == "Paga" }, listOf(1L, 2L, 3L)) }
        coVerify(exactly = 1) {
            bulkEditRepository.apply(BulkEditRequest(ids = listOf(1, 3), description = "Stipendio"))
        }
        assertEquals(
            "Updated 2 entries. 1 selected entry no longer matches the current filter and was left out",
            vm.uiState.value.bulkEditMessage
        )
        assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)
    }

    @Test
    fun applyWithEveryRowGoneEditsNothing() = runTest(dispatcher) {
        val vm = viewModel()
        openWithRename(vm, listOf(1, 2), "Stipendio")
        coEvery { transactionRepository.filterIds(any(), any()) } returns emptyList()

        vm.applyBulkEdit()
        advanceUntilIdle()

        coVerify(exactly = 0) { bulkEditRepository.apply(any<BulkEditRequest>()) }
        assertNull(vm.uiState.value.bulkEdit)
        assertEquals(leftOutMessage(2), vm.uiState.value.bulkEditMessage)
    }

    @Test
    fun deleteSelectedDropsRowsNoLongerMatchingFilter() = runTest(dispatcher) {
        coEvery { transactionRepository.filterIds(any(), any()) } returns listOf(1L, 2L)
        val vm = viewModel()
        listOf(1L, 2L, 3L).forEach(vm::toggleSelect)

        vm.deleteSelected()
        advanceUntilIdle()

        // One repository call for the rows that still match; 3 is never touched.
        coVerify(exactly = 1) { transactionRepository.deleteMany(listOf(1L, 2L)) }
        coVerify(exactly = 0) { transactionRepository.delete(any()) }
        assertEquals(emptySet<Long>(), vm.uiState.value.selectedIds)
        assertEquals(leftOutMessage(1), vm.uiState.value.bulkEditMessage)
    }

    @Test
    fun deleteSelectedFailureIsReported() = runTest(dispatcher) {
        coEvery { transactionRepository.deleteMany(any()) } throws IllegalStateException("disk full")
        val vm = viewModel()
        vm.toggleSelect(1)

        vm.deleteSelected()
        advanceUntilIdle()

        assertEquals(MSG_DELETE_FAILED, vm.uiState.value.bulkEditMessage)
    }

    @Test
    fun serverBlockDisablesApply() {
        val blocked = BulkEditState(
            ids = listOf(1), syncEnabled = true, renameEnabled = true, description = "Stipendio",
            summary = summary().copy(serverBlockedReason = "Bulk edit needs server 1.27 or newer. Update the server, then sync")
        )
        assertFalse(blocked.canApply)
        assertTrue(blocked.copy(summary = summary()).canApply)
    }

    // ---- BulkEditState: what gets sent ----

    @Test
    fun `only opted-in fields are sent and a blocked category never is`() {
        val base = BulkEditState(ids = listOf(1, 2), summary = summary(), syncEnabled = true)

        val renameOnly = base.copy(renameEnabled = true, description = " Stipendio ", categoryId = 11, categoryType = "income")
        assertEquals(BulkEditRequest(listOf(1, 2), description = "Stipendio"), renameOnly.toRequest())

        val categoryOnly = base.copy(description = "ignored", categoryEnabled = true, categoryId = 11, categoryType = "income")
        assertEquals(BulkEditRequest(listOf(1, 2), categoryId = 11), categoryOnly.toRequest())

        val mixed = BulkEditState(
            ids = listOf(1, 2), summary = summary(types = setOf("income", "expense")), syncEnabled = true,
            renameEnabled = true, description = "Stipendio", categoryEnabled = true, categoryId = 11, categoryType = "income"
        )
        assertEquals(BulkEditRequest(listOf(1, 2), description = "Stipendio"), mixed.toRequest())
        assertFalse("a category change can't be applied to a mixed selection", mixed.canApply)

        val schedulesWithoutLinks = categoryOnly.copy(updateSchedules = true)
        assertFalse(schedulesWithoutLinks.toRequest().updateSchedules)
        val withLinks = categoryOnly.copy(
            summary = summary(linked = listOf(70), linkedTypes = setOf("income"), linkedOwners = setOf(ME)),
            updateSchedules = true
        )
        assertTrue(withLinks.toRequest().updateSchedules)
        assertTrue(withLinks.canApply)
    }

    @Test
    fun `apply gating covers blank and too long names and schedule conflicts`() {
        val base = BulkEditState(ids = listOf(1), summary = summary(), syncEnabled = true)

        assertFalse("nothing opted in", base.canApply)
        assertFalse("blank name", base.copy(renameEnabled = true, description = "   ").canApply)
        val tooLong = base.copy(renameEnabled = true, description = "è".repeat(251))
        assertNotNull(tooLong.descriptionError)
        assertFalse(tooLong.canApply)
        assertTrue(base.copy(renameEnabled = true, description = "è".repeat(250)).canApply)
        assertFalse("no category picked", base.copy(categoryEnabled = true).canApply)
        assertFalse("while applying", base.copy(renameEnabled = true, description = "x", isApplying = true).canApply)

        val typeConflict = base.copy(
            summary = summary(linked = listOf(70), linkedTypes = setOf("expense"), linkedOwners = setOf(ME)),
            categoryEnabled = true, categoryId = 11, categoryType = "income", updateSchedules = true
        )
        assertNotNull(typeConflict.scheduleConflict)
        assertFalse(typeConflict.canApply)
        assertTrue("unticking the schedules clears the conflict", typeConflict.copy(updateSchedules = false).canApply)

        val ownerConflict = typeConflict.copy(
            summary = summary(linked = listOf(70), linkedTypes = setOf("income"), linkedOwners = setOf(99))
        )
        assertNotNull(ownerConflict.scheduleConflict)
        assertFalse(ownerConflict.canApply)

        // A rename never conflicts with the schedules.
        val renameWithSchedules = base.copy(
            summary = summary(linked = listOf(70), linkedTypes = setOf("expense"), linkedOwners = setOf(99)),
            renameEnabled = true, description = "Stipendio", updateSchedules = true
        )
        assertNull(renameWithSchedules.scheduleConflict)
        assertTrue(renameWithSchedules.canApply)
    }

    @Test
    fun `result message lists what happened`() {
        assertEquals("Updated 1 entry", bulkEditResultMessage(BulkEditCounts(1, 0, 0)))
        assertEquals(
            "Updated 3 entries, 2 already up to date, 1 skipped and 1 schedule",
            bulkEditResultMessage(BulkEditCounts(3, 2, 1, schedulesUpdated = 1))
        )
        assertEquals("Updated 0 entries, 4 already up to date", bulkEditResultMessage(BulkEditCounts(0, 4, 0)))
    }

    private companion object {
        const val ME = 1L
    }
}
