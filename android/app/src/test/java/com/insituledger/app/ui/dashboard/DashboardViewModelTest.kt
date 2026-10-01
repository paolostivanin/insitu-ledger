package com.insituledger.app.ui.dashboard

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Transaction
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun accountsAreCollectedOnceAndOwnerChangesKeepAccountsAndRowsConsistent() = runTest(dispatcher) {
        val accounts: AccountRepository = mockk()
        val transactions: TransactionRepository = mockk()
        val prefs: UserPreferences = mockk()
        val shared = SharedAccessState()
        val mine = Account(1, 1, "Mine", "EUR", 10.0)
        val theirs = Account(2, 2, "Theirs", "EUR", 20.0)
        val oldRow = Transaction(1, 1, 1, 1, "expense", 1.0, "EUR", "Old", null, "2026-01-01")
        val newRow = oldRow.copy(id = 2, accountId = 2, userId = 2, description = "New")
        var subscriptions = 0
        every { accounts.getAll() } returns flow { subscriptions++; emit(listOf(mine, theirs)) }
        every { transactions.getRecent(10, null) } returns flowOf(listOf(oldRow))
        val currentRows = MutableSharedFlow<List<Transaction>>()
        every { transactions.getRecent(10, setOf(2)) } returns currentRows
        coEvery { transactions.getFilteredSync(any(), any(), null) } returns emptyList()
        every { prefs.userIdFlow } returns flowOf(1L)
        every { prefs.dashboardHeroModeFlow } returns flowOf("net_worth")
        val vm = DashboardViewModel(accounts, transactions, shared, mockk(relaxed = true), prefs)
        backgroundScope.launch(dispatcher) { vm.uiState.collect() }
        assertEquals(1, subscriptions)
        shared.setOwnerFilter(2)
        // Until the new query emits, keep the previous complete pair.
        assertEquals(listOf(mine, theirs), vm.uiState.value.data!!.accounts)
        assertEquals(listOf(oldRow), vm.uiState.value.data!!.recentTransactions)
        currentRows.emit(listOf(newRow))
        assertEquals(listOf(theirs), vm.uiState.value.data!!.accounts)
        assertEquals(listOf(newRow), vm.uiState.value.data!!.recentTransactions)
        assertEquals(1, subscriptions)
        verify(exactly = 1) { accounts.getAll() }
    }
}
