package com.insituledger.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.ScheduledTransaction
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
import com.insituledger.app.ui.common.NetPill
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.TransactionRow
import com.insituledger.app.ui.common.TransactionRowModel
import com.insituledger.app.ui.common.categoryColor
import com.insituledger.app.ui.common.categoryEmoji
import com.insituledger.app.ui.common.currencySymbolFor
import com.insituledger.app.ui.common.entryAttribution
import com.insituledger.app.ui.common.primaryCurrency
import com.insituledger.app.ui.common.seriesLabel
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.LocalSemanticColors
import com.insituledger.app.ui.theme.amountLarge
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val RECENT_ROWS = 8

/** Thin spend bar under the month figures. */
private val SpendBarHeight = 6.dp

/** Surface gap between the two bar segments so they never read as one. */
private val SpendBarGap = 2.dp

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
			val rows = remember(data.recentTransactions, data.accounts, uiState.categories, uiState.schedules, uiState.currentUserId, symbol) {
				buildRecentRows(
					data.recentTransactions.take(RECENT_ROWS),
					data.accounts,
					uiState.categories,
					uiState.schedules,
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
						showNetWorth = uiState.showNetWorth,
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

/**
 * Headline figure plus one supporting line. Net worth stays neutral (red
 * only when below zero); the month net is a net figure, so it is green above
 * zero and red below — as a tonal pill under net worth, or as the headline
 * itself in "month net" mode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BalanceHeader(
	heroMode: String,
	showNetWorth: Boolean,
	totalBalance: Double,
	monthNet: Double,
	horizontalPadding: Dp
) {
	val symbol = LocalCurrencySymbol.current
	val semantic = LocalSemanticColors.current
	val monthName = LocalDate.now().month.getDisplayName(TextStyle.FULL, Locale.getDefault())
	// With net worth hidden, the month's net is the only headline left.
	val isMonthMode = heroMode == "month_net" || !showNetWorth

	val headlineText: String
	val headlineColor: Color
	val headlineSpoken: String?
	if (isMonthMode) {
		headlineText = MoneyFormat.net(monthNet, symbol)
		headlineColor = when {
			monthNet > 0.004 -> semantic.positive
			monthNet < -0.004 -> semantic.negative
			else -> MaterialTheme.colorScheme.onSurface
		}
		headlineSpoken = MoneyFormat.spokenNet(monthNet, symbol)
	} else {
		headlineText = MoneyFormat.format(totalBalance, symbol)
		headlineColor = if (totalBalance < -0.004) semantic.negative else MaterialTheme.colorScheme.onSurface
		headlineSpoken = null
	}

	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = horizontalPadding, end = horizontalPadding, top = AppSpacing.sm, bottom = AppSpacing.lg)
			.semantics(mergeDescendants = true) {}
	) {
		Text(
			text = when {
				!showNetWorth -> "Net in $monthName"
				isMonthMode -> "Net this month"
				else -> "Net worth"
			},
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Spacer(Modifier.height(AppSpacing.xs))
		AutoSizeMoneyText(
			text = headlineText,
			style = MaterialTheme.typography.amountLarge,
			color = headlineColor,
			spoken = headlineSpoken,
			modifier = Modifier.fillMaxWidth()
		)
		Spacer(Modifier.height(AppSpacing.sm))
		// Wraps rather than clipping when the figure is long or text is large.
		if (!showNetWorth) {
			// Nothing below the headline: no net worth anywhere on the screen.
		} else if (isMonthMode) {
			FlowRow {
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
			}
		} else {
			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
				verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
			) {
				NetPill(
					value = monthNet,
					spokenLabel = "Net",
					modifier = Modifier.align(Alignment.CenterVertically)
				)
				Text(
					"in $monthName",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.align(Alignment.CenterVertically)
				)
			}
		}
	}
}

/**
 * Money in vs money out for the current month: income in green, expenses in
 * red, and a thin bar showing how much of the income has been spent.
 */
@Composable
private fun MonthSummary(income: Double, expense: Double, modifier: Modifier = Modifier) {
	val symbol = LocalCurrencySymbol.current
	val semantic = LocalSemanticColors.current
	val monthName = LocalDate.now().month
		.getDisplayName(TextStyle.FULL, Locale.getDefault())
		.replaceFirstChar { it.titlecase(Locale.getDefault()) }
	val share = spendShare(income, expense)

	Surface(
		color = MaterialTheme.colorScheme.surfaceContainer,
		shape = MaterialTheme.shapes.medium,
		modifier = modifier.fillMaxWidth()
	) {
		Column(Modifier.padding(AppSpacing.lg)) {
			Text(
				monthName,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.semantics { heading() }
			)
			Spacer(Modifier.height(AppSpacing.md))
			// Side by side normally; stacked when the effective width (dp ÷ font
			// scale) is too small for two figures, e.g. 320dp at 2× text.
			val config = LocalConfiguration.current
			val incomeText = MoneyFormat.format(income, symbol, MoneySign.NONE)
			val expenseText = MoneyFormat.format(expense, symbol, MoneySign.NONE)
			if (config.screenWidthDp / config.fontScale < 260f) {
				Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
					SummaryFigure("Income", incomeText, semantic.positive, Modifier.fillMaxWidth())
					SummaryFigure("Expenses", expenseText, semantic.negative, Modifier.fillMaxWidth())
				}
			} else {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg)
				) {
					SummaryFigure("Income", incomeText, semantic.positive, Modifier.weight(1f))
					SummaryFigure("Expenses", expenseText, semantic.negative, Modifier.weight(1f))
				}
			}
			SpendCaption(share, symbol)
		}
	}
}

