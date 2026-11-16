package com.insituledger.app.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
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
import com.insituledger.app.data.remote.dto.SharedAccessDto
import com.insituledger.app.domain.model.Account
import com.insituledger.app.ui.common.ConfirmDialog
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.ListRow
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.RowAction
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.common.SelectorRow
import com.insituledger.app.ui.theme.AppSpacing

private val SharedAccessDto.displayName: String get() = guestName.ifBlank { guestEmail }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: SharedViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val focusManager = LocalFocusManager.current
    val pad = LocalContentPadding.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var removeTarget by remember { mutableStateOf<SharedAccessDto?>(null) }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbar.show(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Co-owners") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        // Full-screen loading only on the first load; reloads after add/remove
        // keep the content in place.
        val firstLoad = uiState.isLoading && uiState.accounts.isEmpty() && uiState.accesses.isEmpty()
        when {
            firstLoad -> LoadingIndicator(modifier = Modifier.padding(padding))
            !uiState.isConnected -> EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = "Connect to a server first",
                message = "Co-owners can read and edit every transaction in an account you share. " +
                    "Sharing needs a connection to an InSitu Ledger server.",
                actionLabel = "Open settings",
                onAction = onOpenSettings,
                modifier = Modifier.padding(padding)
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = AppSpacing.xl)
            ) {
                item(key = "intro") {
                    Text(
                        "A co-owner can view and edit every transaction in the account you share with them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = pad, end = pad, top = AppSpacing.sm)
                    )
                }

                item(key = "add-header") {
                    SectionHeader("Add a co-owner", horizontalPadding = pad)
                }
                item(key = "add-form") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = uiState.email,
                            onValueChange = viewModel::updateEmail,
                            label = { Text("Email") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done,
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = pad)
                        )

                        ShareAccountSelector(
                            accounts = uiState.accounts,
                            selectedId = uiState.selectedAccountId,
                            onSelect = viewModel::updateAccount,
                            pad = pad,
                            modifier = Modifier.padding(top = AppSpacing.xs)
                        )

                        uiState.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .padding(horizontal = pad, vertical = AppSpacing.xs)
                                    .semantics { liveRegion = LiveRegionMode.Polite }
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = pad, end = pad, top = AppSpacing.sm),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    focusManager.clearFocus()
                                    viewModel.add()
                                },
                                enabled = !uiState.isSaving && uiState.email.isNotBlank() && uiState.selectedAccountId != null,
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                if (uiState.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.size(AppSpacing.sm))
                                }
                                Text("Add co-owner")
                            }
                        }
                    }
                }

                item(key = "current-header") {
                    SectionHeader("Current co-owners", horizontalPadding = pad)
                }
                if (uiState.accesses.isEmpty()) {
                    item(key = "current-empty") {
                        Text(
                            "No one else has access to your accounts yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = pad, vertical = AppSpacing.sm)
                        )
                    }
                } else {
                    items(uiState.accesses, key = { it.id }) { access ->
                        ListRow(
                            headline = access.displayName,
                            supporting = "${access.accountName} · ${access.guestEmail}",
                            actions = listOf(
                                RowAction("Remove", destructive = true) { removeTarget = access }
                            ),
                            horizontalPadding = pad,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    removeTarget?.let { target ->
        ConfirmDialog(
            title = "Remove ${target.displayName}'s access to ${target.accountName}?",
            text = "${target.displayName} will no longer see or edit ${target.accountName}. You can share it again later.",
            confirmLabel = "Remove",
            onConfirm = {
                removeTarget = null
                viewModel.delete(target.id)
            },
            onDismiss = { removeTarget = null }
        )
    }
}

/** Account picker row for the add form; only the accounts this user can share. */
@Composable
private fun ShareAccountSelector(
    accounts: List<Account>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    pad: Dp,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = accounts.find { it.id == selectedId }

    Box(modifier) {
        SelectorRow(
            icon = Icons.Outlined.AccountBalanceWallet,
            label = "Account",
            value = selected?.let { "${it.name} · ${it.currency}" },
            placeholder = if (accounts.isEmpty()) "No accounts to share" else "Choose an account",
            onClick = { if (accounts.isNotEmpty()) expanded = true },
            // SelectorRow pads itself by the phone margin; line it up with the
            // wider content padding on large screens.
            modifier = Modifier.padding(horizontal = (pad - AppSpacing.screenH).coerceAtLeast(0.dp))
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            accounts.forEach { account ->
                val isSelected = account.id == selectedId
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                account.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                account.currency,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                    onClick = {
                        onSelect(account.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
