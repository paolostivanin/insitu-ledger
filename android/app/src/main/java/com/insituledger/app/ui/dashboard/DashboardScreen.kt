package com.insituledger.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.LoadingIndicator
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onTransactionClick: (Long) -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("InSitu Ledger") }) }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        val data = uiState.data ?: return@Scaffold

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Total balance card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Total Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            text = formatCurrency(data.totalBalance, "EUR"),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Monthly summary
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryCard(
                        title = "Income",
                        amount = data.monthIncome,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "Expenses",
                        amount = data.monthExpense,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Accounts
            if (data.accounts.isNotEmpty()) {
                item {
                    Text("Accounts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                items(data.accounts, key = { it.id }) { account ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(account.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = formatCurrency(account.balance, account.currency),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Recent transactions
            if (data.recentTransactions.isNotEmpty()) {
                item {
                    Text("Recent Transactions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
                items(data.recentTransactions, key = { it.id }) { txn ->
                    TransactionItem(txn = txn, onClick = { onTransactionClick(txn.id) })
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, amount: Double, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatCurrency(amount, "EUR"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
private fun TransactionItem(txn: Transaction, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = txn.description ?: txn.type.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(txn.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AmountText(amount = txn.amount, type = txn.type, currency = txn.currency)
        }
    }
}

private fun formatCurrency(amount: Double, currency: String): String {
    return try {
        val fmt = NumberFormat.getCurrencyInstance(Locale.getDefault())
        fmt.currency = Currency.getInstance(currency)
        fmt.format(amount)
    } catch (_: Exception) {
        "$currency %.2f".format(amount)
    }
}
