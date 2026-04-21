package com.insituledger.app.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.CurrencyFormatter
import com.insituledger.app.ui.common.DashboardSkeleton
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.BrandGradients
import com.insituledger.app.ui.theme.LocalSemanticColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
	onTransactionClick: (Long) -> Unit,
	onAddClick: () -> Unit,
	viewModel: DashboardViewModel = hiltViewModel()
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val haptic = LocalHapticFeedback.current

	Scaffold(
		topBar = {
			CenterAlignedTopAppBar(
				title = {
					Text(
						"InSitu Ledger",
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.SemiBold
					)
				},
				colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
					containerColor = MaterialTheme.colorScheme.surface
				)
			)
		},
		floatingActionButton = {
			AnimatedVisibility(
				visible = !uiState.isReadOnly,
				enter = scaleIn() + fadeIn(),
				exit = fadeOut()
			) {
				BrandFab(
					onClick = {
						haptic.performHapticFeedback(HapticFeedbackType.LongPress)
						onAddClick()
					}
				)
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
				contentPadding = PaddingValues(
					start = AppSpacing.lg,
					end = AppSpacing.lg,
					top = AppSpacing.md,
					bottom = 96.dp
				),
				verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
			) {
				item {
					HeroNetWorthCard(
						balance = data.totalBalance,
						monthNet = data.monthIncome - data.monthExpense,
						mode = uiState.heroMode,
						modifier = Modifier.animateItem()
					)
				}

				item {
					Row(
						modifier = Modifier.fillMaxWidth().animateItem(),
						horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
					) {
						FlowSummaryCard(
							label = "Income",
							amount = data.monthIncome,
							isIncome = true,
							modifier = Modifier.weight(1f)
						)
						FlowSummaryCard(
							label = "Expenses",
							amount = data.monthExpense,
							isIncome = false,
							modifier = Modifier.weight(1f)
						)
					}
				}

				if (data.accounts.size > 1) {
					item {
						SectionHeader(
							title = "Accounts",
							modifier = Modifier
								.padding(top = AppSpacing.sm)
								.animateItem()
						)
					}
					items(data.accounts, key = { "account_${it.id}" }) { account ->
						AccountRow(
							name = account.name,
							balance = account.balance,
							currency = account.currency,
							modifier = Modifier.animateItem()
						)
					}
				}

				item {
					SectionHeader(
						title = "Recent Transactions",
						modifier = Modifier
							.padding(top = AppSpacing.sm)
							.animateItem()
					)
				}

				if (data.recentTransactions.isEmpty()) {
					item {
						Box(
							modifier = Modifier
								.fillParentMaxHeight(0.5f)
								.fillMaxWidth()
								.animateItem()
						) {
							EmptyState(
								icon = Icons.AutoMirrored.Filled.ReceiptLong,
								title = "No transactions yet",
								message = "Tap the + button to record your first transaction.",
								actionLabel = if (!uiState.isReadOnly) "Add Transaction" else null,
								onAction = if (!uiState.isReadOnly) onAddClick else null
							)
						}
					}
				} else {
					items(data.recentTransactions, key = { "txn_${it.id}" }) { txn ->
						TransactionRow(
							txn = txn,
							onClick = { onTransactionClick(txn.id) },
							modifier = Modifier.animateItem()
						)
					}
				}
			}
		}
	}
}

@Composable
private fun HeroNetWorthCard(
	balance: Double,
	monthNet: Double,
	mode: String,
	modifier: Modifier = Modifier
) {
	val gradient = BrandGradients.hero()
	val symbol = LocalCurrencySymbol.current
	val netPrefix = if (monthNet >= 0) "+" else ""
	val isMonthMode = mode == "month_net"
	val headlineLabel = if (isMonthMode) "THIS MONTH" else "NET WORTH"
	val headlineValue = if (isMonthMode) {
		"$netPrefix${CurrencyFormatter.formatWithSymbol(monthNet, symbol)}"
	} else {
		CurrencyFormatter.formatWithSymbol(balance, symbol)
	}
	Surface(
		modifier = modifier
			.fillMaxWidth()
			.shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp), clip = false),
		shape = RoundedCornerShape(24.dp),
		color = Color.Transparent
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(24.dp))
				.background(gradient)
				.padding(horizontal = AppSpacing.xl, vertical = AppSpacing.xl)
		) {
			Column {
				Text(
					text = headlineLabel,
					style = MaterialTheme.typography.labelMedium,
					color = Color.White.copy(alpha = 0.78f),
					fontWeight = FontWeight.SemiBold
				)
				Spacer(modifier = Modifier.height(AppSpacing.xs))
				Text(
					text = headlineValue,
					style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
					color = Color.White,
					fontWeight = FontWeight.Bold
				)
				if (!isMonthMode) {
					Spacer(modifier = Modifier.height(AppSpacing.md))
					Row(verticalAlignment = Alignment.CenterVertically) {
						Surface(
							shape = RoundedCornerShape(50),
							color = Color.White.copy(alpha = 0.18f)
						) {
							Row(
								modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 4.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								Icon(
									imageVector = if (monthNet >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
									contentDescription = null,
									tint = Color.White,
									modifier = Modifier.size(14.dp)
								)
								Text(
									text = "$netPrefix${CurrencyFormatter.formatWithSymbol(monthNet, symbol)}",
									style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
									color = Color.White,
									fontWeight = FontWeight.SemiBold
								)
							}
						}
						Spacer(modifier = Modifier.width(AppSpacing.sm))
						Text(
							text = "this month",
							style = MaterialTheme.typography.labelMedium,
							color = Color.White.copy(alpha = 0.7f)
						)
					}
				}
			}
		}
	}
}

