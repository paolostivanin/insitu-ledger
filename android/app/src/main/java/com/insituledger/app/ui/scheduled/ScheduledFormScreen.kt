package com.insituledger.app.ui.scheduled

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
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledFormScreen(
    onBack: () -> Unit,
    viewModel: ScheduledFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) { if (uiState.saved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.id != null) "Edit Scheduled" else "New Scheduled") },
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = uiState.type == "expense", onClick = { viewModel.updateType("expense") },
                    label = { Text("Expense") }, modifier = Modifier.weight(1f))
                FilterChip(selected = uiState.type == "income", onClick = { viewModel.updateType("income") },
                    label = { Text("Income") }, modifier = Modifier.weight(1f))
            }

            var accountExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = accountExpanded, onExpandedChange = { accountExpanded = it }) {
                OutlinedTextField(
                    value = uiState.accounts.find { it.id == uiState.accountId }?.name ?: "",
                    onValueChange = {}, readOnly = true, label = { Text("Account") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                    uiState.accounts.forEach { account ->
                        DropdownMenuItem(text = { Text("${account.name} (${account.currency})") }, onClick = {
                            viewModel.updateAccountId(account.id); viewModel.updateCurrency(account.currency)
                            accountExpanded = false
                        })
                    }
                }
            }

            var catExpanded by remember { mutableStateOf(false) }
            val filteredCats = uiState.categories.filter { it.type == uiState.type }
            ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                OutlinedTextField(
                    value = uiState.categories.find { it.id == uiState.categoryId }?.name ?: "",
                    onValueChange = {}, readOnly = true, label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                    filteredCats.forEach { cat ->
                        DropdownMenuItem(text = { Text(cat.name) }, onClick = {
                            viewModel.updateCategoryId(cat.id); catExpanded = false
                        })
                    }
                }
            }

            OutlinedTextField(value = uiState.amount, onValueChange = viewModel::updateAmount,
                label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = uiState.description, onValueChange = viewModel::updateDescription,
                label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = uiState.rrule, onValueChange = viewModel::updateRrule,
                label = { Text("RRule (e.g. FREQ=MONTHLY;INTERVAL=1)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = uiState.nextOccurrence, onValueChange = viewModel::updateNextOccurrence,
                label = { Text("Next Occurrence (YYYY-MM-DD)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().height(48.dp), enabled = !uiState.isSaving) {
                if (uiState.isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                else Text(if (uiState.id != null) "Update" else "Create")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
