@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.util.EndMode
import com.insituledger.app.util.Freq
import com.insituledger.app.util.RecurrenceDraft
import com.insituledger.app.util.Rrule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Recurrence controls shared by the inline schedule sheet and the Scheduled
 * form: frequency chips, an optional custom interval, the end condition
 * (revealed progressively), and a one-line preview of upcoming dates.
 */
@Composable
fun RecurrenceEditor(
    draft: RecurrenceDraft,
    onDraftChange: ((RecurrenceDraft) -> RecurrenceDraft) -> Unit,
    start: LocalDateTime,
    upcoming: List<LocalDateTime>,
    modifier: Modifier = Modifier,
    alreadyCreated: Int? = null
) {
    val locale = Locale.getDefault()
    var pickEndDate by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        EditorLabel("Repeats")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
            Rrule.PRESETS.forEach { preset ->
                FrequencyChip(preset.label, draft.presetKey == preset.key) {
                    onDraftChange { it.copy(presetKey = preset.key) }
                }
            }
            FrequencyChip("Custom", draft.isCustom) { onDraftChange { it.copy(presetKey = Rrule.CUSTOM_KEY) } }
        }

        if (draft.isCustom) {
            CustomIntervalRow(draft, onDraftChange)
        }

        EditorLabel("Ends")
        val endOptions = listOf(EndMode.NEVER to "Never", EndMode.COUNT to "After", EndMode.DATE to "On date")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            endOptions.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = draft.endMode == mode,
                    onClick = { onDraftChange { it.copy(endMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index, endOptions.size),
                    icon = {},
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text(label, maxLines = 1) }
            }
        }
        when (draft.endMode) {
            EndMode.COUNT -> OutlinedTextField(
                value = draft.count,
                onValueChange = { v -> onDraftChange { it.copy(count = RecurrenceDraft.sanitizeNumber(v)) } },
                label = { Text("Number of times") },
                suffix = { Text("times") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                supportingText = alreadyCreated?.takeIf { it > 0 }?.let { n ->
                    { Text("$n already created") }
                },
                modifier = Modifier.fillMaxWidth()
            )
            EndMode.DATE -> {
                val end = draft.endDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                OutlinedButton(
                    onClick = { pickEndDate = true },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.Event, contentDescription = null)
                    Text(
                        end?.let { "Until " + it.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)) } ?: "Pick end date",
                        modifier = Modifier.weight(1f).padding(start = AppSpacing.sm)
                    )
                }
            }
            EndMode.NEVER -> Unit
        }

        PreviewLine(draft, start, upcoming, locale)
    }

    if (pickEndDate) {
        val initial = draft.endDate.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: start.toLocalDate().plusMonths(6)
        DatePickerDialogHost(
            initial = initial,
            minDate = start.toLocalDate(),
            onPick = { d -> onDraftChange { it.copy(endDate = d.toString()) } },
            onDismiss = { pickEndDate = false }
        )
    }
}

@Composable
private fun EditorLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun FrequencyChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer)
    )
}

@Composable
private fun CustomIntervalRow(
    draft: RecurrenceDraft,
    onDraftChange: ((RecurrenceDraft) -> RecurrenceDraft) -> Unit
) {
    var unitMenu by remember { mutableStateOf(false) }
    val n = draft.customInterval.toIntOrNull() ?: 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
        OutlinedTextField(
            value = draft.customInterval,
            onValueChange = { v -> onDraftChange { it.copy(customInterval = RecurrenceDraft.sanitizeNumber(v)) } },
            label = { Text("Every") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.width(112.dp)
        )
        ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = it }, modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = Rrule.unitLabel(draft.customUnit, n).replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                label = { Text("Unit") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                Freq.entries.forEach { unit ->
                    DropdownMenuItem(
                        text = { Text(Rrule.unitLabel(unit, n).replaceFirstChar { it.uppercase() }) },
                        onClick = { onDraftChange { it.copy(customUnit = unit) }; unitMenu = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewLine(draft: RecurrenceDraft, start: LocalDateTime, upcoming: List<LocalDateTime>, locale: Locale) {
    val dayFmt = DateTimeFormatter.ofPattern("d MMM", locale)
    val (freq, _) = draft.freqInterval()
    // FREQ=DAILY with a count of 1 is how one-time schedules are stored.
    if (draft.endMode == EndMode.COUNT && draft.count == "1") {
        Text(
            "Runs once · " + start.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val headline = buildString {
        append(draft.summary(start, locale).substringBefore(" · "))
        draft.anchorHint(start, locale).takeIf { it.isNotEmpty() }?.let { append(" ").append(it) }
        if (upcoming.isNotEmpty()) {
            append(" · next ")
            append(upcoming.joinToString(", ") { it.format(dayFmt) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if ((freq == Freq.MONTHLY || freq == Freq.YEARLY) && start.dayOfMonth > 28) {
            Text(
                "Months without day ${start.dayOfMonth} use their last day, and later dates keep that day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
