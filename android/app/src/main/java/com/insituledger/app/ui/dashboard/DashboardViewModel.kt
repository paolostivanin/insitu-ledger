package com.insituledger.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.TransactionRepository
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.DashboardData
import com.insituledger.app.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DashboardUiState(
    val data: DashboardData? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init { loadData() }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                accountRepository.getAll(),
                transactionRepository.getRecent(10)
            ) { accounts, recentTxns ->
                Pair(accounts, recentTxns)
            }.collect { (accounts, recentTxns) ->
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
                        isLoading = false
                    )
                }
            }
        }
    }
}
