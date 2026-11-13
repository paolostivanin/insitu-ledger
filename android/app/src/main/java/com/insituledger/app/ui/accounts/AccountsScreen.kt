package com.insituledger.app.ui.accounts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.domain.model.isOwnedBy
import com.insituledger.app.ui.common.AddFab
import com.insituledger.app.ui.common.BalanceText
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.ListRow
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.RowAction
import com.insituledger.app.ui.common.currencySymbolFor
import com.insituledger.app.ui.common.primaryCurrency
import com.insituledger.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pad = LocalContentPadding.current
    val preferredSymbol = LocalCurrencySymbol.current

    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("Delete “${target.name}”?") },
            text = {
                Text(
                    deleteError ?: "This also deletes all of its transactions and schedules. This can't be undone.",
                    color = if (deleteError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        deleting = true
                        scope.launch {
                            viewModel.delete(target.id).fold(
                                onSuccess = { deleteTarget = null; snackbar.show("Account deleted") },
                                onFailure = { deleteError = it.message ?: "Could not delete account" }
                            )
                            deleting = false
                        }
                    }
                ) { Text(if (deleting) "Deleting…" else "Delete") }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = { AddFab(onClick = onAddClick, contentDescription = "Add account") }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.accounts.isEmpty() -> EmptyState(
                icon = Icons.Outlined.AccountBalanceWallet,
                title = "No accounts yet",
                message = "Add an account to start recording transactions.",
                actionLabel = "Add account",
                onAction = onAddClick,
                modifier = Modifier.padding(padding)
            )
            else -> {
                val currentUserId = uiState.currentUserId
                val primary = primaryCurrency(uiState.accounts)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(top = AppSpacing.xs, bottom = AppSpacing.fabClearance)
                ) {
                    items(uiState.accounts, key = { it.id }) { account ->
                        val isOwn = account.isOwnedBy(currentUserId)
                        val sharing = when {
                            account.isShared && !isOwn && account.ownerName.isNotEmpty() -> "Shared by ${account.ownerName}"
                            account.isShared && isOwn -> "Shared with co-owners"
                            account.isShared -> "Shared"
                            else -> null
                        }
                        ListRow(
                            headline = account.name,
                            supporting = listOfNotNull(account.currency, sharing).joinToString(" · "),
                            trailing = {
                                BalanceText(
                                    value = account.balance,
                                    symbol = currencySymbolFor(account.currency, preferredSymbol, primary)
                                )
                            },
                            onClick = if (isOwn) ({ onEditClick(account.id) }) else null,
                            actions = if (isOwn) listOf(
                                RowAction("Edit") { onEditClick(account.id) },
                                RowAction("Delete", destructive = true) {
                                    deleteError = null
                                    deleteTarget = account
                                }
                            ) else emptyList(),
                            horizontalPadding = pad,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}
