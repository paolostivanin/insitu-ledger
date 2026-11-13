package com.insituledger.app.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AddFab
import com.insituledger.app.ui.common.AdaptiveTrailingRow
import com.insituledger.app.ui.common.CategoryPickerSheet
import com.insituledger.app.ui.common.ConfirmDialog
import com.insituledger.app.ui.common.DateLabels
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.MoneyFormat
import com.insituledger.app.ui.common.MoneyText
import com.insituledger.app.ui.common.TransactionRow
import com.insituledger.app.ui.common.TransactionRowModel
import com.insituledger.app.ui.common.categoryColor
import com.insituledger.app.ui.common.currencySymbolFor
import com.insituledger.app.ui.common.entryAttribution
import com.insituledger.app.ui.common.label
import com.insituledger.app.ui.common.primaryCurrency
import com.insituledger.app.ui.common.rangeLabel
import com.insituledger.app.ui.common.utcMillisToLocalDate
import com.insituledger.app.ui.common.toUtcMillis
import com.insituledger.app.ui.reports.DateRangePreset
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.LocalSemanticColors
import com.insituledger.app.ui.theme.amountSmall
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.time.LocalDate

@Immutable
private data class DayGroup(
    val date: String,
    val label: String,
    val net: Double,
    val rows: List<TransactionRowModel>
)

private val FILTER_PRESETS = listOf(
    DateRangePreset.THIS_WEEK,
    DateRangePreset.THIS_MONTH,
    DateRangePreset.LAST_MONTH,
    DateRangePreset.LAST_3_MONTHS,
    DateRangePreset.THIS_YEAR,
    DateRangePreset.LAST_YEAR
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onAddClick: () -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // System back leaves selection first, then search, before leaving the screen.
    BackHandler(enabled = uiState.isSelectionMode) { viewModel.clearSelection() }
    BackHandler(enabled = !uiState.isSelectionMode && uiState.isSearchActive) { viewModel.toggleSearch() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            when {
                uiState.isSelectionMode -> SelectionTopBar(
                    count = uiState.selectedIds.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onDelete = { showBatchDeleteDialog = true }
                )
                uiState.isSearchActive -> SearchTopBar(
                    query = uiState.searchQuery,
                    onQueryChange = viewModel::setSearchQuery,
                    onClose = viewModel::toggleSearch
                )
                else -> TopAppBar(
                    title = { Text("Transactions", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = viewModel::toggleSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search transactions")
                        }
                        SortMenu(
                            sortBy = uiState.sortBy,
                            sortDir = uiState.sortDir,
                            onSort = viewModel::setSort
                        )
                    }
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                AddFab(onClick = onAddClick, contentDescription = "Add transaction")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!uiState.isSelectionMode) {
                FilterChipRow(
                    uiState = uiState,
                    onPreset = viewModel::setDatePreset,
                    onCustomRange = viewModel::setCustomRange,
                    onCategory = viewModel::setCategoryFilter,
                    onClear = viewModel::clearFilters
                )
            }
            Box(Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = uiState.isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        uiState.isLoading -> LoadingIndicator()
                        // Search narrows within the active filters, so an empty list
                        // usually means "nothing matched", not "nothing recorded".
                        uiState.transactions.isEmpty() && isNarrowed(uiState) -> EmptyState(
                            icon = Icons.Outlined.SearchOff,
                            title = "Nothing matches",
                            message = "No transactions match the current search and filters.",
                            actionLabel = "Clear search and filters",
                            onAction = { viewModel.clearSearchAndFilters() }
                        )
                        uiState.transactions.isEmpty() -> EmptyState(
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            title = "No transactions yet",
                            message = "Record an expense or income and it will appear here.",
                            actionLabel = "Add transaction",
                            onAction = onAddClick
                        )
                        else -> TransactionsList(
                            uiState = uiState,
                            listState = listState,
                            onRowClick = { id ->
                                if (uiState.isSelectionMode) viewModel.toggleSelect(id) else onTransactionClick(id)
                            },
                            onRowLongClick = viewModel::toggleSelect,
                            onLoadMore = viewModel::loadMore
                        )
                    }
                }
                if (uiState.isUpdating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    if (showBatchDeleteDialog) {
        val count = uiState.selectedIds.size
        val noun = if (count == 1) "transaction" else "transactions"
        ConfirmDialog(
            title = "Delete $count $noun?",
            text = "Account balances will be adjusted. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deleteSelected()
                showBatchDeleteDialog = false
                snackbar.show("$count $noun deleted")
            },
            onDismiss = { showBatchDeleteDialog = false }
        )
    }
}

private fun isNarrowed(state: TransactionsUiState): Boolean =
    state.searchQuery.isNotBlank() ||
        state.filterFrom != null || state.filterTo != null || state.filterCategoryId != null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Exit selection") }
        },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete selected") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search transactions") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        }
    )
}

