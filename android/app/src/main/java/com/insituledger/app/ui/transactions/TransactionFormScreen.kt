package com.insituledger.app.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.R
import com.insituledger.app.ui.common.AccountSelectorRow
import com.insituledger.app.ui.common.CalculatorDialog
import com.insituledger.app.ui.common.AmountField
import com.insituledger.app.ui.common.CategorySelectorRow
import com.insituledger.app.ui.common.DescriptionField
import com.insituledger.app.ui.common.NoteField
import com.insituledger.app.ui.common.DatePickerDialogHost
import com.insituledger.app.ui.common.FormActionBar
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.TimePickerDialogHost
import com.insituledger.app.ui.common.TypeToggle
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.amountEntry
import com.insituledger.app.util.DateTimeUtil
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionFormScreen(
    onBack: () -> Unit,
    onSaved: (message: String, offerScheduled: Boolean) -> Unit = { _, _ -> onBack() },
    viewModel: TransactionFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val descriptionFocus = remember { FocusRequester() }
    val amountFocus = remember { FocusRequester() }
    var didAutoFocus by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showCalculator by rememberSaveable { mutableStateOf(false) }
    val pad = LocalContentPadding.current
    val isEdit = uiState.id != null

    // Re-evaluate the primary action every minute: a one-time schedule set a
    // minute ahead must not keep promising "Schedule" once it has passed.
    val now by produceState(viewModel.now()) {
        while (true) {
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
            value = viewModel.now()
        }
    }
    val mode = formMode(uiState, now)

    LaunchedEffect(uiState.outcome) {
        val outcome = uiState.outcome ?: return@LaunchedEffect
        onSaved(
            outcome.message,
            outcome is SaveOutcome.ScheduledOnce || outcome is SaveOutcome.ScheduledRecurring
        )
    }

    LaunchedEffect(uiState.isLoading) {
        if (!didAutoFocus && !uiState.isLoading && !isEdit) {
            didAutoFocus = true
            descriptionFocus.requestFocus()
            keyboard?.show()
        }
    }

    val title = when {
        isEdit -> "Edit transaction"
        uiState.type == "income" -> "New income"
        else -> "New expense"
    }
    val isScheduling = mode == FormMode.SCHEDULE_ONCE || mode == FormMode.SCHEDULE_RECURRING ||
        mode == FormMode.ADD_AND_REPEAT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        bottomBar = {
            if (!uiState.isLoading) {
                FormActionBar(
                    label = mode.primaryLabel(uiState.type),
                    icon = if (isScheduling) null else Icons.Default.Check,
                    loading = uiState.isSaving,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.save()
                    }
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
                focusRequester = descriptionFocus,
                onNext = { amountFocus.requestFocus() },
                modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.lg)
            )

            AmountField(
                amount = uiState.amount,
                currency = uiState.currency,
                error = uiState.amountError,
                onAmountChange = viewModel::updateAmount,
                onCalculator = { showCalculator = true },
                onDone = { focusManager.clearFocus(); keyboard?.hide() },
                modifier = Modifier
                    .padding(start = pad, end = pad, top = AppSpacing.md)
                    .focusRequester(amountFocus)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = AppSpacing.lg)
            )

            val missing = uiState.error == TransactionFormViewModel.MSG_REQUIRED
            CategorySelectorRow(
                categories = uiState.categories,
                selectedId = uiState.categoryId,
                type = uiState.type,
                onSelect = viewModel::updateCategoryId,
                onCreateCategory = viewModel::createCategory,
                isError = missing && uiState.categoryId == null,
                supporting = if (missing && uiState.categoryId == null) "Choose a category" else null
            )
            AccountSelectorRow(
                accountDisplays = uiState.accountDisplays,
                selectedId = uiState.accountId,
                onSelect = { id, currency ->
                    viewModel.updateAccountId(id)
                    viewModel.updateCurrency(currency)
                },
                onCreateAccount = viewModel::createAccount,
                isError = missing && uiState.accountId == null
            )

            WhenRow(
                date = uiState.date,
                pad = pad,
                scheduleActive = isScheduling,
                showScheduleButton = !isEdit,
                onDateClick = {
                    if (uiState.recurrence != null) viewModel.openScheduleSheet() else showDatePicker = true
                },
                onTimeClick = {
                    if (uiState.recurrence != null) viewModel.openScheduleSheet() else showTimePicker = true
                },
                onScheduleClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    viewModel.openScheduleSheet()
                }
            )
            if (!isEdit && isScheduling) {
                ScheduleSummary(
                    uiState = uiState,
                    mode = mode,
                    now = now,
                    pad = pad,
                    onEdit = { keyboard?.hide(); viewModel.openScheduleSheet() },
                    onRemove = viewModel::removeSchedule
                )
            }
            if (uiState.dstAdjusted) {
                InsetNote("Adjusted for daylight saving", pad)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            NoteField(
                note = uiState.note,
                error = uiState.noteError,
                onNoteChange = viewModel::updateNote,
                pad = pad
            )

            val showGeneralError = uiState.error != null &&
                (!missing || (uiState.categoryId != null && uiState.accountId != null))
            if (showGeneralError) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pad, vertical = AppSpacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        uiState.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.canAddNow) {
                        TextButton(onClick = viewModel::addNow) { Text("Add now") }
                    }
                }
            }
        }
    }

    val wallDate = DateTimeUtil.parseFormDateOrNull(uiState.date)?.toLocalDateTime()
    if (showDatePicker) {
        DatePickerDialogHost(
            initial = wallDate?.toLocalDate() ?: LocalDate.now(),
            onPick = viewModel::setDate,
            onDismiss = { showDatePicker = false }
        )
    }
    if (showTimePicker) {
        TimePickerDialogHost(
            initial = wallDate?.toLocalTime() ?: LocalTime.now(),
            onPick = viewModel::setTime,
            onDismiss = { showTimePicker = false }
        )
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
    uiState.scheduleSheet?.let { sheet ->
        ScheduleSheet(
            state = sheet,
            today = now.toLocalDate(),
            onModeChange = viewModel::setSheetMode,
            onDateChange = viewModel::setSheetDate,
            onTimeChange = viewModel::setSheetTime,
            onQuickPick = viewModel::applyQuickPick,
            onDraftChange = viewModel::updateSheetDraft,
            onApply = viewModel::applySchedule,
            onDismiss = viewModel::dismissScheduleSheet,
            onRemove = viewModel::removeSchedule
        )
    }
}

