package com.insituledger.app.ui.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.CategoryDropdownWithAdd
import com.insituledger.app.ui.common.IncomeExpenseToggle
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionFormScreen(
    onBack: () -> Unit,
    viewModel: TransactionFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) { if (uiState.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.id != null) "Edit Transaction" else "New Transaction") },
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

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            IncomeExpenseToggle(selected = uiState.type, onSelect = viewModel::updateType)

            // Account selector
            var accountExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = accountExpanded, onExpandedChange = { accountExpanded = it }) {
                OutlinedTextField(
                    value = uiState.accounts.find { it.id == uiState.accountId }?.name ?: "",
                    onValueChange = {}, readOnly = true, label = { Text("Account") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                    uiState.accounts.forEach { account ->
                        DropdownMenuItem(text = { Text("${account.name} (${account.currency})") }, onClick = {
                            viewModel.updateAccountId(account.id)
                            viewModel.updateCurrency(account.currency)
                            accountExpanded = false
                        })
                    }
                }
            }

            CategoryDropdownWithAdd(
                categories = uiState.categories,
                selectedId = uiState.categoryId,
                type = uiState.type,
                onSelect = viewModel::updateCategoryId,
                onCreateCategory = viewModel::createCategory
            )

            OutlinedTextField(value = uiState.amount, onValueChange = viewModel::updateAmount,
                label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth())

            ExposedDropdownMenuBox(
                expanded = uiState.showSuggestions,
                onExpandedChange = { if (!it) viewModel.dismissSuggestions() }
            ) {
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable)
                )
                if (uiState.suggestions.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = uiState.showSuggestions,
                        onDismissRequest = { viewModel.dismissSuggestions() }
                    ) {
                        uiState.suggestions.forEach { suggestion ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(suggestion.description)
                                        val catName = uiState.categories.find { it.id == suggestion.categoryId }?.name ?: ""
                                        if (catName.isNotEmpty()) {
                                            Text(
                                                catName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = { viewModel.selectSuggestion(suggestion) }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(value = uiState.date, onValueChange = viewModel::updateDate,
                label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (uiState.id != null) "Update" else "Create")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
