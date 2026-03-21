package com.insituledger.app.ui.reports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Transaction
import com.insituledger.app.ui.common.AmountText
import com.insituledger.app.ui.common.CurrencyFormatter
import com.insituledger.app.ui.common.ExpenseColor
import com.insituledger.app.ui.common.IncomeColor
import com.insituledger.app.ui.common.LoadingIndicator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportsScreen(
    onBack: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.selectedCategory != null) {
        BackHandler { viewModel.clearSelection() }
        DrillDownView(uiState, onBack = { viewModel.clearSelection() })
    } else {
        SummaryView(uiState, viewModel, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SummaryView(
    uiState: ReportsUiState,
    viewModel: ReportsViewModel,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reports") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Date range chips
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateRangePreset.entries.forEach { preset ->
                        FilterChip(
                            selected = uiState.dateRangePreset == preset,
                            onClick = {
                                if (preset != DateRangePreset.CUSTOM) viewModel.setDateRangePreset(preset)
                                else viewModel.setDateRangePreset(DateRangePreset.CUSTOM)
                            },
                            label = { Text(presetLabel(preset)) }
                        )
                    }
                }
            }

            // Custom date range fields with date pickers
            if (uiState.dateRangePreset == DateRangePreset.CUSTOM) {
                item {
                    var fromText by remember { mutableStateOf(uiState.customFrom) }
                    var toText by remember { mutableStateOf(uiState.customTo) }
                    var showFromPicker by remember { mutableStateOf(false) }
                    var showToPicker by remember { mutableStateOf(false) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = fromText,
                                onValueChange = {},
                                label = { Text("From") },
                                placeholder = { Text("YYYY-MM-DD") },
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { showFromPicker = true }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = toText,
                                onValueChange = {},
                                label = { Text("To") },
                                placeholder = { Text("YYYY-MM-DD") },
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable { showToPicker = true }
                            )
                        }
                        Button(
                            onClick = { viewModel.setCustomDateRange(fromText, toText) },
                            modifier = Modifier.align(Alignment.CenterVertically)
                        ) { Text("Apply") }
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
            }

            // Summary cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryCard("Income", uiState.totalIncome, IncomeColor, Modifier.weight(1f))
                    SummaryCard("Expenses", uiState.totalExpense, ExpenseColor, Modifier.weight(1f))
                }
            }

            // Category breakdown header
            if (uiState.categoryBreakdown.isNotEmpty()) {
                item {
                    Text("Category Breakdown", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // Category breakdown items
            items(uiState.categoryBreakdown, key = { it.category.id }) { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { viewModel.selectCategory(summary.category) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(parseColor(summary.category.color))
                            )
                            Column {
                                Text(summary.category.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    summary.category.type.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = formatCurrency(summary.total),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (summary.category.type == "income") IncomeColor else ExpenseColor
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrillDownView(uiState: ReportsUiState, onBack: () -> Unit) {
    val category = uiState.selectedCategory ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val grouped = remember(uiState.selectedCategoryTransactions) {
            uiState.selectedCategoryTransactions.groupBy { it.date }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Total card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Total for period", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatCurrency(uiState.selectedCategoryTotal),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (category.type == "income") IncomeColor else ExpenseColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Transactions grouped by date
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
                    DrillDownTransactionRow(txn)
                }
            }
        }
    }
}

@Composable
private fun DrillDownTransactionRow(txn: Transaction) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = txn.description ?: txn.type.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            AmountText(amount = txn.amount, type = txn.type, currency = txn.currency)
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
                text = formatCurrency(amount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

private fun presetLabel(preset: DateRangePreset): String = when (preset) {
    DateRangePreset.THIS_WEEK -> "This Week"
    DateRangePreset.THIS_MONTH -> "This Month"
    DateRangePreset.LAST_WEEK -> "Last Week"
    DateRangePreset.LAST_MONTH -> "Last Month"
    DateRangePreset.LAST_3_MONTHS -> "Last 3 Months"
    DateRangePreset.LAST_YEAR -> "Last Year"
    DateRangePreset.CUSTOM -> "Custom"
}

private fun parseColor(hex: String?): Color {
    if (hex == null) return Color.Gray
    return try {
        Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
    } catch (_: Exception) {
        Color.Gray
    }
}

private fun formatCurrency(amount: Double): String {
    return CurrencyFormatter.format(amount, "EUR")
}
