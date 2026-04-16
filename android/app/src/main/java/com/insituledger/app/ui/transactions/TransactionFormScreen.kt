package com.insituledger.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import com.insituledger.app.ui.common.CalculatorDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.CategoryDropdownWithAdd
import com.insituledger.app.ui.common.CompactAccountChip
import com.insituledger.app.ui.common.IncomeExpenseToggle
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalSnackbarHostState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionFormScreen(
    onBack: () -> Unit,
    viewModel: TransactionFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            scope.launch { snackbarHostState.showSnackbar(if (uiState.id != null) "Transaction updated" else "Transaction created") }
            onBack()
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showCalculator by remember { mutableStateOf(false) }

    // Parse date and time from the state string
    val datePart = if (uiState.date.contains("T")) uiState.date.substringBefore("T") else uiState.date
    val timePart = if (uiState.date.contains("T")) uiState.date.substringAfter("T") else "00:00"
    val formattedDate = try {
        LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE)
            .format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
    } catch (_: Exception) { datePart }
    val hour = timePart.substringBefore(":").toIntOrNull() ?: 0
    val minute = timePart.substringAfter(":").toIntOrNull() ?: 0

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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = AppSpacing.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))

            IncomeExpenseToggle(selected = uiState.type, onSelect = viewModel::updateType)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showDatePicker = true }
                )
                Text(
                    text = timePart,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showTimePicker = true }
                )
            }

            CompactAccountChip(
                accountDisplays = uiState.accountDisplays,
                selectedId = uiState.accountId,
                onSelect = { id, currency ->
                    viewModel.updateAccountId(id)
                    viewModel.updateCurrency(currency)
                },
                onCreateAccount = viewModel::createAccount
            )

            CategoryDropdownWithAdd(
                categories = uiState.categories,
                selectedId = uiState.categoryId,
                type = uiState.type,
                onSelect = viewModel::updateCategoryId,
                onCreateCategory = viewModel::createCategory
            )

            // Name field (with autocomplete)
            ExposedDropdownMenuBox(
                expanded = uiState.showSuggestions,
                onExpandedChange = { if (!it) viewModel.dismissSuggestions() }
            ) {
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::updateDescription,
                    label = { Text("Name") },
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

            val amountError = uiState.amount.isNotEmpty() && uiState.amount.toDoubleOrNull().let { it == null || it <= 0 }
            OutlinedTextField(value = uiState.amount, onValueChange = viewModel::updateAmount,
                label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                prefix = { Text(uiState.currency) },
                isError = amountError,
                supportingText = if (amountError) {{ Text("Enter a valid amount") }} else null,
                trailingIcon = {
                    IconButton(onClick = { showCalculator = true }) {
                        Icon(Icons.Default.Calculate, contentDescription = "Calculator")
                    }
                })

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

            Spacer(modifier = Modifier.height(AppSpacing.lg))
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val initialMillis = try {
            LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val selectedDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                        viewModel.updateDate("${selectedDate}T${timePart}")
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Calculator dialog
    if (showCalculator) {
        CalculatorDialog(
            initialValue = uiState.amount,
            onDismiss = { showCalculator = false },
            onConfirm = { result ->
                viewModel.updateAmount(result)
                showCalculator = false
            }
        )
    }

    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = hour, initialMinute = minute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val newTime = "%02d:%02d".format(timePickerState.hour, timePickerState.minute)
                    viewModel.updateDate("${datePart}T${newTime}")
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}
