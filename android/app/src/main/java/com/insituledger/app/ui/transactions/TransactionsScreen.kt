package com.insituledger.app.ui.transactions

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Category
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.ExpenseColor
import com.insituledger.app.ui.common.LoadingIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.insituledger.app.ui.common.CurrencyFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onAddClick: () -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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
            if (!uiState.isReadOnly && !uiState.isSelectionMode) {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(Icons.Default.Add, contentDescription = "Add Transaction")
                }
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
                    uiState.isLoading -> LoadingIndicator()
                    uiState.transactions.isEmpty() -> EmptyState("No transactions")
                    else -> {
                        val categoryMap = remember(uiState.categories) {
                            uiState.categories.associateBy { it.id }
                        }
                        val isSelectionMode = uiState.isSelectionMode
                        val selectedIds = uiState.selectedIds
                        val isReadOnly = uiState.isReadOnly
                        if (uiState.sortBy == "date") {
                            val grouped = remember(uiState.transactions) {
                                uiState.transactions.groupBy { it.date.take(10) }
                            }
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                grouped.forEach { (date, txns) ->
                                    item(key = "header_$date") {
                                        val dailyExpense = txns.filter { it.type == "expense" }.sumOf { it.amount }
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = date,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (dailyExpense > 0) {
                                                Text(
                                                    text = "-${formatAmount(dailyExpense)}",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = ExpenseColor
                                                )
                                            }
                                        }
                                    }
                                    items(txns, key = { it.id }) { txn ->
                                        SwipeableTransactionRow(
                                            txn = txn,
                                            categoryMap = categoryMap,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedIds.contains(txn.id),
                                            isReadOnly = isReadOnly,
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
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(uiState.transactions, key = { it.id }) { txn ->
                                    SwipeableTransactionRow(
                                        txn = txn,
                                        categoryMap = categoryMap,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = selectedIds.contains(txn.id),
                                        isReadOnly = isReadOnly,
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

    // Single delete confirmation dialog
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
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false; pendingDeleteId = null }) { Text("Cancel") }
            }
        )
    }

    // Batch delete confirmation dialog
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("Delete Transactions") },
            text = { Text("Delete ${uiState.selectedIds.size} transaction(s)?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showBatchDeleteDialog = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeableTransactionRow(
    txn: Transaction,
    categoryMap: Map<Long, Category>,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isReadOnly: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: () -> Unit
) {
    if (isReadOnly || isSelectionMode) {
        Card(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
        ) {
            TransactionRowContent(txn, categoryMap, isSelectionMode, isSelected)
        }
    } else {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) {
                    onSwipeDelete()
                    false // Don't dismiss yet — wait for dialog confirmation
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
                        MaterialTheme.colorScheme.error else Color.Transparent,
                    label = "swipe-bg"
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
                }
            },
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
            ) {
                TransactionRowContent(txn, categoryMap, isSelectionMode, isSelected)
            }
        }
    }
}

@Composable
private fun TransactionRowContent(
    txn: Transaction,
    categoryMap: Map<Long, Category>,
    isSelectionMode: Boolean,
    isSelected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val category = categoryMap[txn.categoryId]
            if (category?.color != null) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(parseColor(category.color))
                        .semantics { contentDescription = "Category: ${category.name}" }
                )
            }
            Text(
                text = txn.description ?: txn.type.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium
            )
        }
        AmountText(amount = txn.amount, type = txn.type, currency = txn.currency)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBar(
    sortBy: String,
    sortDir: String,
    onSortChanged: (String, String) -> Unit
) {
    val sortOptions = listOf("date" to "Date", "amount" to "Amount", "description" to "Description")
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    categories: List<com.insituledger.app.domain.model.Category>,
    selectedCategoryId: Long?,
    onApply: (String, String, Long?) -> Unit
) {
    var fromText by remember { mutableStateOf(from) }
    var toText by remember { mutableStateOf(to) }
    var catId by remember { mutableStateOf(selectedCategoryId) }

    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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

private fun parseColor(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (_: Exception) {
        Color.Gray
    }
}

private fun formatAmount(amount: Double): String {
    return CurrencyFormatter.format(amount, "EUR")
}
