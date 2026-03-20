package com.insituledger.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

val IncomeColor = Color(0xFF2E7D32)
val ExpenseColor = Color(0xFFC62828)

@Composable
fun AmountText(
    amount: Double,
    type: String,
    currency: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium
) {
    val color = if (type == "income") IncomeColor else ExpenseColor
    val prefix = if (type == "income") "+" else "-"
    val formatted = try {
        val fmt = NumberFormat.getCurrencyInstance(Locale.getDefault())
        fmt.currency = Currency.getInstance(currency)
        fmt.format(amount)
    } catch (_: Exception) {
        "$currency %.2f".format(amount)
    }
    Text(
        text = "$prefix$formatted",
        color = color,
        style = style,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@Composable
fun LoadingIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun IncomeExpenseToggle(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onSelect("expense") },
            modifier = Modifier.weight(1f).height(44.dp),
            colors = if (selected == "expense") ButtonDefaults.outlinedButtonColors(
                containerColor = ExpenseColor.copy(alpha = 0.12f),
                contentColor = ExpenseColor
            ) else ButtonDefaults.outlinedButtonColors(),
            border = BorderStroke(
                width = if (selected == "expense") 2.dp else 1.dp,
                color = if (selected == "expense") ExpenseColor else MaterialTheme.colorScheme.outline
            )
        ) { Text("Expense", fontWeight = if (selected == "expense") FontWeight.Bold else FontWeight.Normal) }

        OutlinedButton(
            onClick = { onSelect("income") },
            modifier = Modifier.weight(1f).height(44.dp),
            colors = if (selected == "income") ButtonDefaults.outlinedButtonColors(
                containerColor = IncomeColor.copy(alpha = 0.12f),
                contentColor = IncomeColor
            ) else ButtonDefaults.outlinedButtonColors(),
            border = BorderStroke(
                width = if (selected == "income") 2.dp else 1.dp,
                color = if (selected == "income") IncomeColor else MaterialTheme.colorScheme.outline
            )
        ) { Text("Income", fontWeight = if (selected == "income") FontWeight.Bold else FontWeight.Normal) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDropdownWithAdd(
    categories: List<com.insituledger.app.domain.model.Category>,
    selectedId: Long?,
    type: String,
    onSelect: (Long) -> Unit,
    onCreateCategory: (name: String, type: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredCats = categories.filter { it.type == type }
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = categories.find { it.id == selectedId }?.name ?: "",
            onValueChange = {}, readOnly = true, label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            filteredCats.forEach { cat ->
                val indent = if (cat.parentId != null) "    " else ""
                DropdownMenuItem(
                    text = { Text("$indent${cat.name}") },
                    onClick = { onSelect(cat.id); expanded = false }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("New category", color = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = { expanded = false; showAddDialog = true }
            )
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New ${type.replaceFirstChar { it.uppercase() }} Category") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Category name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreateCategory(newName.trim(), type)
                            showAddDialog = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactAccountChip(
    accountDisplays: List<com.insituledger.app.ui.transactions.AccountDisplay>,
    selectedId: Long?,
    onSelect: (id: Long, currency: String) -> Unit,
    onCreateAccount: (name: String, currency: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val selectedDisplay = accountDisplays.find { it.account.id == selectedId }
    val chipLabel = if (selectedDisplay != null) {
        "${selectedDisplay.label} (${selectedDisplay.account.currency})"
    } else {
        "Account"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(chipLabel, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accountDisplays.forEach { display ->
                DropdownMenuItem(
                    text = { Text("${display.label} (${display.account.currency})") },
                    onClick = {
                        onSelect(display.account.id, display.account.currency)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("New account", color = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = { expanded = false; showAddDialog = true }
            )
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newCurrency by remember { mutableStateOf("EUR") }
        var currencyExpanded by remember { mutableStateOf(false) }
        val currencies = listOf("EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "BRL", "CNY", "INR")

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Account name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = currencyExpanded,
                        onExpandedChange = { currencyExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = newCurrency,
                            onValueChange = {}, readOnly = true,
                            label = { Text("Currency") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                            currencies.forEach { cur ->
                                DropdownMenuItem(
                                    text = { Text(cur) },
                                    onClick = { newCurrency = cur; currencyExpanded = false }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreateAccount(newName.trim(), newCurrency)
                            showAddDialog = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDropdownWithAdd(
    accountDisplays: List<com.insituledger.app.ui.transactions.AccountDisplay>,
    selectedId: Long?,
    onSelect: (id: Long, currency: String) -> Unit,
    onCreateAccount: (name: String, currency: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = accountDisplays.find { it.account.id == selectedId }?.label ?: "",
            onValueChange = {}, readOnly = true, label = { Text("Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accountDisplays.forEach { display ->
                DropdownMenuItem(
                    text = { Text("${display.label} (${display.account.currency})") },
                    onClick = {
                        onSelect(display.account.id, display.account.currency)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("New account", color = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = { expanded = false; showAddDialog = true }
            )
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newCurrency by remember { mutableStateOf("EUR") }
        var currencyExpanded by remember { mutableStateOf(false) }
        val currencies = listOf("EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "BRL", "CNY", "INR")

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Account name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ExposedDropdownMenuBox(
                        expanded = currencyExpanded,
                        onExpandedChange = { currencyExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = newCurrency,
                            onValueChange = {}, readOnly = true,
                            label = { Text("Currency") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                            currencies.forEach { cur ->
                                DropdownMenuItem(
                                    text = { Text(cur) },
                                    onClick = { newCurrency = cur; currencyExpanded = false }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onCreateAccount(newName.trim(), newCurrency)
                            showAddDialog = false
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }
}
