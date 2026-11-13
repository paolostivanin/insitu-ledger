@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.insituledger.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.common.DatePickerDialogHost
import com.insituledger.app.ui.common.RecurrenceEditor
import com.insituledger.app.ui.common.TimePickerDialogHost
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.util.RecurrenceDraft
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Inline scheduling for a new transaction. Edits a draft held by the
 * ViewModel; nothing on the form changes until Done. Back, swipe, scrim tap
 * and Cancel discard the draft only.
 */
@Composable
fun ScheduleSheet(
    state: ScheduleSheetState,
    today: LocalDate,
    onModeChange: (ScheduleMode) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onQuickPick: (QuickPick) -> Unit,
    onDraftChange: ((RecurrenceDraft) -> RecurrenceDraft) -> Unit,
    onApply: () -> Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val locale = Locale.getDefault()

    // Animate out before handing control back, so the sheet doesn't vanish.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
            ) {
                Text(
                    "Schedule",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )

                val modes = listOf(ScheduleMode.ONE_TIME to "One-time", ScheduleMode.RECURRING to "Recurring")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = {
                                if (mode == state.mode) return@SegmentedButton
                                onModeChange(mode)
                                // A one-time entry must be in the future: start from tomorrow.
                                if (mode == ScheduleMode.ONE_TIME &&
                                    !LocalDateTime.of(state.date, state.time).isAfter(LocalDateTime.now())
                                ) onQuickPick(QuickPick.TOMORROW)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            icon = {},
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(label, maxLines = 1) }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        if (state.mode == ScheduleMode.ONE_TIME) "On" else "Starts",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        OutlinedButton(
                            onClick = { pickDate = true },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(AppSpacing.sm))
                            Text(state.date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)), maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { pickTime = true },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(AppSpacing.sm))
                            Text(state.time.format(DateTimeFormatter.ofPattern("HH:mm", locale)))
                        }
                    }
                    if (state.dstAdjusted) {
                        Hint("That time doesn't exist on this day (clocks go forward); it will be saved an hour later.")
                    }
                }

                if (state.mode == ScheduleMode.ONE_TIME) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        SuggestionChip(onClick = { onQuickPick(QuickPick.TOMORROW) }, label = { Text("Tomorrow") })
                        SuggestionChip(onClick = { onQuickPick(QuickPick.IN_A_WEEK) }, label = { Text("In a week") })
                        SuggestionChip(onClick = { onQuickPick(QuickPick.IN_A_MONTH) }, label = { Text("In a month") })
                    }
                    Hint("Added automatically at that time. Nothing is recorded until then.")
                } else {
                    RecurrenceEditor(
                        draft = state.draft,
                        onDraftChange = onDraftChange,
                        start = LocalDateTime.of(state.date, state.time),
                        upcoming = state.upcoming
                    )
                    if (state.pastEntries.isNotEmpty()) {
                        val dayFmt = DateTimeFormatter.ofPattern("d MMM", locale)
                        val n = state.pastEntries.size
                        val list = state.pastEntries.take(3).joinToString(", ") { it.format(dayFmt) } +
                            if (n > 3) ", …" else ""
                        val text = (if (n == 1) "Adds 1 entry now ($list)" else "Adds $n past entries now ($list)") +
                            if (state.endsDuringCatchUp) ", then ends." else "."
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Spacer(Modifier.size(AppSpacing.xs))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.isEditingExisting) {
                    TextButton(
                        onClick = { closeThen(onRemove) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Remove") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { closeThen(onDismiss) }) { Text("Cancel") }
                Spacer(Modifier.size(AppSpacing.sm))
                Button(
                    onClick = {
                        // Animate out, then apply; if the clock moved and the draft
                        // no longer validates, bring the sheet back with its error.
                        closeThen { if (!onApply()) scope.launch { sheetState.show() } }
                    },
                    enabled = state.error == null
                ) { Text("Done") }
            }
        }
    }

    if (pickDate) {
        DatePickerDialogHost(
            initial = state.date,
            // One-time entries are in the future; recurring starts may back-fill.
            minDate = if (state.mode == ScheduleMode.ONE_TIME) today else null,
            onPick = onDateChange,
            onDismiss = { pickDate = false }
        )
    }
    if (pickTime) {
        TimePickerDialogHost(initial = state.time, onPick = onTimeChange, onDismiss = { pickTime = false })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
