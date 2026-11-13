package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.insituledger.app.domain.model.Category
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.amountEntry
import com.insituledger.app.ui.transactions.DescriptionSuggestion

// Form fields shared by the transaction and scheduled forms.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescriptionField(
    value: String,
    suggestions: List<DescriptionSuggestion>,
    showSuggestions: Boolean,
    categories: List<Category>,
    error: String?,
    onValueChange: (String) -> Unit,
    onSuggestion: (DescriptionSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    focusRequester: FocusRequester,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExposedDropdownMenuBox(
        expanded = showSuggestions,
        onExpandedChange = { if (!it) onDismissSuggestions() },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Description") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { onDismissSuggestions(); onNext() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .menuAnchor(MenuAnchorType.PrimaryEditable)
        )
        if (suggestions.isNotEmpty()) {
            ExposedDropdownMenu(expanded = showSuggestions, onDismissRequest = onDismissSuggestions) {
                suggestions.forEach { suggestion ->
                    val catName = categories.find { it.id == suggestion.categoryId }?.name
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    suggestion.description,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (catName != null) {
                                    Spacer(Modifier.size(AppSpacing.sm))
                                    Text(
                                        catName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        },
                        onClick = { onSuggestion(suggestion); onNext() }
                    )
                }
            }
        }
    }
}

@Composable
fun AmountField(
    amount: String,
    currency: String,
    error: String?,
    onAmountChange: (String) -> Unit,
    onCalculator: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = amount,
        onValueChange = onAmountChange,
        label = { Text("Amount") },
        placeholder = { Text("0.00", style = MaterialTheme.typography.amountEntry) },
        prefix = {
            Text(
                "$currency  ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        textStyle = MaterialTheme.typography.amountEntry,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = onCalculator) {
                Icon(Icons.Outlined.Calculate, contentDescription = "Open calculator")
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun NoteField(note: String, error: String?, onNoteChange: (String) -> Unit, pad: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(start = pad, end = pad),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppSpacing.lg)
        )
        TextField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = { Text("Add note") },
            minLines = 1,
            maxLines = 6,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.weight(1f)
        )
    }
}
