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
import com.insituledger.app.ui.common.CategoryDropdownWithAdd
import com.insituledger.app.ui.common.IncomeExpenseToggle
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

            IncomeExpenseToggle(selected = uiState.type, onSelect = viewModel::updateType)

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
                            viewModel.updateAccountId(account.id); viewModel.updateCurrency(account.currency)
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

            OutlinedTextField(value = uiState.description, onValueChange = viewModel::updateDescription,
                label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            // Frequency dropdown
            var freqExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = freqExpanded, onExpandedChange = { freqExpanded = it }) {
                OutlinedTextField(
                    value = ScheduledFormUiState.frequencyLabels[uiState.frequency] ?: uiState.frequency,
                    onValueChange = {}, readOnly = true, label = { Text("Frequency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = freqExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = freqExpanded, onDismissRequest = { freqExpanded = false }) {
                    ScheduledFormUiState.frequencyLabels.forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            viewModel.updateFrequency(key)
                            freqExpanded = false
                        })
                    }
                }
            }

            OutlinedTextField(value = uiState.nextDate, onValueChange = viewModel::updateNextDate,
                label = { Text("Next Date (YYYY-MM-DD)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = uiState.nextTime, onValueChange = viewModel::updateNextTime,
                label = { Text("Time (HH:MM)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())

            OutlinedTextField(value = uiState.maxOccurrences, onValueChange = viewModel::updateMaxOccurrences,
                label = { Text("Stop after N occurrences") }, placeholder = { Text("Unlimited") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth())

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
