package com.insituledger.app.ui.scheduled

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.AccountSelectorRow
import com.insituledger.app.ui.common.AmountField
import com.insituledger.app.ui.common.CalculatorDialog
import com.insituledger.app.ui.common.CategorySelectorRow
import com.insituledger.app.ui.common.ConfirmDialog
import com.insituledger.app.ui.common.DatePickerDialogHost
import com.insituledger.app.ui.common.DescriptionField
import com.insituledger.app.ui.common.FormActionBar
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.NoteField
import com.insituledger.app.ui.common.RecurrenceEditor
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.TimePickerDialogHost
import com.insituledger.app.ui.common.TypeToggle
import com.insituledger.app.ui.theme.AppSpacing
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledFormScreen(
    onBack: () -> Unit,
    onSaved: (message: String) -> Unit = { onBack() },
    viewModel: ScheduledFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val amountFocus = remember { FocusRequester() }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val pad = LocalContentPadding.current
    val isEdit = uiState.id != null
    val locale = Locale.getDefault()

    LaunchedEffect(uiState.saved, uiState.deleted) {
        if (uiState.saved || uiState.deleted) {
            onSaved(uiState.savedMessage ?: if (isEdit) "Schedule updated" else "Schedule created")
        }
    }

    val startDate = runCatching { LocalDate.parse(uiState.nextDate) }.getOrNull() ?: LocalDate.now()
    val startTime = runCatching { LocalTime.parse(uiState.nextTime) }.getOrNull() ?: LocalTime.of(9, 0)
    val start = LocalDateTime.of(startDate, startTime)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit schedule" else "New schedule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isEdit) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete schedule")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!uiState.isLoading) {
                FormActionBar(
                    label = if (isEdit) "Save changes" else "Create schedule",
                    loading = uiState.isSaving,
                    onClick = viewModel::save
                )
            }
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.lg)
        ) {
            TypeToggle(
                selected = uiState.type,
                onSelect = viewModel::updateType,
                modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.sm)
            )
            DescriptionField(
                value = uiState.description,
                suggestions = uiState.suggestions,
                showSuggestions = uiState.showSuggestions,
                categories = uiState.categories,
                error = uiState.descriptionError,
                onValueChange = viewModel::updateDescription,
                onSuggestion = viewModel::selectSuggestion,
                onDismissSuggestions = viewModel::dismissSuggestions,
                focusRequester = remember { FocusRequester() },
                onNext = { amountFocus.requestFocus() },
                modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.lg)
            )
            AmountField(
                amount = uiState.amount,
                currency = uiState.currency,
                error = null,
                onAmountChange = viewModel::updateAmount,
                onCalculator = { showCalculator = true },
                onDone = { focusManager.clearFocus(); keyboard?.hide() },
                modifier = Modifier
                    .padding(start = pad, end = pad, top = AppSpacing.md)
                    .focusRequester(amountFocus)
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = AppSpacing.lg))
            CategorySelectorRow(
                categories = uiState.categories,
                selectedId = uiState.categoryId,
                type = uiState.type,
                onSelect = viewModel::updateCategoryId,
                onCreateCategory = viewModel::createCategory
            )
            AccountSelectorRow(
                accountDisplays = uiState.accountDisplays,
                selectedId = uiState.accountId,
                onSelect = { id, currency ->
                    viewModel.updateAccountId(id)
                    viewModel.updateCurrency(currency)
                },
                onCreateAccount = viewModel::createAccount
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            SectionHeader("When", horizontalPadding = pad, topPadding = AppSpacing.md)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppSpacing.rowMinHeight)
                    .padding(start = pad, end = pad),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(AppSpacing.xs))
                val valueColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { showDatePicker = true }, colors = valueColors) {
                    Text(
                        (if (isEdit) "Next " else "Starts ") +
                            startDate.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                TextButton(onClick = { showTimePicker = true }, colors = valueColors) {
                    Text(startTime.format(DateTimeFormatter.ofPattern("HH:mm", locale)), style = MaterialTheme.typography.bodyLarge)
                }
            }
            RecurrenceEditor(
                draft = uiState.recurrence,
                onDraftChange = viewModel::updateRecurrence,
                start = start,
                upcoming = uiState.recurrence.upcoming(start, 3, after = LocalDateTime.now()),
                alreadyCreated = if (isEdit) uiState.occurrenceCount else null,
                modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.sm, bottom = AppSpacing.lg)
            )

            if (isEdit) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = uiState.active, role = Role.Switch, onValueChange = viewModel::updateActive)
                        .heightIn(min = 72.dp)
                        .padding(horizontal = pad),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Active", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (uiState.active) "Entries are created automatically" else "Paused: no entries are created",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = uiState.active, onCheckedChange = null)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NoteField(note = uiState.note, error = uiState.noteError, onNoteChange = viewModel::updateNote, pad = pad)

            uiState.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(horizontal = pad, vertical = AppSpacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialogHost(
            initial = startDate,
            onPick = { viewModel.updateNextDate(it.toString()) },
            onDismiss = { showDatePicker = false }
        )
    }
    if (showTimePicker) {
        TimePickerDialogHost(
            initial = startTime,
            onPick = { viewModel.updateNextTime(it.format(DateTimeFormatter.ofPattern("HH:mm"))) },
            onDismiss = { showTimePicker = false }
        )
    }
    if (showCalculator) {
        CalculatorDialog(
            initialValue = uiState.amount,
            onDismiss = { showCalculator = false },
            onConfirm = { viewModel.updateAmount(it); showCalculator = false }
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this schedule?",
            text = "Future entries won't be created. Entries already recorded stay as they are.",
            confirmLabel = "Delete",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