@Composable
private fun FlowSummaryCard(
	label: String,
	amount: Double,
	isIncome: Boolean,
	modifier: Modifier = Modifier
) {
	val semantic = LocalSemanticColors.current
	val symbol = LocalCurrencySymbol.current
	val accent = if (isIncome) semantic.income else semantic.expense
	val container = if (isIncome) semantic.incomeContainer else semantic.expenseContainer
	val icon = if (isIncome) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(20.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		tonalElevation = 0.dp,
		shadowElevation = 1.dp
	) {
		Column(modifier = Modifier.padding(AppSpacing.lg)) {
			Box(
				modifier = Modifier
					.size(36.dp)
					.clip(CircleShape)
					.background(container),
				contentAlignment = Alignment.Center
			) {
				Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
			}
			Spacer(modifier = Modifier.height(AppSpacing.md))
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			Spacer(modifier = Modifier.height(AppSpacing.xxs))
			Text(
				text = CurrencyFormatter.formatWithSymbol(amount, symbol),
				style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
				color = accent,
				fontWeight = FontWeight.Bold
			)
		}
	}
}

@Composable
private fun AccountRow(
	name: String,
	balance: Double,
	currency: String,
	modifier: Modifier = Modifier
) {
	val symbol = LocalCurrencySymbol.current
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		shadowElevation = 1.dp
	) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
			verticalAlignment = Alignment.CenterVertically
		) {
			AccountAvatar(name = name)
			Spacer(modifier = Modifier.width(AppSpacing.md))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = name,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface
				)
				Text(
					text = currency,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			Text(
				text = CurrencyFormatter.formatWithSymbol(balance, symbol),
				style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface
			)
		}
	}
}

@Composable
private fun TransactionRow(
	txn: Transaction,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val semantic = LocalSemanticColors.current
	val isIncome = txn.type == "income"
	val accent = if (isIncome) semantic.income else semantic.expense
	val container = if (isIncome) semantic.incomeContainer else semantic.expenseContainer
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerLow,
		shadowElevation = 1.dp,
		onClick = onClick
	) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
			verticalAlignment = Alignment.CenterVertically
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.clip(CircleShape)
					.background(container),
				contentAlignment = Alignment.Center
			) {
				Icon(
					imageVector = if (isIncome) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
					contentDescription = null,
					tint = accent,
					modifier = Modifier.size(20.dp)
				)
			}
			Spacer(modifier = Modifier.width(AppSpacing.md))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = txn.description?.takeIf { it.isNotBlank() }
						?: txn.type.replaceFirstChar { it.uppercase() },
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1
				)
				Text(
					text = formatDateLabel(txn.date),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			AmountText(
				amount = txn.amount,
				type = txn.type,
				style = MaterialTheme.typography.titleMedium
			)
		}
	}
}

@Composable
private fun AccountAvatar(name: String) {
	val initials = name.trim().take(1).uppercase().ifEmpty { "•" }
	val palette = listOf(
		Color(0xFF26A69A),
		Color(0xFF42A5F5),
		Color(0xFFAB47BC),
		Color(0xFFFFA726),
		Color(0xFFEF5350),
		Color(0xFF66BB6A)
	)
	val color = palette[(name.hashCode().mod(palette.size) + palette.size) % palette.size]
	Box(
		modifier = Modifier
			.size(40.dp)
			.clip(CircleShape)
			.background(color.copy(alpha = 0.18f)),
		contentAlignment = Alignment.Center
	) {
		Text(
			text = initials,
			style = MaterialTheme.typography.titleMedium,
			color = color,
			fontWeight = FontWeight.Bold
		)
	}
}

@Composable
private fun BrandFab(onClick: () -> Unit) {
	val gradient = BrandGradients.hero()
	Surface(
		onClick = onClick,
		shape = CircleShape,
		color = Color.Transparent,
		shadowElevation = 8.dp,
		modifier = Modifier.size(64.dp)
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(gradient),
			contentAlignment = Alignment.Center
		) {
			Icon(
				Icons.Default.Add,
				contentDescription = "Add Transaction",
				tint = Color.White,
				modifier = Modifier.size(28.dp)
			)
		}
	}
}

private fun formatDateLabel(date: String): String {
	// Input is "yyyy-MM-dd" or "yyyy-MM-ddTHH:mm"; surface a friendly label with time when present.
	val datePart = if (date.contains("T")) date.substringBefore("T") else date
	val timePart = if (date.contains("T")) date.substringAfter("T") else null
	return if (timePart != null) "$datePart  $timePart" else datePart
}
