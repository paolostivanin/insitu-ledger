package com.insituledger.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.DashboardData
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DashboardUiState(
    val data: DashboardData? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val heroMode: String = "net_worth",
    val currentUserId: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val sharedAccessState: SharedAccessState,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _refreshTick = MutableStateFlow(0)

    private data class Scope(val accounts: List<Account>, val owner: Long?)
    private data class ScopedData(val scope: Scope, val recent: List<Transaction>)

    private val scopedData = combine(accountRepository.getAll(), sharedAccessState.ownerFilter) { accounts, owner ->
        Scope(if (owner == null) accounts else accounts.filter { it.userId == owner }, owner)
    }.flatMapLatest { scope ->
        val ids = if (scope.owner == null) null else scope.accounts.map { it.id }.toSet()
        transactionRepository.getRecent(10, ids).map { ScopedData(scope, it) }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        scopedData,
        prefs.userIdFlow,
        prefs.dashboardHeroModeFlow
    ) { data, currentUserId, heroMode ->
        val accounts = data.scope.accounts
        val filter = data.scope.owner
        val accountIds = accounts.map { it.id }.toSet()
        val recent = data.recent

        val now = LocalDate.now()
        val monthStart = now.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthEnd = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthTxns = transactionRepository.getFilteredSync(monthStart, monthEnd, null)
            .let { if (filter == null) it else it.filter { t -> t.accountId in accountIds } }
        val monthIncome = monthTxns.filter { it.type == "income" }.sumOf { it.amount }
        val monthExpense = monthTxns.filter { it.type == "expense" }.sumOf { it.amount }

        DashboardUiState(
            data = DashboardData(
                totalBalance = accounts.sumOf { it.balance },
                monthIncome = monthIncome,
                monthExpense = monthExpense,
                recentTransactions = recent,
                accounts = accounts
            ),
            isLoading = false,
            heroMode = heroMode,
            currentUserId = currentUserId
        )
    }
        .combine(_isRefreshing) { state, refreshing -> state.copy(isRefreshing = refreshing) }
        .combine(_refreshTick) { state, _ -> state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            syncManager.syncNow()
            _isRefreshing.value = false
            _refreshTick.update { it + 1 }
        }
    }
}
