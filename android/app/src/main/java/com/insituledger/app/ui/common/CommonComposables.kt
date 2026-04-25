@file:OptIn(ExperimentalLayoutApi::class)

package com.insituledger.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.LocalSemanticColors

@Composable
fun AmountText(
    amount: Double,
    type: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium
) {
    val semanticColors = LocalSemanticColors.current
    val color = if (type == "income") semanticColors.income else semanticColors.expense
    val prefix = if (type == "income") "+" else "-"
    val symbol = LocalCurrencySymbol.current
    val formatted = CurrencyFormatter.formatWithSymbol(amount, symbol)
    Text(
        text = "$prefix$formatted",
        color = color,
        style = style.copy(fontFeatureSettings = "tnum"),
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
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = AppSpacing.xl)
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.lg))
            }
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(AppSpacing.xl))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
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
            Spacer(modifier = Modifier.height(AppSpacing.lg))
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
    val semanticColors = LocalSemanticColors.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        OutlinedButton(
            onClick = { onSelect("expense") },
            modifier = Modifier.weight(1f).height(44.dp),
            colors = if (selected == "expense") ButtonDefaults.outlinedButtonColors(
                containerColor = semanticColors.expense.copy(alpha = 0.12f),
                contentColor = semanticColors.expense
            ) else ButtonDefaults.outlinedButtonColors(),
            border = BorderStroke(
                width = if (selected == "expense") 2.dp else 1.dp,
                color = if (selected == "expense") semanticColors.expense else MaterialTheme.colorScheme.outline
            )
        ) { Text("Expense", fontWeight = if (selected == "expense") FontWeight.Bold else FontWeight.Normal) }

        OutlinedButton(
            onClick = { onSelect("income") },
            modifier = Modifier.weight(1f).height(44.dp),
            colors = if (selected == "income") ButtonDefaults.outlinedButtonColors(
                containerColor = semanticColors.income.copy(alpha = 0.12f),
                contentColor = semanticColors.income
            ) else ButtonDefaults.outlinedButtonColors(),
            border = BorderStroke(
                width = if (selected == "income") 2.dp else 1.dp,
                color = if (selected == "income") semanticColors.income else MaterialTheme.colorScheme.outline
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
    val selectedCat = categories.find { it.id == selectedId }
    val parentCat = selectedCat?.parentId?.let { pid -> categories.find { it.id == pid } }

    var showModal by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Compact clickable text trigger (matches date/time style)
    val displayText = if (parentCat != null) {
        "${parentCat.name} > ${selectedCat?.name ?: ""}"
    } else {
        selectedCat?.name ?: "Category"
    }
    Row(
        modifier = modifier.fillMaxWidth().clickable { showModal = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(ColorUtils.parseHex(parentCat?.color ?: selectedCat?.color))
        )
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }

    // Bottom sheet modal
    if (showModal) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // Absorb leftover scroll/fling from the inner LazyColumn so scrolling at the
        // top of the list doesn't get forwarded to the sheet's drag handler. Direct
        // drag on the sheet's drag handle / non-scrollable areas still dismisses.
        val absorbScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset = available
                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity
                ): Velocity = available
            }
        }
        var searchQuery by remember { mutableStateOf("") }

        val topLevel = remember(filteredCats) { filteredCats.filter { it.parentId == null } }

        fun matchesSearch(cat: com.insituledger.app.domain.model.Category): Boolean {
            if (searchQuery.isBlank()) return true
            return cat.name.contains(searchQuery, ignoreCase = true)
        }

        val filteredGroups = remember(topLevel, searchQuery) {
            topLevel.filter { parent ->
                matchesSearch(parent) || filteredCats.any { it.parentId == parent.id && matchesSearch(it) }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showModal = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.xl)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Select Category",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = { showModal = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // Search + New row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    FilledTonalButton(
                        onClick = { showModal = false; showAddDialog = true }
                    ) {
                        Text("New")
                    }
                }

                // Category groups
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .nestedScroll(absorbScrollConnection),
                    contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    items(filteredGroups, key = { it.id }) { parent ->
                        val allChildren = remember(filteredCats, searchQuery, parent.id) {
                            val children = filteredCats.filter { it.parentId == parent.id && matchesSearch(it) }
                            if (searchQuery.isBlank()) {
                                filteredCats.filter { it.parentId == parent.id }
                            } else {
                                children.ifEmpty {
                                    filteredCats.filter { it.parentId == parent.id }
                                }
                            }
                        }

                        if (allChildren.isNotEmpty()) {
                            // Parent as group header
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(ColorUtils.parseHex(parent.color))
                                    )
                                    Text(
                                        text = parent.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                // Subcategory chips
                                FlowRow(
                                    modifier = Modifier.padding(start = AppSpacing.sm),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    allChildren.forEach { child ->
                                        val isSelected = child.id == selectedId
                                        Surface(
                                            onClick = {
                                                onSelect(child.id)
                                                showModal = false
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected)
                                                MaterialTheme.colorScheme.primaryContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant,
                                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                                        ) {
                                            Text(
                                                text = child.name,
                                                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Standalone parent (no children)
                            val isSelected = parent.id == selectedId
                            Surface(
                                onClick = {
                                    onSelect(parent.id)
                                    showModal = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    Color.Transparent
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(ColorUtils.parseHex(parent.color))
                                    )
                                    Text(
                                        text = parent.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    if (filteredGroups.isEmpty()) {
                        item {
                            Text(
                                "No matching categories",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = AppSpacing.xl),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // Create category dialog
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
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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

    // Compact clickable text trigger (matches date/time style)
    val chipLabel = if (selectedDisplay != null) {
        "${selectedDisplay.label} (${selectedDisplay.account.currency})"
    } else {
        "Account"
    }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        Text(
            text = chipLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = true }.menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accountDisplays.forEach { display ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                display.label,
                                fontWeight = if (display.account.id == selectedId) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                display.account.currency,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onSelect(display.account.id, display.account.currency)
                        expanded = false
                    }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Account name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
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
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Account name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
