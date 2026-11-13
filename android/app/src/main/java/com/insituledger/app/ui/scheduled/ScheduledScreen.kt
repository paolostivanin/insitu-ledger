package com.insituledger.app.ui.scheduled

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.ScheduledTransaction
import com.insituledger.app.ui.common.AddFab
import com.insituledger.app.ui.common.ConfirmDialog
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.ListRow
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.RowAction
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.TransactionAmount
import com.insituledger.app.ui.common.currencySymbolFor
import com.insituledger.app.ui.common.entryAttribution
import com.insituledger.app.ui.common.primaryCurrency
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.util.DateTimeUtil
import com.insituledger.app.util.Rrule
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: ScheduledViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pad = LocalContentPadding.current
    val preferredSymbol = LocalCurrencySymbol.current

    var deleteTarget by remember { mutableStateOf<ScheduledTransaction?>(null) }
    var deleting by remember { mutableStateOf(false) }

    deleteTarget?.let { target ->
        val name = target.description?.takeIf { it.isNotBlank() } ?: "this schedule"
        ConfirmDialog(
            title = "Delete “$name”?",
            text = "Future entries won't be created. Entries already recorded stay as they are.",
            confirmLabel = if (deleting) "Deleting…" else "Delete",
            working = deleting,
            onConfirm = {
                deleting = true
                scope.launch {
                    viewModel.delete(target.id).fold(
                        onSuccess = { snackbar.show("Schedule deleted") },
                        onFailure = { snackbar.show(it.message ?: "Could not delete schedule") }
                    )
                    deleting = false
                    deleteTarget = null
                }
            },
            onDismiss = { deleteTarget = null }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("Scheduled") }, scrollBehavior = scrollBehavior) },
        floatingActionButton = { AddFab(onClick = onAddClick, contentDescription = "Add schedule") }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.EventRepeat,
                title = "Nothing scheduled",
                message = "Schedule rent, salary or subscriptions and they'll be recorded automatically.",
                actionLabel = "Add schedule",
                onAction = onAddClick,
                modifier = Modifier.padding(padding)
            )
            else -> {
                val now = LocalDateTime.now()
                val primary = primaryCurrency(uiState.accounts)
                val active = uiState.items.filter { it.active }.sortedBy { nextLocal(it.nextOccurrence) }
                val paused = uiState.items.filterNot { it.active }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = AppSpacing.fabClearance)
                ) {
                    if (active.isNotEmpty()) {
                        item(key = "h-upcoming") { SectionHeader("Upcoming", horizontalPadding = pad, topPadding = AppSpacing.sm) }
                        items(active, key = { it.id }) { item ->
                            ScheduledRow(item, uiState, now, preferredSymbol, primary, pad, onEditClick) { deleteTarget = item }
                        }
                    }
                    if (paused.isNotEmpty()) {
                        item(key = "h-paused") { SectionHeader("Paused", horizontalPadding = pad) }
                        items(paused, key = { it.id }) { item ->
                            ScheduledRow(item, uiState, now, preferredSymbol, primary, pad, onEditClick) { deleteTarget = item }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduledRow(
    item: ScheduledTransaction,
    uiState: ScheduledUiState,
    now: LocalDateTime,
    preferredSymbol: String,
    primary: String?,
    pad: androidx.compose.ui.unit.Dp,
    onEditClick: (Long) -> Unit,
    onDelete: () -> Unit
) {
    val account = uiState.accounts.find { it.id == item.accountId }
    val category = uiState.categories.find { it.id == item.categoryId }
    val next = nextLocal(item.nextOccurrence)
    val overdue = item.active && next != null && !next.isAfter(now)
    val attribution = entryAttribution(account?.isShared == true, item.createdByUserId, item.createdByName, uiState.currentUserId)
    val supporting = buildSupporting(item, next, overdue, category, account, uiState.accounts.size > 1)
    ListRow(
        headline = item.description?.takeIf { it.isNotBlank() } ?: category?.name ?: "Scheduled ${item.type}",
        supporting = listOfNotNull(supporting, attribution).joinToString("\n"),
        supportingColor = if (overdue) MaterialTheme.colorScheme.tertiary else androidx.compose.ui.graphics.Color.Unspecified,
        trailing = {
            TransactionAmount(
                amount = item.amount,
                type = item.type,
                symbol = currencySymbolFor(item.currency, preferredSymbol, primary)
            )
        },
        onClick = { onEditClick(item.id) },
        actions = listOf(
            RowAction("Edit") { onEditClick(item.id) },
            RowAction("Delete", destructive = true, onClick = onDelete)
        ),
        horizontalPadding = pad,
        modifier = Modifier
    )
}

private fun nextLocal(stored: String): LocalDateTime? =
    DateTimeUtil.parseFormDateOrNull(stored)?.toLocalDateTime()
        ?: runCatching { LocalDate.parse(stored.take(10)).atStartOfDay() }.getOrNull()

private fun buildSupporting(
    item: ScheduledTransaction,
    next: LocalDateTime?,
    overdue: Boolean,
    category: Category?,
    account: Account?,
    showAccount: Boolean
): String {
    val locale = Locale.getDefault()
    val today = LocalDate.now()
    val dayFmt = DateTimeFormatter.ofPattern(
        if (next != null && next.year != today.year) "EEE, d MMM yyyy" else "EEE, d MMM", locale
    )
    val hasTime = item.nextOccurrence.contains('T')
    val label = Rrule.label(item.rrule, item.maxOccurrences)
    val oneTime = label == "One-time"
    // The time matters for a one-off; for a series the day is what you scan for.
    val whenText = next?.let {
        it.format(dayFmt) + if (hasTime && oneTime) " at " + it.format(DateTimeFormatter.ofPattern("HH:mm", locale)) else ""
    } ?: item.nextOccurrence
    val until = Rrule.parse(item.rrule).until.takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)) }.getOrNull()
    }
    val parts = mutableListOf<String>()
    parts += if (overdue) "$label · due now" else if (oneTime) "$label · $whenText" else "$label · next $whenText"
    if (!oneTime && item.maxOccurrences != null) parts += "${item.occurrenceCount} of ${item.maxOccurrences}"
    if (until != null) parts += "until $until"
    // Category only: the account is one tap away and made every row wrap.
    return (parts + listOfNotNull(category?.name)).joinToString(" · ")
}
