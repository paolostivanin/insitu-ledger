package com.insituledger.app.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.BalanceText
import com.insituledger.app.ui.common.CurrencyField
import com.insituledger.app.ui.common.FormActionBar
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.currencySymbolFor
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.amountEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountFormScreen(
    onBack: () -> Unit,
    onSaved: (message: String) -> Unit = { onBack() },
    viewModel: AccountFormViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val focusManager = LocalFocusManager.current
    val pad = LocalContentPadding.current
    val isEdit = uiState.id != null

    LaunchedEffect(uiState.saved) { if (uiState.saved) onSaved(if (isEdit) "Account updated" else "Account created") }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit account" else "New account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (!uiState.isLoading) {
                FormActionBar(
                    label = if (isEdit) "Save changes" else "Create account",
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
                .padding(horizontal = pad, vertical = AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::updateName,
                label = { Text("Name") },
                singleLine = true,
                isError = uiState.nameError != null,
                supportingText = uiState.nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (isEdit) ImeAction.Done else ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth()
            )

            CurrencyField(
                value = uiState.currency,
                onValueChange = viewModel::updateCurrency,
                modifier = Modifier.fillMaxWidth()
            )

            if (!isEdit) {
                OutlinedTextField(
                    value = uiState.balance,
                    onValueChange = viewModel::updateBalance,
                    label = { Text("Opening balance") },
                    placeholder = { Text("0.00", style = MaterialTheme.typography.amountEntry) },
                    prefix = {
                        Text(
                            "${uiState.currency}  ",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    textStyle = MaterialTheme.typography.amountEntry,
                    singleLine = true,
                    isError = uiState.balanceError != null,
                    supportingText = {
                        Text(uiState.balanceError ?: "Set once when the account is created. Leave empty for 0.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val balance = uiState.currentBalance
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AppSpacing.sm)
                        .semantics(mergeDescendants = true) {}
                ) {
                    Text(
                        "Current balance",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (balance != null) {
                        BalanceText(
                            value = balance,
                            style = MaterialTheme.typography.amountEntry,
                            symbol = currencySymbolFor(uiState.currency, LocalCurrencySymbol.current, uiState.primaryCurrency)
                        )
                    }
                    Text(
                        "Calculated from your transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            uiState.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}