@Composable
private fun WhenRow(
    date: String,
    pad: Dp,
    scheduleActive: Boolean,
    showScheduleButton: Boolean,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onScheduleClick: () -> Unit
) {
    val locale = Locale.getDefault()
    val wall = DateTimeUtil.parseFormDateOrNull(date)?.toLocalDateTime()
    val dateText = wall?.toLocalDate()?.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)) ?: date.take(10)
    val timeText = wall?.toLocalTime()?.format(DateTimeFormatter.ofPattern("HH:mm", locale)) ?: ""
    val valueColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(start = pad, end = pad - AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(AppSpacing.xs))
        TextButton(onClick = onDateClick, colors = valueColors) {
            Text(dateText, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
        TextButton(onClick = onTimeClick, colors = valueColors) {
            Text(timeText, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (showScheduleButton) {
            val description = if (scheduleActive) "Edit schedule" else "Schedule"
            if (scheduleActive) {
                FilledTonalIconButton(onClick = onScheduleClick) {
                    Icon(painterResource(R.drawable.ic_calendar_clock), contentDescription = description)
                }
            } else {
                IconButton(onClick = onScheduleClick) {
                    Icon(
                        painterResource(R.drawable.ic_calendar_clock),
                        contentDescription = description,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduleSummary(
    uiState: TransactionFormUiState,
    mode: FormMode,
    now: OffsetDateTime,
    pad: Dp,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    val locale = Locale.getDefault()
    val start = DateTimeUtil.parseFormDateOrNull(uiState.date)?.toLocalDateTime() ?: return
    val recurrence = uiState.recurrence
    val text = if (recurrence != null) {
        val summary = recurrence.summary(start, locale)
        val head = summary.substringBefore(" · ").replaceFirstChar { it.lowercase(locale) }
        val tail = summary.substringAfter(" · ", "").takeIf { it.isNotEmpty() }
        val anchor = recurrence.anchorHint(start, locale).takeIf { it.isNotEmpty() }
        val firstPart = if (mode == FormMode.ADD_AND_REPEAT) {
            val n = recurrence.pastOccurrences(start, now.toLocalDateTime(), 367).size
            if (n <= 1) "first entry added on save" else "adds $n past entries"
        } else "starts ${start.format(DateTimeFormatter.ofPattern("d MMM", locale))}"
        listOfNotNull("Repeats $head" + (anchor?.let { " $it" } ?: ""), tail, firstPart).joinToString(" · ")
    } else {
        "One-time · added ${start.format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' HH:mm", locale))}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Edit schedule", onClick = onEdit)
            .padding(start = pad + 24.dp + AppSpacing.lg, end = pad - AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(vertical = AppSpacing.xs)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = "Remove schedule", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InsetNote(text: String, pad: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = pad + 24.dp + AppSpacing.lg, end = pad, bottom = AppSpacing.sm)
    )
}