@Composable
private fun SummaryFigure(label: String, text: String, color: Color, modifier: Modifier) {
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
			// One step above list amounts so the month block reads as the
			// second level under the headline.
			style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
			color = color,
			steps = listOf(20.sp, 18.sp, 16.sp, 14.sp, 12.sp)
		)
	}
}

/**
 * Bar + one-line reading of [share]. Nothing at all for an empty month, text
 * only while there is no income to compare against.
 */
@Composable
private fun SpendCaption(share: SpendShare, symbol: String) {
	val semantic = LocalSemanticColors.current
	val muted = MaterialTheme.colorScheme.onSurfaceVariant
	val (ratio, caption, captionColor) = when (share) {
		SpendShare.Empty -> return
		SpendShare.NoIncome -> Triple(null, "No income yet this month", muted)
		is SpendShare.Within -> Triple(
			share.ratio,
			if (share.ratio <= 0f) "Nothing spent yet" else "Spent ${spendPercent(share.ratio)}% of income",
			muted
		)
		is SpendShare.Over -> Triple(
			1f,
			"Spent ${MoneyFormat.format(share.excess, symbol, MoneySign.NONE)} more than you earned",
			semantic.negative
		)
	}
	if (ratio != null) {
		Spacer(Modifier.height(AppSpacing.lg))
		SpendBar(ratio)
		Spacer(Modifier.height(AppSpacing.sm))
	} else {
		Spacer(Modifier.height(AppSpacing.md))
	}
	Text(caption, style = MaterialTheme.typography.bodySmall, color = captionColor)
}

/**
 * The month's income as one bar: the part still unspent in the positive
 * colour (under "Income"), the part spent in the negative colour (under
 * "Expenses"). Decorative for screen readers — the caption carries the value.
 */
@Composable
private fun SpendBar(spentRatio: Float, modifier: Modifier = Modifier) {
	val semantic = LocalSemanticColors.current
	// Keep a sliver of each part visible when both exist (e.g. 0.4% spent).
	val spent = when {
		spentRatio <= 0f -> 0f
		spentRatio >= 1f -> 1f
		else -> spentRatio.coerceIn(0.02f, 0.98f)
	}
	Row(
		modifier
			.fillMaxWidth()
			.height(SpendBarHeight)
			.clip(CircleShape)
	) {
		if (spent < 1f) {
			Box(Modifier.weight(1f - spent).fillMaxHeight().background(semantic.positive))
		}
		if (spent > 0f && spent < 1f) {
			Spacer(Modifier.width(SpendBarGap))
		}
		if (spent > 0f) {
			Box(Modifier.weight(spent).fillMaxHeight().background(semantic.negative))
		}
	}
}

/** How this month's spending relates to its income. */
internal sealed interface SpendShare {
	/** No income and no spending recorded. */
	data object Empty : SpendShare

	/** Spending but no income yet: a ratio would be meaningless. */
	data object NoIncome : SpendShare

	/** Spent [ratio] (0..1) of income. */
	data class Within(val ratio: Float) : SpendShare

	/** Spent [excess] more than was earned. */
	data class Over(val excess: Double) : SpendShare
}

internal fun spendShare(income: Double, expense: Double): SpendShare {
	val earned = income.coerceAtLeast(0.0)
	val spent = expense.coerceAtLeast(0.0)
	return when {
		earned < 0.005 && spent < 0.005 -> SpendShare.Empty
		earned < 0.005 -> SpendShare.NoIncome
		spent - earned > 0.004 -> SpendShare.Over(spent - earned)
		else -> SpendShare.Within((spent / earned).toFloat().coerceIn(0f, 1f))
	}
}

/** Whole percent that never claims 0% or 100% for a part-way ratio. */
internal fun spendPercent(ratio: Float): Int = when {
	ratio <= 0f -> 0
	ratio >= 1f -> 100
	else -> (ratio * 100).roundToInt().coerceIn(1, 99)
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
	schedules: Map<Long, ScheduledTransaction>,
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
			categoryIcon = categoryEmoji(categories, txn.categoryId),
			categoryName = categoryName,
			attribution = entryAttribution(account?.isShared == true, txn.createdByUserId, txn.createdByName, currentUserId),
			amount = txn.amount,
			type = txn.type,
			symbol = currencySymbolFor(txn.currency, preferredSymbol, primary),
			seriesLabel = seriesLabel(txn, schedules)
		)
	}
}
