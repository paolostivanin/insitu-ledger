package com.insituledger.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.insituledger.app.ui.common.ListRow
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.theme.AppSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onAccountsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onReportsClick: () -> Unit,
    onSharedClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val pad = LocalContentPadding.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("More") }, scrollBehavior = scrollBehavior) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            MoreItem(Icons.Outlined.Insights, "Reports", "Spending by category and period", onReportsClick)
            MoreItem(Icons.Outlined.AccountBalanceWallet, "Accounts", null, onAccountsClick)
            MoreItem(Icons.AutoMirrored.Outlined.Label, "Categories", null, onCategoriesClick)
            MoreItem(Icons.Outlined.People, "Co-owners", "Share accounts with family members", onSharedClick)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = pad, vertical = AppSpacing.sm)
            )
            MoreItem(Icons.Outlined.Settings, "Settings", null, onSettingsClick)
            MoreItem(Icons.Outlined.Info, "About", null, onAboutClick)
        }
    }
}

@Composable
private fun MoreItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListRow(
        headline = title,
        supporting = subtitle,
        leading = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
        horizontalPadding = LocalContentPadding.current
    )
}
