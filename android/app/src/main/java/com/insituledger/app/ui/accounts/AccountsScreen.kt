package com.insituledger.app.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.domain.model.Account
import com.insituledger.app.ui.common.AppCard
import com.insituledger.app.ui.common.CurrencyFormatter
import com.insituledger.app.ui.common.EmptyState
import com.insituledger.app.ui.common.LoadingIndicator
import com.insituledger.app.ui.common.LocalCurrencySymbol
import com.insituledger.app.ui.common.LocalSnackbarHostState
import com.insituledger.app.ui.theme.AppSpacing
import com.insituledger.app.ui.theme.BrandGradients
import com.insituledger.app.ui.theme.TabularNumStyle
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
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            BrandFab(onClick = onAddClick, contentDescription = "Add Account")
        }
    ) { padding ->
        when {
            uiState.isLoading -> LoadingIndicator(modifier = Modifier.padding(padding))
            uiState.accounts.isEmpty() -> EmptyState(
                icon = Icons.Default.AccountBalanceWallet,
                title = "No accounts yet",
                message = "Add your first account to start tracking transactions.",
                actionLabel = "Add account",
                onAction = onAddClick,
                modifier = Modifier.padding(padding)
            )
            else -> {
                val currentUserId = uiState.currentUserId
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(
                        start = AppSpacing.screenPadding,
                        end = AppSpacing.screenPadding,
                        top = AppSpacing.sm,
                        bottom = AppSpacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    items(uiState.accounts, key = { it.id }) { account ->
                        val isOwn = currentUserId != null && account.userId == currentUserId
                        AccountRow(
                            account = account,
                            isOwn = isOwn,
                            onEdit = if (isOwn) {{ onEditClick(account.id) }} else null,
                            onDelete = if (isOwn) {{
                                viewModel.delete(account.id)
                                scope.launch { snackbarHostState.showSnackbar("Account deleted") }
                            }} else null,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: Account,
    isOwn: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val symbol = LocalCurrencySymbol.current
    AppCard(modifier = modifier.fillMaxWidth(), level = 1) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccountAvatar(name = account.name)
            Spacer(modifier = Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            account.currency,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = CurrencyFormatter.formatWithSymbol(account.balance, symbol),
                    style = MaterialTheme.typography.titleMedium.merge(TabularNumStyle),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (account.isShared) {
                    val badge = if (isOwn) {
                        "Shared with others"
                    } else {
                        account.ownerName.takeIf { it.isNotBlank() }?.let { "Shared by $it" } ?: "Shared"
                    }
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            if (onEdit != null || onDelete != null) {
                Row {
                    if (onEdit != null) {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                        }
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountAvatar(name: String) {
    val initials = name.trim().take(1).uppercase().ifEmpty { "•" }
    val palette = listOf(
        Color(0xFF26A69A),
        Color(0xFF42A5F5),
        Color(0xFFAB47BC),
        Color(0xFFFFA726),
        Color(0xFFEF5350),
        Color(0xFF66BB6A)
    )
    val color = palette[(name.hashCode().mod(palette.size) + palette.size) % palette.size]
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BrandFab(onClick: () -> Unit, contentDescription: String) {
    val gradient = BrandGradients.hero()
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        shadowElevation = 8.dp,
        modifier = Modifier.size(64.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

