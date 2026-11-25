@file:OptIn(ExperimentalMaterial3Api::class)

package com.insituledger.app.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.insituledger.app.data.repository.bulkCategoryOptions
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.common.CategorySelectorRow
import com.insituledger.app.ui.common.DescriptionField
import com.insituledger.app.ui.common.FormActionBar
import com.insituledger.app.ui.theme.AppSpacing

/**
 * Rename and/or recategorise every selected entry at once. Each change is
 * opt-in (a switch), so a rename never touches categories and vice versa.
 * Back, swipe and scrim tap discard; nothing is written until Apply.
 */
@Composable
fun BulkEditSheet(
    state: BulkEditState,
    categories: List<Category>,
    onRenameEnabled: (Boolean) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSuggestion: (DescriptionSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    onCategoryEnabled: (Boolean) -> Unit,
    onCategory: (Long) -> Unit,
    onUpdateSchedules: (Boolean) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    // While Apply runs the sheet can't be swiped or tapped away: the result
    // (or its error) lands in it.
    val applying by rememberUpdatedState(state.isApplying)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || !applying }
    )
    val summary = state.summary
    val focusManager = LocalFocusManager.current
    val descriptionFocus = remember { FocusRequester() }
    val options = remember(categories, summary, state.syncEnabled) {
        bulkCategoryOptions(categories, summary, state.syncEnabled)
    }
    val count = state.ids.size
    val noun = if (count == 1) "entry" else "entries"

    LaunchedEffect(state.renameEnabled) {
        if (state.renameEnabled) descriptionFocus.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Matches FormActionBar's surface, so the action area doesn't read as a band.
        containerColor = MaterialTheme.colorScheme.surface
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
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    "Edit $count $noun",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .padding(horizontal = AppSpacing.screenH)
                        .semantics { heading() }
                )
                // Webapp mode against a server without the batch endpoints (or
                // one never synced with): nothing can be applied.
                summary.serverBlockedReason?.let {
                    Text(
                        "$it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = AppSpacing.screenH)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                state.notice?.let { Hint(it) }
                val gone = summary.requested - summary.liveCount
                if (gone > 0) {
                    Hint(
                        if (gone == 1) "1 selected entry no longer exists and will be skipped."
                        else "$gone selected entries no longer exist and will be skipped."
                    )
                }

                val editable = !state.isApplying && summary.liveCount > 0 && summary.serverBlockedReason == null
                OptionSwitchRow(
                    title = "Change name",
                    checked = state.renameEnabled,
                    enabled = editable,
                    onCheckedChange = onRenameEnabled
                )
                if (state.renameEnabled) {
                    DescriptionField(
                        value = state.description,
                        suggestions = state.suggestions,
                        showSuggestions = state.showSuggestions,
                        categories = categories,
                        error = state.descriptionError,
                        onValueChange = onDescriptionChange,
                        onSuggestion = onSuggestion,
                        onDismissSuggestions = onDismissSuggestions,
                        focusRequester = descriptionFocus,
                        onNext = { focusManager.clearFocus() },
                        modifier = Modifier.padding(horizontal = AppSpacing.screenH)
                    )
                    Hint("Replaces the whole name of every selected entry.")
                }

                val blocked = summary.categoryBlockedReason
                OptionSwitchRow(
                    title = "Change category",
                    checked = state.categoryEnabled && blocked == null,
                    enabled = editable && blocked == null,
                    onCheckedChange = onCategoryEnabled
                )
                if (blocked != null) {
                    Hint(blocked)
                } else if (state.categoryEnabled) {
                    val type = summary.type
                    if (type != null) {
                        CategorySelectorRow(
                            categories = options,
                            selectedId = state.categoryId,
                            type = type,
                            onSelect = onCategory,
                            // A category made here couldn't be sent until it syncs.
                            onCreateCategory = null,
                            enabled = !state.isApplying
                        )
                    }
                    if (options.isEmpty()) {
                        Hint(
                            if (state.syncEnabled) "No synced ${summary.type ?: ""} categories to choose from yet."
                            else "No ${summary.type ?: ""} categories to choose from."
                        )
                    }
                }

                val linked = summary.linkedScheduleIds.size
                if (linked > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AppSpacing.rowMinHeight)
                            .toggleable(
                                value = state.updateSchedules,
                                enabled = !state.isApplying,
                                role = Role.Checkbox,
                                onValueChange = onUpdateSchedules
                            )
                            .padding(horizontal = AppSpacing.screenH),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = state.updateSchedules, onCheckedChange = null, enabled = !state.isApplying)
                        Spacer(Modifier.size(AppSpacing.md))
                        Column(Modifier.weight(1f).padding(vertical = AppSpacing.sm)) {
                            Text(
                                if (linked == 1) "Also update the linked recurring schedule"
                                else "Also update $linked linked recurring schedules",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Future entries get the same change. Amounts and dates stay as they are.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                (state.scheduleConflict ?: state.error)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(horizontal = AppSpacing.screenH)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                Spacer(Modifier.size(AppSpacing.sm))
            }

            FormActionBar(
                label = "Apply to $count $noun",
                onClick = onApply,
                loading = state.isApplying,
                enabled = state.canApply
            )
        }
    }
}

@Composable
private fun OptionSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = AppSpacing.screenH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = AppSpacing.screenH)
    )
}
