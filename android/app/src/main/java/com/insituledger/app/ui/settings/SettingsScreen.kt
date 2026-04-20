package com.insituledger.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.security.KeyChain
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.insituledger.app.ui.common.AppCard
import com.insituledger.app.ui.theme.AppSpacing
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onConnectWebapp: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // SAF launcher for auto backup folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.setAutoBackupFolder(it) }
    }

    // SAF launchers for file backup
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportData(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importData(it) }
    }

    // Show snackbar for backup messages
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.backupMessage) {
        uiState.backupMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBackupMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(AppSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
        ) {
            // Theme
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                            FilterChip(
                                selected = uiState.themeMode == value,
                                onClick = { viewModel.setTheme(value) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // Week starts on
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Text("Week starts on", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        listOf("monday" to "Monday", "sunday" to "Sunday").forEach { (value, label) ->
                            FilterChip(
                                selected = uiState.weekStartDay == value,
                                onClick = { viewModel.setWeekStartDay(value) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // Currency symbol
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Text("Currency symbol", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        "Shown next to every amount across the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    var symbolDraft by remember(uiState.currencySymbol) { mutableStateOf(uiState.currencySymbol) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        OutlinedTextField(
                            value = symbolDraft,
                            onValueChange = { symbolDraft = it.take(8) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            label = { Text("Symbol") }
                        )
                        Button(
                            onClick = { viewModel.setCurrencySymbol(symbolDraft) },
                            enabled = symbolDraft != uiState.currencySymbol
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            // Biometric unlock
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Row(
                    modifier = Modifier.padding(AppSpacing.cardPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(AppSpacing.md))
                    Text("Fingerprint unlock", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = uiState.biometricEnabled,
                        onCheckedChange = viewModel::setBiometric
                    )
                }
            }

            // Prevent screenshots
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Row(
                    modifier = Modifier.padding(AppSpacing.cardPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ScreenLockPortrait, contentDescription = null)
                    Spacer(modifier = Modifier.width(AppSpacing.md))
                    Text("Prevent screenshots", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = uiState.screenSecure,
                        onCheckedChange = viewModel::setScreenSecure
                    )
                }
            }

            // Data backup (always available)
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text("Data Backup", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "Export or import your data as a JSON file. Save to Google Drive, Dropbox, or any storage provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                        Button(
                            onClick = { exportLauncher.launch("insitu-ledger-backup.json") },
                            enabled = !uiState.isExporting && !uiState.isImporting
                        ) {
                            if (uiState.isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                            }
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text("Export")
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json")) },
                            enabled = !uiState.isExporting && !uiState.isImporting
                        ) {
                            if (uiState.isImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                            }
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text("Import")
                        }
                    }
                }
            }

            // Automatic backup
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text("Automatic Backup", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "Automatically back up your data on a daily, weekly, or monthly schedule. " +
                                "Backups are saved as JSON files to the selected folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    if (uiState.autoBackupFolderUri != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Folder, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text(
                                "Folder selected", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { folderPickerLauncher.launch(null) }) { Text("Change") }
                            TextButton(onClick = viewModel::clearAutoBackupFolder) { Text("Clear") }
                        }

                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(AppSpacing.md))

                        BackupTierRow(
                            label = "Daily",
                            description = "Runs every day at ~3:00 AM",
                            enabled = uiState.autoBackupDailyEnabled,
                            retention = uiState.autoBackupDailyRetention,
                            onEnabledChange = viewModel::setAutoBackupDailyEnabled,
                            onRetentionChange = viewModel::setAutoBackupDailyRetention
                        )
                        BackupTierRow(
                            label = "Weekly",
                            description = "Runs every Monday",
                            enabled = uiState.autoBackupWeeklyEnabled,
                            retention = uiState.autoBackupWeeklyRetention,
                            onEnabledChange = viewModel::setAutoBackupWeeklyEnabled,
                            onRetentionChange = viewModel::setAutoBackupWeeklyRetention
                        )
                        BackupTierRow(
                            label = "Monthly",
                            description = "Runs on the 1st of each month",
                            enabled = uiState.autoBackupMonthlyEnabled,
                            retention = uiState.autoBackupMonthlyRetention,
                            onEnabledChange = viewModel::setAutoBackupMonthlyEnabled,
                            onRetentionChange = viewModel::setAutoBackupMonthlyRetention
                        )
                    } else {
                        Button(onClick = { folderPickerLauncher.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text("Select Backup Folder")
                        }
                    }
                }
            }

            // Client certificate (mTLS)
            val context = LocalContext.current
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text("Client certificate (mTLS)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(
                            checked = uiState.mtlsEnabled,
                            onCheckedChange = viewModel::setMtlsEnabled
                        )
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "Required if your server enforces mutual TLS (e.g. Cloudflare Access).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.mtlsEnabled) {
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Selected certificate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    uiState.mtlsAlias ?: "None",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (uiState.mtlsAlias != null) FontWeight.Medium else FontWeight.Normal
                                )
                            }
                            TextButton(onClick = {
                                val activity = context as? Activity ?: return@TextButton
                                KeyChain.choosePrivateKeyAlias(
                                    activity,
                                    { alias -> viewModel.setMtlsAlias(alias) },
                                    null,
                                    null,
                                    null,
                                    -1,
                                    uiState.mtlsAlias
                                )
                            }) {
                                Text(if (uiState.mtlsAlias == null) "Choose" else "Change")
                            }
                            if (uiState.mtlsAlias != null) {
                                TextButton(onClick = { viewModel.setMtlsAlias(null) }) { Text("Clear") }
                            }
                        }
                    }
                }
            }

            // Allow HTTP (cleartext) — opt-in for LAN self-hosters
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text("Allow HTTP (insecure)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Switch(
                            checked = uiState.allowCleartextHttp,
                            onCheckedChange = viewModel::setAllowCleartextHttp
                        )
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        "Required for LAN servers reached over http://. Leave off if your server is on the public internet — HTTPS is enforced by default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Sync section
            AppCard(modifier = Modifier.fillMaxWidth(), level = 1) {
                Column(modifier = Modifier.padding(AppSpacing.cardPadding)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text("Server Sync", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    Text(
                        "Optionally connect to an InSitu Ledger server for real-time sync across devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.md))

                    if (uiState.syncMode == "webapp" && uiState.isWebappConnected) {
                        // Connected state
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(AppSpacing.md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Connected", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                if (uiState.userName.isNotBlank()) {
                                    Text(uiState.userName, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text("Last sync version: ${uiState.lastSyncVersion}", style = MaterialTheme.typography.bodySmall)
                        Text("Pending operations: ${uiState.pendingOps}", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            Button(onClick = viewModel::forceSync, enabled = !uiState.isSyncing) {
                                if (uiState.isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                }
                                Text("Sync Now")
                            }
                            OutlinedButton(
                                onClick = viewModel::disconnect,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Disconnect")
                            }
                        }
                    } else {
                        // Not connected
                        Button(onClick = {
                            viewModel.setSyncMode("webapp")
                            onConnectWebapp?.invoke()
                        }) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(AppSpacing.sm))
                            Text("Connect to Server")
                        }
                    }
                }
            }

            // Change password (only when webapp connected)
            if (uiState.syncMode == "webapp" && uiState.isWebappConnected) {
                var showPasswordDialog by remember { mutableStateOf(false) }
                AppCard(modifier = Modifier.fillMaxWidth(), onClick = { showPasswordDialog = true }, level = 1) {
                    Row(modifier = Modifier.padding(AppSpacing.cardPadding), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(AppSpacing.md))
                        Text("Change Password", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }

                if (showPasswordDialog) {
                    ChangePasswordDialog(
                        isLoading = uiState.isChangingPassword,
                        error = uiState.passwordError,
                        onDismiss = { showPasswordDialog = false },
                        onConfirm = { current, new -> viewModel.changePassword(current, new) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupTierRow(
    label: String,
    description: String,
    enabled: Boolean,
    retention: Int,
    onEnabledChange: (Boolean) -> Unit,
    onRetentionChange: (Int) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = AppSpacing.lg, bottom = AppSpacing.sm)
            ) {
                Text("Keep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                IconButton(
                    onClick = { if (retention > 1) onRetentionChange(retention - 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(18.dp))
                }
                Text("$retention", style = MaterialTheme.typography.bodyMedium)
                IconButton(
                    onClick = { onRetentionChange(retention + 1) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text("backups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                OutlinedTextField(
                    value = currentPassword, onValueChange = { currentPassword = it },
                    label = { Text("Current Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = newPassword, onValueChange = { newPassword = it },
                    label = { Text("New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(currentPassword, newPassword) }, enabled = !isLoading) {
                Text("Change")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
