package com.insituledger.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.DashboardData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DashboardUiState(
    val data: DashboardData? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isReadOnly: Boolean = false,
    val heroMode: String = "net_worth"
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val sharedAccessState: SharedAccessState,
    private val prefs: UserPreferences
) : ViewModel() {

    private val _refreshTrigger = MutableStateFlow(0)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadData()
        viewModelScope.launch {
            prefs.dashboardHeroModeFlow.collect { mode ->
                _uiState.update { it.copy(heroMode = mode) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val owner = sharedAccessState.selectedOwner.value
            if (owner != null) {
                loadFromServer(owner.ownerId, owner.accounts.none { it.permission == "write" })
            } else {
                // Trigger re-emission from the local flow
                _refreshTrigger.update { it + 1 }
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun loadData() {
        viewModelScope.launch {
            sharedAccessState.selectedOwner
                .flatMapLatest { owner ->
                    if (owner != null) {
                        flow {
                            _uiState.update { it.copy(isLoading = true) }
                            loadFromServer(owner.ownerId, owner.accounts.none { it.permission == "write" })
                            emit(Unit)
                        }
                    } else {
                        _refreshTrigger.flatMapLatest {
                            _uiState.update { it.copy(isLoading = true) }
                            combine(
                                accountRepository.getAll(),
                                transactionRepository.getRecent(10)
                            ) { accounts, recentTxns ->
                                Pair(accounts, recentTxns)
                            }.map { (accounts, recentTxns) ->
                                val now = LocalDate.now()
                                val monthStart = now.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                                val monthEnd = now.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                val summary = transactionRepository.getMonthlySummary(monthStart, monthEnd)

                                val totalBalance = accounts.sumOf { it.balance }
                                _uiState.update {
                                    it.copy(
                                        data = DashboardData(
                                            totalBalance = totalBalance,
                                            monthIncome = summary.income,
                                            monthExpense = summary.expense,
                                            recentTransactions = recentTxns,
                                            accounts = accounts
                                        ),
                                        isLoading = false,
                                        isReadOnly = false
                                    )
                                }
                            }
                        }
                    }
                }
                .collect()
        }
    }

    private suspend fun loadFromServer(ownerId: Long, readOnly: Boolean) {
        val now = LocalDate.now()
        val monthStart = now.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val monthEnd = now.format(DateTimeFormatter.ISO_LOCAL_DATE)

        coroutineScope {
            val accountsDeferred = async { accountRepository.listFromServer(ownerId) }
            val recentDeferred = async {
                transactionRepository.listFromServer(
                    ownerId = ownerId, sortBy = "date", sortDir = "desc", limit = 10
                )
            }
            val monthDeferred = async {
                transactionRepository.listFromServer(
                    ownerId = ownerId, from = monthStart, to = monthEnd, limit = 500
                )
            }

            val accounts = accountsDeferred.await()
            val recentTxns = recentDeferred.await()
            val monthTxns = monthDeferred.await()

            val totalBalance = accounts.sumOf { it.balance }
            val monthIncome = monthTxns.filter { it.type == "income" }.sumOf { it.amount }
            val monthExpense = monthTxns.filter { it.type == "expense" }.sumOf { it.amount }

            _uiState.update {
                it.copy(
                    data = DashboardData(totalBalance, monthIncome, monthExpense, recentTxns, accounts),
                    isLoading = false,
                    isRefreshing = false,
                    isReadOnly = readOnly
                )
            }
        }
    }
}
