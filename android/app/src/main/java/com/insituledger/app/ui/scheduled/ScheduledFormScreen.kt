package com.insituledger.app.ui.scheduled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.AppCard
import com.insituledger.app.ui.common.CalculatorDialog
import com.insituledger.app.ui.common.CategoryDropdownWithAdd
import com.insituledger.app.ui.common.CompactAccountChip
import com.insituledger.app.ui.common.IncomeExpenseToggle
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalSnackbarHostState
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.InterFontFamily
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledFormScreen(
    onBack: () -> Unit,
    viewModel: ScheduledFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val nameFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var didAutoFocus by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) {
            scope.launch {
                snackbarHostState.showSnackbar(if (uiState.id != null) "Scheduled updated" else "Scheduled created")
            }
            onBack()
        }
    }

    LaunchedEffect(uiState.isLoading, uiState.id) {
        if (!didAutoFocus && !uiState.isLoading && uiState.id == null) {
            didAutoFocus = true
            nameFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showCalculator by remember { mutableStateOf(false) }

    val formattedNextDate = try {
        LocalDate.parse(uiState.nextDate, DateTimeFormatter.ISO_LOCAL_DATE)
            .format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    } catch (_: Exception) { uiState.nextDate }
    val nextHour = uiState.nextTime.substringBefore(":").toIntOrNull() ?: 9
    val nextMinute = uiState.nextTime.substringAfter(":").toIntOrNull() ?: 0

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
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!uiState.isSaving) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.save()
                    }
                },
                icon = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                },
                text = { Text(if (uiState.id != null) "Update" else "Create") }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingIndicator(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AppSpacing.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.xs))

            PlainCard {
                ExposedDropdownMenuBox(
                    expanded = uiState.showSuggestions,
                    onExpandedChange = { if (!it) viewModel.dismissSuggestions() }
                ) {
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = viewModel::updateDescription,
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(nameFocusRequester)
                            .menuAnchor(MenuAnchorType.PrimaryEditable)
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

                CategoryDropdownWithAdd(
                    categories = uiState.categories,
                    selectedId = uiState.categoryId,
                    type = uiState.type,
                    onSelect = viewModel::updateCategoryId,
                    onCreateCategory = viewModel::createCategory
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        text = formattedNextDate,
                        modifier = Modifier
                            .clickable { showDatePicker = true }
                            .padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = uiState.nextTime,
                        modifier = Modifier
                            .clickable { showTimePicker = true }
                            .padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "Type & Amount") {
                IncomeExpenseToggle(selected = uiState.type, onSelect = viewModel::updateType)
                AmountInput(
                    amount = uiState.amount,
                    currency = uiState.currency,
                    onAmountChange = viewModel::updateAmount,
                    onCalculatorClick = { showCalculator = true }
                )
            }

            SectionCard(title = "Account") {
                CompactAccountChip(
                    accountDisplays = uiState.accountDisplays,
                    selectedId = uiState.accountId,
                    onSelect = { id, currency ->
                        viewModel.updateAccountId(id)
                        viewModel.updateCurrency(currency)
                    },
                    onCreateAccount = viewModel::createAccount
                )
            }

            SectionCard(title = "Recurrence") {
                FrequencyDropdown(
                    selected = uiState.frequency,
                    onSelect = viewModel::updateFrequency
                )

                EndsSelector(
                    endMode = uiState.endMode,
                    onChange = viewModel::updateEndMode
                )

                when (uiState.endMode) {
                    "count" -> OutlinedTextField(
                        value = uiState.maxOccurrences,
                        onValueChange = viewModel::updateMaxOccurrences,
                        label = { Text("Stop after N occurrences") },
                        placeholder = { Text("e.g. 12") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    "date" -> {
                        val formattedEndDate = if (uiState.endDate.isBlank()) "Pick a date"
                        else try {
                            LocalDate.parse(uiState.endDate, DateTimeFormatter.ISO_LOCAL_DATE)
                                .format(DateTimeFormatter.ofPattern("d MMM yyyy"))
                        } catch (_: Exception) { uiState.endDate }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Ends on:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(
                                text = formattedEndDate,
                                modifier = Modifier
                                    .clickable { showEndDatePicker = true }
                                    .padding(vertical = 6.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (uiState.previewOccurrences.isNotEmpty()) {
                    Text(
                        text = "Next: " + uiState.previewOccurrences.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.id != null) {
                SectionCard(title = "Status") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Active",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = if (uiState.active) "This schedule is currently running"
                                else "Paused — no new transactions will be created",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = uiState.active, onCheckedChange = viewModel::updateActive)
                    }
                }
            }

            SectionCard(title = "Note") {
                OutlinedTextField(
                    value = uiState.note,
                    onValueChange = viewModel::updateNote,
                    label = { Text("Note (optional)") },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    supportingText = if (uiState.note.isNotEmpty()) {
                        { Text("${uiState.note.length}/2000") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            uiState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // FAB clearance: 56dp button + 16dp margin + breathing room
            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    if (showDatePicker) {
        val initialMillis = try {
            LocalDate.parse(uiState.nextDate, DateTimeFormatter.ISO_LOCAL_DATE)
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
                        viewModel.updateNextDate(selectedDate)
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

    if (showEndDatePicker) {
        val initialMillis = try {
            LocalDate.parse(uiState.endDate, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant().toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val endDateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    endDateState.selectedDateMillis?.let { millis ->
                        val selected = Instant.ofEpochMilli(millis)
                            .atZone(ZoneId.of("UTC"))
                            .toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                        viewModel.updateEndDate(selected)
                    }
                    showEndDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = endDateState)
        }
    }

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

    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(initialHour = nextHour, initialMinute = nextMinute)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Select Time") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val newTime = "%02d:%02d".format(timePickerState.hour, timePickerState.minute)
                    viewModel.updateNextTime(newTime)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencyDropdown(
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = ScheduledFormUiState.frequencyLabels[selected] ?: selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Frequency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ScheduledFormUiState.frequencyLabels.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onSelect(key)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndsSelector(
    endMode: String,
    onChange: (String) -> Unit
) {
    val options = listOf("never" to "Never", "count" to "After N", "date" to "On date")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (key, label) ->
            SegmentedButton(
                selected = endMode == key,
                onClick = { onChange(key) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = AppSpacing.sm, bottom = AppSpacing.xs)
        )
        AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                content = content
            )
        }
    }
}

@Composable
private fun PlainCard(content: @Composable ColumnScope.() -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmountInput(
    amount: String,
    currency: String,
    onAmountChange: (String) -> Unit,
    onCalculatorClick: () -> Unit
) {
    val amountError = amount.isNotEmpty() && amount.toDoubleOrNull().let { it == null || it <= 0 }
    OutlinedTextField(
        value = amount,
        onValueChange = onAmountChange,
        label = { Text("Amount") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            fontFeatureSettings = "tnum"
        ),
        prefix = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = currency,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        isError = amountError,
        supportingText = if (amountError) {{ Text("Enter a valid amount") }} else null,
        trailingIcon = {
            IconButton(onClick = onCalculatorClick) {
                Icon(Icons.Default.Calculate, contentDescription = "Calculator")
            }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
}