@Composable
private fun SortMenu(sortBy: String, sortDir: String, onSort: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort transactions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("date" to "Date", "amount" to "Amount", "description" to "Name").forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = sortBy == key, onClick = null) },
                    onClick = {
                        // Keep the direction when switching field; names default A–Z.
                        val dir = if (key == sortBy) sortDir else if (key == "description") "asc" else "desc"
                        onSort(key, dir)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            val (descLabel, ascLabel) = when (sortBy) {
                "amount" -> "Largest first" to "Smallest first"
                "description" -> "Z to A" to "A to Z"
                else -> "Newest first" to "Oldest first"
            }
            listOf("desc" to descLabel, "asc" to ascLabel).forEach { (dir, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { RadioButton(selected = sortDir == dir, onClick = null) },
                    onClick = { onSort(sortBy, dir); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    uiState: TransactionsUiState,
    onPreset: (DateRangePreset?) -> Unit,
    onCustomRange: (String, String) -> Unit,
    onCategory: (Long?) -> Unit,
    onClear: () -> Unit
) {
    var dateMenu by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    val hasDates = uiState.filterFrom != null || uiState.filterTo != null
    val hasCategory = uiState.filterCategoryId != null
    val pad = LocalContentPadding.current

    val dateLabel = when {
        !hasDates -> "Dates"
        uiState.datePreset != null && uiState.datePreset != DateRangePreset.CUSTOM -> uiState.datePreset.label()
        else -> rangeLabel(uiState.filterFrom, uiState.filterTo)
    }
    val categoryLabel = uiState.categories.find { it.id == uiState.filterCategoryId }?.name ?: "Category"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = pad),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            FilterChip(
                selected = hasDates,
                onClick = { dateMenu = true },
                label = { Text(dateLabel, maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
            )
            DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Any time") },
                    leadingIcon = { RadioButton(selected = !hasDates, onClick = null) },
                    onClick = { onPreset(null); dateMenu = false }
                )
                FILTER_PRESETS.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.label()) },
                        leadingIcon = { RadioButton(selected = hasDates && uiState.datePreset == preset, onClick = null) },
                        onClick = { onPreset(preset); dateMenu = false }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Custom range…") },
                    leadingIcon = {
                        RadioButton(selected = hasDates && uiState.datePreset == DateRangePreset.CUSTOM, onClick = null)
                    },
                    onClick = { dateMenu = false; showRangePicker = true }
                )
            }
        }
        FilterChip(
            selected = hasCategory,
            onClick = { showCategorySheet = true },
            label = { Text(categoryLabel, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
        )
        if (hasDates || hasCategory) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }

    if (showRangePicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = uiState.filterFrom?.let { DateLabels.parseDay(it) }?.toUtcMillis(),
            initialSelectedEndDateMillis = uiState.filterTo?.let { DateLabels.parseDay(it) }?.toUtcMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.let(::utcMillisToLocalDate)
                        val end = state.selectedEndDateMillis?.let(::utcMillisToLocalDate) ?: start
                        if (start != null && end != null) onCustomRange(start.toString(), end.toString())
                        showRangePicker = false
                    }
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } }
        ) {
            DateRangePicker(
                state = state,
                title = { Text("Date range", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    if (showCategorySheet) {
        CategoryPickerSheet(
            categories = uiState.categories,
            selectedId = uiState.filterCategoryId,
            type = null,
            allowAll = true,
            onSelect = onCategory,
            onDismiss = { showCategorySheet = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionsList(
    uiState: TransactionsUiState,
    listState: LazyListState,
    onRowClick: (Long) -> Unit,
    onRowLongClick: (Long) -> Unit,
    onLoadMore: () -> Unit
) {
    val symbol = LocalCurrencySymbol.current
    val pad = LocalContentPadding.current
    val primary = remember(uiState.accounts) { primaryCurrency(uiState.accounts) }
    val byDate = uiState.sortBy == "date"

    val rows = remember(uiState.transactions, uiState.categories, uiState.accounts, uiState.currentUserId, symbol, byDate) {
        buildRows(uiState.transactions, uiState.categories, uiState.accounts, uiState.currentUserId, symbol, primary, byDate)
    }

    // Load the next page automatically when the end comes into view.
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState, uiState.hasMore) {
        if (!uiState.hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 10
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentOnLoadMore() }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = AppSpacing.fabClearance)
    ) {
        if (byDate) {
            val groups = groupByDay(uiState.transactions, rows)
            groups.forEach { group ->
                stickyHeader(key = "header_${group.date}", contentType = "day_header") {
                    DayHeader(group, symbol, pad)
                }
                rowItems(group.rows, uiState, pad, onRowClick, onRowLongClick)
            }
        } else {
            rowItems(rows, uiState, pad, onRowClick, onRowLongClick)
        }
        if (uiState.hasMore) {
            item(key = "load_more", contentType = "load_more") {
                Box(Modifier.fillMaxWidth().padding(vertical = AppSpacing.md), contentAlignment = Alignment.Center) {
                    if (uiState.isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = onLoadMore) { Text("Load more") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.rowItems(
    rows: List<TransactionRowModel>,
    uiState: TransactionsUiState,
    pad: Dp,
    onRowClick: (Long) -> Unit,
    onRowLongClick: (Long) -> Unit
) {
    items(rows, key = { it.id }, contentType = { "transaction" }) { row ->
        TransactionRow(
            model = row,
            onClick = { onRowClick(row.id) },
            onLongClick = { onRowLongClick(row.id) },
            selectionMode = uiState.isSelectionMode,
            selected = row.id in uiState.selectedIds,
            horizontalPadding = pad,
            modifier = Modifier.animateItem()
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup, symbol: String, pad: Dp) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        // Label first; the day total is measured whole and drops below the
        // label when both don't fit on one line.
        AdaptiveTrailingRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = pad, end = pad, top = AppSpacing.lg, bottom = AppSpacing.xs),
            content = {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            },
            trailing = {
                if (kotlin.math.abs(group.net) > 0.004) {
                    // Day totals stay neutral; the sign carries the meaning.
                    MoneyText(
                        text = MoneyFormat.net(group.net, symbol),
                        style = MaterialTheme.typography.amountSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        spoken = "Day total ${MoneyFormat.net(group.net, symbol)}"
                    )
                }
            }
        )
    }
}

private fun groupByDay(transactions: List<Transaction>, rows: List<TransactionRowModel>): List<DayGroup> {
    val today = LocalDate.now()
    val rowById = rows.associateBy { it.id }
    return transactions.groupBy { it.date.take(10) }.map { (date, txns) ->
        val net = txns.sumOf { if (it.type == "income") it.amount else -it.amount }
        DayGroup(
            date = date,
            label = DateLabels.parseDay(date)?.let { DateLabels.day(it, today) } ?: date,
            net = net,
            rows = txns.mapNotNull { rowById[it.id] }
        )
    }
}

private fun buildRows(
    transactions: List<Transaction>,
    categories: List<Category>,
    accounts: List<Account>,
    currentUserId: Long?,
    preferredSymbol: String,
    primary: String?,
    byDate: Boolean
): List<TransactionRowModel> {
    val categoryMap = categories.associateBy { it.id }
    val accountMap = accounts.associateBy { it.id }
    val showAccount = accounts.size > 1
    val today = LocalDate.now()
    return transactions.map { txn ->
        val account = accountMap[txn.accountId]
        val categoryName = categoryMap[txn.categoryId]?.name
        // Day headers carry the date in date order; otherwise each row shows it.
        val whenLabel = if (byDate) DateLabels.time(txn.date)
        else DateLabels.parseDay(txn.date)?.let { DateLabels.short(it, today) }
        TransactionRowModel(
            id = txn.id,
            title = txn.description?.takeIf { it.isNotBlank() } ?: categoryName ?: txn.type.replaceFirstChar { it.uppercase() },
            supporting = listOfNotNull(categoryName, account?.name?.takeIf { showAccount }, whenLabel).joinToString(" · "),
            categoryColor = categoryColor(categories, txn.categoryId),
            attribution = entryAttribution(account?.isShared == true, txn.createdByUserId, txn.createdByName, currentUserId),
            amount = txn.amount,
            type = txn.type,
            symbol = currencySymbolFor(txn.currency, preferredSymbol, primary)
        )
    }
}
