package com.insituledger.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.data.sync.SyncManager
import com.insituledger.app.domain.model.DashboardData
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

    val uiState: StateFlow<DashboardUiState> = combine(
        accountRepository.getAll(),
        transactionRepository.getRecent(10),
        sharedAccessState.ownerFilter,
        prefs.userIdFlow,
        prefs.dashboardHeroModeFlow
    ) { allAccounts, allRecent, filter, currentUserId, heroMode ->
        val accounts = if (filter == null) allAccounts else allAccounts.filter { it.userId == filter }
        val accountIds = accounts.map { it.id }.toSet()
        val recent = if (filter == null) allRecent else allRecent.filter { it.accountId in accountIds }

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
