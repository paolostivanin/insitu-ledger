package com.insituledger.app.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onAccountsClick: () -> Unit,
    onCategoriesClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("More") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MoreMenuItem(Icons.Default.AccountBalance, "Accounts", onAccountsClick)
            MoreMenuItem(Icons.AutoMirrored.Filled.List, "Categories", onCategoriesClick)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            MoreMenuItem(Icons.Default.Settings, "Settings", onSettingsClick)
        }
    }
}

@Composable
private fun MoreMenuItem(icon: ImageVector, title: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
