package com.insituledger.app.ui.transactions

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.CurrencyFormatter
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.LocalSnackbarHostState
import com.insituledger.app.ui.common.TransactionListSkeleton
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.BrandGradients
import com.insituledger.app.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransactionsScreen(
    onAddClick: () -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var showFilters by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(onClick = { showBatchDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        if (uiState.isSearchActive) {
                            TextField(
                                value = uiState.searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                placeholder = { Text("Search transactions...") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text("Transactions")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleSearch() }) {
                            Icon(
                                if (uiState.isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                        val hasFilters = uiState.filterFrom != null || uiState.filterTo != null || uiState.filterCategoryId != null
                        IconButton(onClick = {
                            if (hasFilters) viewModel.clearFilters() else showFilters = !showFilters
                        }) {
                            Icon(
                                if (hasFilters) Icons.Default.FilterListOff else Icons.Default.FilterList,
                                contentDescription = "Filters"
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                BrandFab(onClick = onAddClick)
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (showFilters) {
                    FilterBar(
                        from = uiState.filterFrom ?: "",
                        to = uiState.filterTo ?: "",
                        categories = uiState.categories,
                        selectedCategoryId = uiState.filterCategoryId,
                        onApply = { from, to, catId ->
                            viewModel.setFilters(
                                from.ifBlank { null },
                                to.ifBlank { null },
                                catId
                            )
                            showFilters = false
                        }
                    )
                }

                SortBar(
                    sortBy = uiState.sortBy,
                    sortDir = uiState.sortDir,
                    onSortChanged = { sortBy, sortDir -> viewModel.setSort(sortBy, sortDir) }
                )

                when {
                    uiState.isLoading -> TransactionListSkeleton()
                    uiState.transactions.isEmpty() -> EmptyState(
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        title = "No transactions yet",
                        message = "Tap + to record your first transaction.",
                        actionLabel = "Add transaction",
                        onAction = onAddClick
                    )
                    else -> {
                        val categoryMap = remember(uiState.categories) {
                            uiState.categories.associateBy { it.id }
                        }
                        val accountMap = remember(uiState.accounts) {
                            uiState.accounts.associateBy { it.id }
                        }
                        val currentUserId = uiState.currentUserId
                        val isSelectionMode = uiState.isSelectionMode
                        val selectedIds = uiState.selectedIds
                        if (uiState.sortBy == "date") {
                            val grouped = remember(uiState.transactions) {
                                uiState.transactions.groupBy { it.date.take(10) }
                            }
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = AppSpacing.lg,
                                    end = AppSpacing.lg,
                                    top = AppSpacing.sm,
                                    bottom = AppSpacing.xxl
                                ),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                grouped.forEach { (date, txns) ->
                                    stickyHeader(key = "header_$date") {
                                        DayHeader(date = date, txns = txns)
                                    }
                                    items(txns, key = { it.id }) { txn ->
                                        Box(modifier = Modifier.animateItem()) {
                                            SwipeableTransactionRow(
                                                txn = txn,
                                                categoryMap = categoryMap,
                                                accountMap = accountMap,
                                                currentUserId = currentUserId,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = selectedIds.contains(txn.id),
                                                onClick = {
                                                    if (isSelectionMode) viewModel.toggleSelect(txn.id)
                                                    else onTransactionClick(txn.id)
                                                },
                                                onLongClick = { viewModel.toggleSelect(txn.id) },
                                                onSwipeDelete = {
                                                    pendingDeleteId = txn.id
                                                    showDeleteDialog = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = AppSpacing.lg,
                                    end = AppSpacing.lg,
                                    top = AppSpacing.sm,
                                    bottom = AppSpacing.xxl
                                ),
                                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                items(uiState.transactions, key = { it.id }) { txn ->
                                    Box(modifier = Modifier.animateItem()) {
                                        SwipeableTransactionRow(
                                            txn = txn,
                                            categoryMap = categoryMap,
                                            accountMap = accountMap,
                                            currentUserId = currentUserId,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedIds.contains(txn.id),
                                            onClick = {
                                                if (isSelectionMode) viewModel.toggleSelect(txn.id)
                                                else onTransactionClick(txn.id)
                                            },
                                            onLongClick = { viewModel.toggleSelect(txn.id) },
                                            onSwipeDelete = {
                                                pendingDeleteId = txn.id
                                                showDeleteDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog && pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; pendingDeleteId = null },
            title = { Text("Delete Transaction") },
            text = { Text("Delete this transaction?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(pendingDeleteId!!)
                    showDeleteDialog = false
                    pendingDeleteId = null
                    scope.launch { snackbarHostState.showSnackbar("Transaction deleted") }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; pendingDeleteId = null }) { Text("Cancel") }
            }
        )
    }

    if (showBatchDeleteDialog) {
        val count = uiState.selectedIds.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete Transactions") },
            text = { Text("Delete ${uiState.selectedIds.size} transaction(s)?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showBatchDeleteDialog = false
                    scope.launch { snackbarHostState.showSnackbar("$count transaction(s) deleted") }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DayHeader(date: String, txns: List<Transaction>) {
    val semantic = LocalSemanticColors.current
    val symbol = LocalCurrencySymbol.current
    val income = txns.filter { it.type == "income" }.sumOf { it.amount }
    val expense = txns.filter { it.type == "expense" }.sumOf { it.amount }
    val net = income - expense
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = AppSpacing.md, bottom = AppSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = friendlyDayLabel(date),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (net != 0.0) {
                val color = if (net >= 0) semantic.income else semantic.expense
                val container = if (net >= 0) semantic.incomeContainer else semantic.expenseContainer
                val sign = if (net >= 0) "+" else "-"
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = container
                ) {
                    Text(
                        text = "$sign${CurrencyFormatter.formatWithSymbol(kotlin.math.abs(net), symbol)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableTransactionRow(
    txn: Transaction,
    categoryMap: Map<Long, Category>,
    accountMap: Map<Long, com.insituledger.app.domain.model.Account>,
    currentUserId: Long?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    if (isSelectionMode) {
        TransactionRowSurface(
            txn = txn,
            categoryMap = categoryMap,
            accountMap = accountMap,
            currentUserId = currentUserId,
            isSelectionMode = true,
            isSelected = isSelected,
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
        )
    } else {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSwipeDelete()
                    false
                } else {
                    false
                }
            }
        )
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val color by animateColorAsState(
                    targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
                    label = "swipe-bg"
                )
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    color = color
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = AppSpacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            },
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true
        ) {
            TransactionRowSurface(
                txn = txn,
                categoryMap = categoryMap,
                accountMap = accountMap,
                currentUserId = currentUserId,
                isSelectionMode = false,
                isSelected = isSelected,
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
            )
        }
    }
}

@Composable
private fun TransactionRowSurface(
    txn: Transaction,
    categoryMap: Map<Long, Category>,
    accountMap: Map<Long, com.insituledger.app.domain.model.Account>,
    currentUserId: Long?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    val isIncome = txn.type == "income"
    val accent = if (isIncome) semantic.income else semantic.expense
    val container = if (isIncome) semantic.incomeContainer else semantic.expenseContainer
    val rowBg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val account = accountMap[txn.accountId]
    val attribution = if (account?.isShared == true && txn.createdByUserId != null && txn.createdByUserId != currentUserId) {
        txn.createdByName?.takeIf { it.isNotBlank() }?.let { "Added by $it" }
    } else null
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = rowBg,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = AppSpacing.sm)
                )
            }
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
                val category = categoryMap[txn.categoryId]
                val time = if (txn.date.contains("T")) txn.date.substringAfter("T").take(5) else null
                val secondary = listOfNotNull(time, category?.name, attribution).joinToString("  ·  ")
                if (secondary.isNotEmpty()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBar(
    sortBy: String,
    sortDir: String,
    onSortChanged: (String, String) -> Unit
) {
    val sortOptions = listOf("date" to "Date", "amount" to "Amount", "description" to "Name")
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Sort:", style = MaterialTheme.typography.labelMedium)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            FilterChip(
                selected = true,
                onClick = { expanded = true },
                label = {
                    val label = sortOptions.find { it.first == sortBy }?.second ?: "Date"
                    val arrow = if (sortDir == "asc") "\u25B2" else "\u25BC"
                    Text("$label $arrow")
                },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                sortOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            val newDir = if (sortBy == value) {
                                if (sortDir == "desc") "asc" else "desc"
                            } else {
                                "desc"
                            }
                            onSortChanged(value, newDir)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    from: String, to: String,
    categories: List<Category>,
    selectedCategoryId: Long?,
    onApply: (String, String, Long?) -> Unit
) {
    var fromText by remember { mutableStateOf(from) }
    var toText by remember { mutableStateOf(to) }
    var catId by remember { mutableStateOf(selectedCategoryId) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm)) {
        Column(modifier = Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = fromText, onValueChange = {},
                        label = { Text("From") }, placeholder = { Text("YYYY-MM-DD") },
                        readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showFromPicker = true }
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = toText, onValueChange = {},
                        label = { Text("To") }, placeholder = { Text("YYYY-MM-DD") },
                        readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { showToPicker = true }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = categories.find { it.id == catId }?.name ?: "All categories",
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("All categories") }, onClick = { catId = null; expanded = false })
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat.name) }, onClick = { catId = cat.id; expanded = false })
                        }
                    }
                }
                Button(onClick = { onApply(fromText, toText, catId) }) { Text("Apply") }
            }
        }
    }

    if (showFromPicker) {
        val initialMillis = try {
            LocalDate.parse(fromText, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        fromText = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showToPicker) {
        val initialMillis = try {
            LocalDate.parse(toText, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        toText = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

private fun friendlyDayLabel(isoDate: String): String {
    return try {
        val date = LocalDate.parse(isoDate, DateTimeFormatter.ISO_LOCAL_DATE)
        val today = LocalDate.now()
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> {
                val sameYear = date.year == today.year
                val pattern = if (sameYear) "EEE, d MMM" else "d MMM yyyy"
                date.format(DateTimeFormatter.ofPattern(pattern))
            }
        }
    } catch (_: Exception) {
        isoDate
    }
}
