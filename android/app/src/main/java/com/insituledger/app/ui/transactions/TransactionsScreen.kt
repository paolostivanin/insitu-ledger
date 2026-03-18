package com.insituledger.app.ui.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onAddClick: () -> Unit,
    onTransactionClick: (Long) -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transactions") },
                actions = {
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add Transaction")
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
                        if (uiState.sortBy == "date") {
                            val grouped = uiState.transactions.groupBy { it.date }
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                grouped.forEach { (date, txns) ->
                                    item(key = "header_$date") {
                                        Text(
                                            text = date,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                        )
                                    }
                                    items(txns, key = { it.id }) { txn ->
                                        TransactionRow(
                                            txn = txn,
                                            onClick = { onTransactionClick(txn.id) },
                                            onDelete = { viewModel.delete(txn.id) }
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
                                    TransactionRow(
                                        txn = txn,
                                        onClick = { onTransactionClick(txn.id) },
                                        onDelete = { viewModel.delete(txn.id) }
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
                modifier = Modifier.menuAnchor()
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

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fromText, onValueChange = { fromText = it },
                    label = { Text("From") }, placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = toText, onValueChange = { toText = it },
                    label = { Text("To") }, placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
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
                        modifier = Modifier.menuAnchor()
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
}

@Composable
private fun TransactionRow(txn: Transaction, onClick: () -> Unit, onDelete: () -> Unit) {
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
            }
            AmountText(amount = txn.amount, type = txn.type, currency = txn.currency)
        }
    }
}
