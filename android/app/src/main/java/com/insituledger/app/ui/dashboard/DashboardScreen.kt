package com.insituledger.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AddFab
import com.insituledger.app.ui.common.AutoSizeMoneyText
import com.insituledger.app.ui.common.BalanceText
import com.insituledger.app.ui.common.DateLabels
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.MoneyFormat
import com.insituledger.app.ui.common.MoneySign
import com.insituledger.app.ui.common.MoneyText
import com.insituledger.app.ui.common.NetText
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.TransactionRow
import com.insituledger.app.ui.common.TransactionRowModel
import com.insituledger.app.ui.common.categoryColor
import com.insituledger.app.ui.common.currencySymbolFor
import com.insituledger.app.ui.common.entryAttribution
import com.insituledger.app.ui.common.primaryCurrency
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.LocalSemanticColors
import com.insituledger.app.ui.theme.amountLarge
import com.insituledger.app.ui.theme.amountMedium
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val RECENT_ROWS = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
	onTransactionClick: (Long) -> Unit,
	onAddClick: () -> Unit,
	onSeeAllTransactions: () -> Unit,
	onManageAccounts: () -> Unit,
	viewModel: DashboardViewModel = hiltViewModel()
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
	val pad = LocalContentPadding.current

	Scaffold(
		modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
		topBar = {
			TopAppBar(title = { Text("Overview") }, scrollBehavior = scrollBehavior)
		},
		floatingActionButton = {
			AddFab(onClick = onAddClick, contentDescription = "Add transaction")
		}
	) { padding ->
		val data = uiState.data
		if (uiState.isLoading || data == null) {
			LoadingIndicator(modifier = Modifier.padding(padding))
			return@Scaffold
		}

		PullToRefreshBox(
			isRefreshing = uiState.isRefreshing,
			onRefresh = { viewModel.refresh() },
			modifier = Modifier.fillMaxSize().padding(padding)
		) {
			val symbol = LocalCurrencySymbol.current
			val primary = remember(data.accounts) { primaryCurrency(data.accounts) }
			val rows = remember(data.recentTransactions, data.accounts, uiState.categories, uiState.currentUserId, symbol) {
				buildRecentRows(
					data.recentTransactions.take(RECENT_ROWS),
					data.accounts,
					uiState.categories,
					uiState.currentUserId,
					symbol,
					primary
				)
			}

			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(bottom = AppSpacing.fabClearance)
			) {
				item(key = "balance") {
					BalanceHeader(
						heroMode = uiState.heroMode,
						totalBalance = data.totalBalance,
						monthNet = data.monthIncome - data.monthExpense,
						horizontalPadding = pad
					)
				}
				item(key = "month") {
					MonthSummary(
						income = data.monthIncome,
						expense = data.monthExpense,
						modifier = Modifier.padding(horizontal = pad)
					)
				}

				if (data.accounts.size > 1) {
					item(key = "accounts-header") {
						SectionHeader(
							title = "Accounts",
							horizontalPadding = pad,
							actionLabel = "Manage",
							onAction = onManageAccounts
						)
					}
					items(data.accounts, key = { "account_${it.id}" }) { account ->
						AccountSummaryRow(
							account = account,
							symbol = currencySymbolFor(account.currency, symbol, primary),
							currentUserId = uiState.currentUserId,
							horizontalPadding = pad
						)
					}
				}

				item(key = "recent-header") {
					SectionHeader(
						title = "Recent",
						horizontalPadding = pad,
						actionLabel = if (rows.isNotEmpty()) "See all" else null,
						onAction = onSeeAllTransactions
					)
				}

				if (rows.isEmpty()) {
					item(key = "recent-empty") {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = pad, vertical = AppSpacing.lg),
							verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
						) {
							Text(
								"No transactions yet. Add your first expense or income to see it here.",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
							FilledTonalButton(onClick = onAddClick) { Text("Add transaction") }
						}
					}
				} else {
					items(rows, key = { "txn_${it.id}" }) { row ->
						TransactionRow(
							model = row,
							onClick = { onTransactionClick(row.id) },
							horizontalPadding = pad
						)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceHeader(
	heroMode: String,
	totalBalance: Double,
	monthNet: Double,
	horizontalPadding: Dp
) {
	val symbol = LocalCurrencySymbol.current
	val monthName = LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())
	val isMonthMode = heroMode == "month_net"
	val headline = if (isMonthMode) monthNet else totalBalance
	val headlineText = if (isMonthMode) MoneyFormat.net(headline, symbol) else MoneyFormat.format(headline, symbol)
	val headlineColor = if (headline < -0.004) LocalSemanticColors.current.negative
	else MaterialTheme.colorScheme.onSurface

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = horizontalPadding, end = horizontalPadding, top = AppSpacing.sm, bottom = AppSpacing.lg)
			.semantics(mergeDescendants = true) {}
	) {
		Text(
			text = if (isMonthMode) "Net this month" else "Net worth",
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Spacer(Modifier.height(AppSpacing.xs))
		AutoSizeMoneyText(
			text = headlineText,
			style = MaterialTheme.typography.amountLarge,
			color = headlineColor,
			modifier = Modifier.fillMaxWidth()
		)
		Spacer(Modifier.height(AppSpacing.xs))
		// Wraps rather than clipping when the figure is long or text is large.
		FlowRow(verticalArrangement = Arrangement.Center) {
			if (isMonthMode) {
				Text(
					"Net worth ",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				BalanceText(
					value = totalBalance,
					style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			} else {
				NetText(value = monthNet, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
				Text(
					" in $monthName",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun MonthSummary(income: Double, expense: Double, modifier: Modifier = Modifier) {
	val symbol = LocalCurrencySymbol.current
	val monthName = LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())
	Surface(
		color = MaterialTheme.colorScheme.surfaceContainer,
		shape = MaterialTheme.shapes.medium,
		modifier = modifier.fillMaxWidth()
	) {
		Column(Modifier.padding(AppSpacing.lg)) {
			Text(
				monthName,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			Spacer(Modifier.height(AppSpacing.md))
			// Side by side normally; stacked when the effective width (dp ÷ font
			// scale) is too small for two figures, e.g. 320dp at 2× text.
			val config = LocalConfiguration.current
			val incomeText = MoneyFormat.format(income, symbol, MoneySign.NONE)
			val expenseText = MoneyFormat.format(expense, symbol, MoneySign.NONE)
			if (config.screenWidthDp / config.fontScale < 260f) {
				Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
					SummaryFigure("Income", incomeText, LocalSemanticColors.current.positive, Modifier.fillMaxWidth())
					SummaryFigure("Expenses", expenseText, MaterialTheme.colorScheme.onSurface, Modifier.fillMaxWidth())
				}
			} else {
				Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
					SummaryFigure("Income", incomeText, LocalSemanticColors.current.positive, Modifier.weight(1f))
					VerticalDivider(
						color = MaterialTheme.colorScheme.outlineVariant,
						modifier = Modifier.fillMaxHeight().padding(horizontal = AppSpacing.md)
					)
					SummaryFigure("Expenses", expenseText, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
				}
			}
		}
	}
}

@Composable
private fun SummaryFigure(label: String, text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
	Column(modifier.semantics(mergeDescendants = true) {}) {
		Text(
			label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		Spacer(Modifier.height(2.dp))
		AutoSizeMoneyText(
			text = text,
			style = MaterialTheme.typography.amountMedium,
			color = color,
			steps = listOf(16, 14, 12).map { androidx.compose.ui.unit.TextUnit(it.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp) }
		)
	}
}

@Composable
private fun AccountSummaryRow(
	account: Account,
	symbol: String,
	currentUserId: Long?,
	horizontalPadding: Dp
) {
	val shared = when {
		account.isShared && account.userId != currentUserId && account.ownerName.isNotEmpty() -> "Shared by ${account.ownerName}"
		account.isShared -> "Shared"
		else -> null
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = AppSpacing.rowMinHeight)
			.semantics(mergeDescendants = true) {}
			.padding(horizontal = horizontalPadding, vertical = AppSpacing.sm),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f)) {
			Text(
				account.name,
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				listOfNotNull(account.currency, shared).joinToString(" · "),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
		Spacer(Modifier.width(AppSpacing.md))
		BalanceText(value = account.balance, symbol = symbol)
	}
}

private fun buildRecentRows(
	transactions: List<Transaction>,
	accounts: List<Account>,
	categories: List<Category>,
	currentUserId: Long?,
	preferredSymbol: String,
	primary: String?
): List<TransactionRowModel> {
	val accountMap = accounts.associateBy { it.id }
	val categoryMap = categories.associateBy { it.id }
	val showAccount = accounts.size > 1
	val today = LocalDate.now()
	return transactions.map { txn ->
		val account = accountMap[txn.accountId]
		val categoryName = categoryMap[txn.categoryId]?.name
		val day = DateLabels.parseDay(txn.date)?.let { DateLabels.day(it, today) }
		val supporting = listOfNotNull(day, categoryName, account?.name?.takeIf { showAccount }).joinToString(" · ")
		TransactionRowModel(
			id = txn.id,
			title = txn.description?.takeIf { it.isNotBlank() } ?: categoryName ?: txn.type.replaceFirstChar { it.uppercase() },
			supporting = supporting,
			categoryColor = categoryColor(categories, txn.categoryId),
			attribution = entryAttribution(account?.isShared == true, txn.createdByUserId, txn.createdByName, currentUserId),
			amount = txn.amount,
			type = txn.type,
			symbol = currencySymbolFor(txn.currency, preferredSymbol, primary)
		)
	}
}
