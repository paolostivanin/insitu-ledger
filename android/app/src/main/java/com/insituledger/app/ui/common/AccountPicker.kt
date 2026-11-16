@file:OptIn(ExperimentalMaterial3Api::class)

package com.insituledger.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.transactions.AccountDisplay

/** Currencies offered when creating an account (user can still type any ISO code in the account form). */
val CommonCurrencies = listOf(
    "EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK",
    "PLN", "CZK", "HUF", "BRL", "CNY", "INR", "MXN", "ZAR"
)

/** Form row showing the selected account ("Checking · EUR") with a dropdown. */
@Composable
fun AccountSelectorRow(
    accountDisplays: List<AccountDisplay>,
    selectedId: Long?,
    onSelect: (id: Long, currency: String) -> Unit,
    onCreateAccount: (name: String, currency: String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    val selected = accountDisplays.find { it.account.id == selectedId }

    Box(modifier) {
        SelectorRow(
            icon = Icons.Outlined.AccountBalanceWallet,
            label = "Account",
            value = selected?.let { "${it.label} · ${it.account.currency}" },
            onClick = { expanded = true },
            isError = isError
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            accountDisplays.forEach { display ->
                val isSelected = display.account.id == selectedId
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                display.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                display.account.currency,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                    onClick = {
                        onSelect(display.account.id, display.account.currency)
                        expanded = false
                    }
                )
            }
            if (accountDisplays.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New account") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = { expanded = false; showCreate = true }
            )
        }
    }

    if (showCreate) {
        NewAccountDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, currency ->
                onCreateAccount(name, currency)
                showCreate = false
            }
        )
    }
}

/** The one "New account" dialog, shared by every picker. */
@Composable
fun NewAccountDialog(onDismiss: () -> Unit, onCreate: (name: String, currency: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("EUR") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                CurrencyField(value = currency, onValueChange = { currency = it })
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name.trim(), currency) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Currency picker field: common ISO codes in a dropdown. */
@Composable
fun CurrencyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember(value) { if (value.isNotBlank() && value !in CommonCurrencies) listOf(value) + CommonCurrencies else CommonCurrencies }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Currency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = { onValueChange(code); expanded = false }
                )
            }
        }
    }
}
