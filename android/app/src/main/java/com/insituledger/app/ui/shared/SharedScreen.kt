package com.insituledger.app.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.theme.AppSpacing
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(
    onBack: () -> Unit,
    viewModel: SharedViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Co-owners") },
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

        if (!uiState.isConnected) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(AppSpacing.xxl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(AppSpacing.lg))
                Text(
                    "Server connection required",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                Text(
                    "Co-owning an account lets another user fully read and write transactions on it. This requires connecting to an InSitu Ledger server in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.cardPadding),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        Text("Add a co-owner", style = MaterialTheme.typography.titleSmall)

                        OutlinedTextField(
                            value = uiState.email,
                            onValueChange = viewModel::updateEmail,
                            label = { Text("Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        AccountPicker(
                            accounts = uiState.accounts,
                            selectedId = uiState.selectedAccountId,
                            onSelect = viewModel::updateAccount
                        )

                        uiState.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = viewModel::add,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isSaving && uiState.email.isNotBlank() && uiState.selectedAccountId != null
                        ) {
                            if (uiState.isSaving) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                            else Text("Add co-owner")
                        }
                    }
                }
            }

            if (uiState.accesses.isNotEmpty()) {
                item {
                    Text("Current co-owners", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = AppSpacing.xs))
                }
                items(uiState.accesses, key = { it.id }) { access ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(access.guestName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${access.accountName} · ${access.guestEmail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.delete(access.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPicker(
    accounts: List<Account>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.find { it.id == selectedId }
    val label = selected?.name ?: if (accounts.isEmpty()) "No accounts available" else "Pick an account"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (accounts.isNotEmpty()) expanded = it }
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { acct ->
                DropdownMenuItem(
                    text = { Text(acct.name) },
                    onClick = { onSelect(acct.id); expanded = false }
                )
            }
        }
    }
}
