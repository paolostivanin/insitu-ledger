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
import com.insituledger.app.ui.common.DashboardSkeleton
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.theme.LocalSemanticColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.insituledger.app.ui.common.CurrencyFormatter
import com.insituledger.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onTransactionClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val semanticColors = LocalSemanticColors.current
    val haptic = LocalHapticFeedback.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("InSitu Ledger") }) },
        floatingActionButton = {
            if (!uiState.isReadOnly) {
                FloatingActionButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAddClick()
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Transaction")
                }
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            DashboardSkeleton(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        val data = uiState.data ?: return@Scaffold

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            // Total balance card
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(AppSpacing.xl)) {
                        Text("Net Worth", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            text = formatCurrency(data.totalBalance, "EUR"),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Monthly summary
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    SummaryCard(
                        title = "Income",
                        amount = data.monthIncome,
                        color = semanticColors.income,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "Expenses",
                        amount = data.monthExpense,
                        color = semanticColors.expense,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Net monthly balance
            item {
                val net = data.monthIncome - data.monthExpense
                val netColor = if (net >= 0) semanticColors.income else semanticColors.expense
                val netPrefix = if (net >= 0) "+" else ""
                Card(modifier = Modifier.fillMaxWidth().animateItem()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Monthly Net", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "$netPrefix${formatCurrency(net, "EUR")}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = netColor
                        )
                    }
                }
            }

            // Accounts
            if (data.accounts.size > 1) {
                item {
                    SectionHeader(title = "Accounts", modifier = Modifier.animateItem())
                }
                items(data.accounts, key = { "account_${it.id}" }) { account ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth().animateItem()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(AppSpacing.lg),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                if (account.currency != "EUR") {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = account.currency,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
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
                    SectionHeader(title = "Recent Transactions", modifier = Modifier.animateItem())
                }
                items(data.recentTransactions, key = { "txn_${it.id}" }) { txn ->
                    TransactionItem(txn = txn, onClick = { onTransactionClick(txn.id) }, modifier = Modifier.animateItem())
                }
            }
        }
        }
    }
}

@Composable
private fun SummaryCard(title: String, amount: Double, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
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
private fun TransactionItem(txn: Transaction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
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
    return CurrencyFormatter.format(amount, currency)
}
