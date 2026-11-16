package com.insituledger.app.ui.settings

import android.app.Activity
import android.net.Uri
import android.security.KeyChain
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.insituledger.app.ui.common.ConfirmDialog
import com.insituledger.app.ui.common.DateLabels
import com.insituledger.app.ui.common.LocalContentPadding
import com.insituledger.app.ui.common.LocalSnackbarController
import com.insituledger.app.ui.common.SectionHeader
import com.insituledger.app.ui.theme.AppSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val MIN_BACKUP_PASSPHRASE_LENGTH = 12
private val BACKUP_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun formatBackupTime(timestamp: Long?): String {
    if (timestamp == null) return "Never"
    val at = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    return "${DateLabels.day(at.toLocalDate())} · ${at.format(BACKUP_TIME_FORMAT)}"
}

private fun changeCount(n: Int): String = if (n == 1) "1 change" else "$n changes"

private data class Choice<T>(val value: T, val label: String, val description: String? = null)

private val ThemeChoices = listOf(
    Choice("system", "System default"),
    Choice("light", "Light"),
    Choice("dark", "Dark")
)

private val WeekStartChoices = listOf(
    Choice("monday", "Monday"),
    Choice("sunday", "Sunday")
)

private val HeadlineChoices = listOf(
    Choice("net_worth", "Net worth", "Total balance of your accounts"),
    Choice("month_net", "This month", "Income minus expenses so far this month")
)

private fun <T> List<Choice<T>>.labelFor(value: T): String =
    (firstOrNull { it.value == value } ?: first()).label

/** Which dialog is open. One at a time; saved across rotation. */
private enum class SettingsDialog {
    Theme, WeekStart, CurrencySymbol, DashboardHeadline, DefaultAccount,
    BackupPassphrase, DisablePassphrase, ClearBackupFolder,
    ChangePassword, DiscardFailed, Disconnect
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onConnectWebapp: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current
    val context = LocalContext.current
    val pad = LocalContentPadding.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    LaunchedEffect(uiState.passwordChanged) {
        if (uiState.passwordChanged) {
            dialog = null
            viewModel.clearPasswordChanged()
            onConnectWebapp?.invoke()
        }
    }

    LaunchedEffect(uiState.backupMessage) {
        uiState.backupMessage?.let {
            snackbar.show(it)
            viewModel.clearBackupMessage()
        }
    }

    // SAF launcher for the automatic backup folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { viewModel.setAutoBackupFolder(it) }
    }

    // SAF launchers for manual export / import
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

    val connected = uiState.syncMode == "webapp" && uiState.isWebappConnected
    val backupBusy = uiState.isExporting || uiState.isImporting
    val errorColor = MaterialTheme.colorScheme.error

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.xl)
        ) {
            // ---- Appearance ----
            SectionHeader("Appearance", horizontalPadding = pad, topPadding = AppSpacing.sm)
            PreferenceRow(
                title = "Theme",
                summary = ThemeChoices.labelFor(uiState.themeMode),
                onClick = { dialog = SettingsDialog.Theme }
            )
            PreferenceRow(
                title = "Week starts on",
                summary = WeekStartChoices.labelFor(uiState.weekStartDay),
                onClick = { dialog = SettingsDialog.WeekStart }
            )
            PreferenceRow(
                title = "Currency symbol",
                summary = uiState.currencySymbol.ifBlank { "None" },
                onClick = { dialog = SettingsDialog.CurrencySymbol }
            )
            PreferenceRow(
                title = "Dashboard headline",
                summary = HeadlineChoices.labelFor(uiState.dashboardHeroMode),
                onClick = { dialog = SettingsDialog.DashboardHeadline }
            )

            // ---- Security ----
            SectionHeader("Security", horizontalPadding = pad)
            SwitchRow(
                title = "Fingerprint unlock",
                summary = "Unlock with your fingerprint when the app opens",
                checked = uiState.biometricEnabled,
                onCheckedChange = viewModel::setBiometric
            )
            SwitchRow(
                title = "Prevent screenshots",
                summary = "Blocks screenshots and hides the app in recent apps",
                checked = uiState.screenSecure,
                onCheckedChange = viewModel::setScreenSecure
            )

            // ---- Backups ----
            SectionHeader("Backups", horizontalPadding = pad)
            PreferenceRow(
                title = "Export backup",
                summary = if (uiState.backupPassphraseSet) "Save an encrypted .ilbk file"
                    else "Save a .json file to your device or a cloud drive",
                enabled = !backupBusy,
                onClick = {
                    val name = if (uiState.backupPassphraseSet) "insitu-ledger-backup.ilbk" else "insitu-ledger-backup.json"
                    exportLauncher.launch(name)
                },
                trailing = if (uiState.isExporting) ({ RowProgress("Exporting") }) else null
            )
            PreferenceRow(
                title = "Import backup",
                summary = "Restore entries from a .json or .ilbk file",
                enabled = !backupBusy,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                trailing = if (uiState.isImporting) ({ RowProgress("Importing") }) else null
            )
            PreferenceRow(
                title = "Backup passphrase",
                summary = if (uiState.backupPassphraseSet) "Encrypted" else "Not encrypted",
                onClick = { dialog = SettingsDialog.BackupPassphrase }
            )

            SectionHeader("Automatic backup", horizontalPadding = pad, topPadding = AppSpacing.lg)
            val folderUri = uiState.autoBackupFolderUri
            val folderName = rememberFolderName(folderUri)
            PreferenceRow(
                title = "Backup folder",
                summary = when {
                    folderUri == null -> "Choose a folder to turn on automatic backups"
                    else -> folderName ?: "Folder selected"
                },
                onClick = { folderPickerLauncher.launch(null) },
                trailing = if (folderUri != null) ({
                    TextButton(
                        onClick = { dialog = SettingsDialog.ClearBackupFolder },
                        modifier = Modifier.semantics { contentDescription = "Clear backup folder" }
                    ) { Text("Clear") }
                }) else null
            )
            if (folderUri != null) {
                SwitchRow(
                    title = "Daily",
                    summary = "Every day at about 03:00",
                    checked = uiState.autoBackupDailyEnabled,
                    onCheckedChange = viewModel::setAutoBackupDailyEnabled
                )
                if (uiState.autoBackupDailyEnabled) {
                    RetentionRow("daily", uiState.autoBackupDailyRetention, viewModel::setAutoBackupDailyRetention)
                }
                SwitchRow(
                    title = "Weekly",
                    summary = "Every Monday",
                    checked = uiState.autoBackupWeeklyEnabled,
                    onCheckedChange = viewModel::setAutoBackupWeeklyEnabled
                )
                if (uiState.autoBackupWeeklyEnabled) {
                    RetentionRow("weekly", uiState.autoBackupWeeklyRetention, viewModel::setAutoBackupWeeklyRetention)
                }
                SwitchRow(
                    title = "Monthly",
                    summary = "On the 1st of each month",
                    checked = uiState.autoBackupMonthlyEnabled,
                    onCheckedChange = viewModel::setAutoBackupMonthlyEnabled
                )
                if (uiState.autoBackupMonthlyEnabled) {
                    RetentionRow("monthly", uiState.autoBackupMonthlyRetention, viewModel::setAutoBackupMonthlyRetention)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pad, vertical = AppSpacing.sm)
                        .semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    FootnoteText("Last successful backup: ${formatBackupTime(uiState.autoBackupLastSuccessfulAt)}")
                    FootnoteText("Last attempt: ${formatBackupTime(uiState.autoBackupLastAttemptAt)}")
                    FootnoteText(
                        if (uiState.backupPassphraseSet) "Saved as encrypted .ilbk files."
                        else "Saved as .json files. Set a backup passphrase to encrypt them."
                    )
                }
            }

            // ---- Sync ----
            SectionHeader("Sync", horizontalPadding = pad)
            if (connected) {
                PreferenceRow(
                    title = uiState.userName.takeIf { it.isNotBlank() }?.let { "Connected as $it" } ?: "Connected",
                    summary = when {
                        uiState.isSyncing -> "Syncing…"
                        uiState.pendingOps == 0 -> "All changes synced"
                        else -> "${changeCount(uiState.pendingOps)} waiting to sync"
                    },
                    liveSummary = true
                )
                PreferenceRow(
                    title = "Sync now",
                    enabled = !uiState.isSyncing,
                    onClick = viewModel::forceSync,
                    trailing = if (uiState.isSyncing) ({ RowProgress("Syncing") }) else null
                )
                uiState.syncError?.let { msg ->
                    PreferenceRow(
                        title = "Last sync failed",
                        titleColor = errorColor,
                        summary = msg,
                        liveSummary = true,
                        trailing = {
                            TextButton(
                                onClick = viewModel::clearSyncError,
                                modifier = Modifier.semantics { contentDescription = "Dismiss sync error" }
                            ) { Text("Dismiss") }
                        }
                    )
                }
                if (uiState.failedOps > 0) {
                    FailedOperationsRow(
                        count = uiState.failedOps,
                        firstError = uiState.firstFailedOpError,
                        onRetry = {
                            viewModel.retryFailedSyncOperations()
                            snackbar.show("Retrying ${changeCount(uiState.failedOps)}")
                        },
                        onDiscard = { dialog = SettingsDialog.DiscardFailed }
                    )
                }
                if (uiState.accessibleAccountOptions.isNotEmpty()) {
                    LaunchedEffect(Unit) { viewModel.refreshAccessibleAccounts() }
                    val selected = uiState.accessibleAccountOptions.find { it.accountId == uiState.defaultAccountId }
                    PreferenceRow(
                        title = "Default account",
                        summary = selected?.let { "${it.ownerName} · ${it.accountName}" } ?: "None",
                        onClick = { dialog = SettingsDialog.DefaultAccount }
                    )
                }
                PreferenceRow(
                    title = "Change password",
                    onClick = { dialog = SettingsDialog.ChangePassword }
                )
                PreferenceRow(
                    title = "Disconnect",
                    titleColor = errorColor,
                    summary = "Sign out of the server on this device",
                    onClick = { dialog = SettingsDialog.Disconnect }
                )
            } else {
                PreferenceRow(
                    title = "Not connected",
                    summary = "Sync your data across devices with an InSitu Ledger server"
                )
                PreferenceRow(
                    title = "Connect to server",
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        viewModel.setSyncMode("webapp")
                        onConnectWebapp?.invoke()
                    }
                )
            }

            // Connection settings stay visible so they can be set before connecting.
            SectionHeader("Connection", horizontalPadding = pad, topPadding = AppSpacing.lg)
            SwitchRow(
                title = "Client certificate (mTLS)",
                summary = "Required if your server enforces mutual TLS, for example Cloudflare Access",
                checked = uiState.mtlsEnabled,
                onCheckedChange = viewModel::setMtlsEnabled
            )
            if (uiState.mtlsEnabled) {
                PreferenceRow(
                    title = "Certificate",
                    summary = uiState.mtlsAlias ?: "None selected",
                    onClick = {
                        val activity = context as? Activity ?: return@PreferenceRow
                        KeyChain.choosePrivateKeyAlias(
                            activity,
                            { alias -> viewModel.setMtlsAlias(alias) },
                            null,
                            null,
                            null,
                            -1,
                            uiState.mtlsAlias
                        )
                    },
                    trailing = if (uiState.mtlsAlias != null) ({
                        TextButton(
                            onClick = { viewModel.setMtlsAlias(null) },
                            modifier = Modifier.semantics { contentDescription = "Clear certificate" }
                        ) { Text("Clear") }
                    }) else null
                )
            }
            SwitchRow(
                title = "Allow HTTP (insecure)",
                summary = "Needed for LAN servers reached over http://. Leave off for servers on the internet; HTTPS is enforced by default.",
                checked = uiState.allowCleartextHttp,
                onCheckedChange = viewModel::setAllowCleartextHttp
            )
        }
    }

    // ---- Dialogs ----
    val dismiss = { dialog = null }
    when (dialog) {
        SettingsDialog.Theme -> ChoiceDialog(
            title = "Theme",
            choices = ThemeChoices,
            selected = uiState.themeMode,
            onSelect = viewModel::setTheme,
            onDismiss = dismiss
        )
        SettingsDialog.WeekStart -> ChoiceDialog(
            title = "Week starts on",
            choices = WeekStartChoices,
            selected = uiState.weekStartDay,
            onSelect = viewModel::setWeekStartDay,
            onDismiss = dismiss
        )
        SettingsDialog.DashboardHeadline -> ChoiceDialog(
            title = "Dashboard headline",
            message = "What the large number on the dashboard shows.",
            choices = HeadlineChoices,
            selected = uiState.dashboardHeroMode,
            onSelect = viewModel::setDashboardHeroMode,
            onDismiss = dismiss
        )
        SettingsDialog.CurrencySymbol -> CurrencySymbolDialog(
            current = uiState.currencySymbol,
            onSave = { viewModel.setCurrencySymbol(it); dialog = null },
            onDismiss = dismiss
        )
        SettingsDialog.DefaultAccount -> DefaultAccountDialog(
            options = uiState.accessibleAccountOptions,
            selectedId = uiState.defaultAccountId,
            onSelect = { viewModel.setDefaultAccount(it); dialog = null },
            onDismiss = dismiss
        )
        SettingsDialog.BackupPassphrase -> BackupPassphraseDialog(
            isCurrentlySet = uiState.backupPassphraseSet,
            onSave = {
                viewModel.setBackupPassphrase(it)
                dialog = null
                snackbar.show("Backups will be encrypted")
            },
            onClear = { dialog = SettingsDialog.DisablePassphrase },
            onDismiss = dismiss
        )
        SettingsDialog.DisablePassphrase -> ConfirmDialog(
            title = "Turn off backup encryption?",
            text = "New backups will be saved as unencrypted .json files that anyone with the file can read. " +
                "Existing encrypted backups still need the passphrase to import.",
            confirmLabel = "Turn off",
            onConfirm = {
                viewModel.setBackupPassphrase(null)
                dialog = null
                snackbar.show("Backup encryption turned off")
            },
            onDismiss = dismiss
        )
        SettingsDialog.ClearBackupFolder -> ConfirmDialog(
            title = "Clear backup folder?",
            text = "Automatic backups stop. Backup files already in the folder are kept.",
            confirmLabel = "Clear",
            onConfirm = {
                viewModel.clearAutoBackupFolder()
                dialog = null
                snackbar.show("Automatic backups turned off")
            },
            onDismiss = dismiss
        )
        SettingsDialog.ChangePassword -> ChangePasswordDialog(
            isLoading = uiState.isChangingPassword,
            error = uiState.passwordError,
            onDismiss = dismiss,
            onConfirm = { current, new -> viewModel.changePassword(current, new) }
        )
        SettingsDialog.DiscardFailed -> ConfirmDialog(
            title = "Discard ${changeCount(uiState.failedOps)}?",
            text = "They stay on this device but won't be sent to the server.",
            confirmLabel = "Discard",
            onConfirm = {
                viewModel.discardFailedSyncOperations()
                dialog = null
            },
            onDismiss = dismiss
        )
        SettingsDialog.Disconnect -> ConfirmDialog(
            title = "Disconnect from the server?",
            text = "Your entries stay on this device but won't sync until you connect again. " +
                "App preferences such as theme and automatic backups are reset.",
            confirmLabel = "Disconnect",
            onConfirm = {
                viewModel.disconnect()
                dialog = null
                snackbar.show("Disconnected")
            },
            onDismiss = dismiss
        )
        null -> Unit
    }

    if (uiState.pendingImportNeedsPassphrase) {
        ImportPassphraseDialog(
            onConfirm = { viewModel.importWithPassphrase(it) },
            onDismiss = { viewModel.cancelPendingImport() }
        )
    }
}

// ---- Rows ----

/** Flat settings row: title, optional summary, optional trailing control. */
@Composable
private fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    titleColor: Color = Color.Unspecified,
    summaryColor: Color = Color.Unspecified,
    enabled: Boolean = true,
    liveSummary: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val pad = LocalContentPadding.current
    val colors = MaterialTheme.colorScheme
    val disabled = colors.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                else Modifier.semantics(mergeDescendants = true) {}
            )
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(horizontal = pad, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> disabled
                    titleColor != Color.Unspecified -> titleColor
                    else -> colors.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        !enabled -> disabled
                        summaryColor != Color.Unspecified -> summaryColor
                        else -> colors.onSurfaceVariant
                    },
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (liveSummary) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.size(AppSpacing.md))
            trailing()
        }
    }
}

/** Whole-row switch: the row is the toggle target. */
@Composable
private fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val pad = LocalContentPadding.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(horizontal = pad, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.size(AppSpacing.lg))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** "Keep 7 daily backups" with 48dp − / + buttons; indented under its tier. */
@Composable
private fun RetentionRow(tier: String, retention: Int, onChange: (Int) -> Unit) {
    val pad = LocalContentPadding.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppSpacing.rowMinHeight)
            .padding(start = pad + AppSpacing.lg, end = (pad - AppSpacing.md).coerceAtLeast(0.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Keep $retention $tier ${if (retention == 1) "backup" else "backups"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        IconButton(onClick = { if (retention > 1) onChange(retention - 1) }, enabled = retention > 1) {
            Icon(Icons.Outlined.Remove, contentDescription = "Keep fewer $tier backups")
        }
        IconButton(onClick = { onChange(retention + 1) }) {
            Icon(Icons.Outlined.Add, contentDescription = "Keep more $tier backups")
        }
    }
}

@Composable
private fun FailedOperationsRow(
    count: Int,
    firstError: String?,
    onRetry: () -> Unit,
    onDiscard: () -> Unit
) {
    val pad = LocalContentPadding.current
    Column(Modifier.fillMaxWidth()) {
        PreferenceRow(
            title = "${changeCount(count)} couldn't sync",
            titleColor = MaterialTheme.colorScheme.error,
            summary = firstError
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (pad - AppSpacing.md).coerceAtLeast(0.dp), end = pad, bottom = AppSpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            TextButton(
                onClick = onRetry,
                modifier = Modifier.semantics { contentDescription = "Retry changes that couldn't sync" }
            ) { Text("Retry") }
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.semantics { contentDescription = "Discard changes that couldn't sync" }
            ) { Text("Discard") }
        }
    }
}

@Composable
private fun RowProgress(label: String) {
    CircularProgressIndicator(
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = label },
        strokeWidth = 2.dp
    )
}

@Composable
private fun FootnoteText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Display name of the backup folder, resolved off the main thread. */
@Composable
private fun rememberFolderName(uri: String?): String? {
    val context = LocalContext.current
    var name by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        name = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()
        }
    }
    return name
}

// ---- Dialogs ----

@Composable
private fun ChoiceRow(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(AppSpacing.lg))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Single-choice dialog: picking an option applies it and closes. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = AppSpacing.sm)
                    )
                }
                Column(Modifier.selectableGroup()) {
                    choices.forEach { choice ->
                        ChoiceRow(
                            label = choice.label,
                            description = choice.description,
                            selected = choice.value == selected,
                            onClick = { onSelect(choice.value); onDismiss() }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DefaultAccountDialog(
    options: List<AccessibleAccountOption>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val grouped = options.groupBy { it.ownerName }
    val noneSelected = options.none { it.accountId == selectedId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default account") },
        text = {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup()
            ) {
                Text(
                    "Pre-selected when a form asks for an account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = AppSpacing.sm)
                )
                ChoiceRow("None", null, selected = noneSelected, onClick = { onSelect(null) })
                grouped.forEach { (ownerName, ownerAccounts) ->
                    Text(
                        ownerName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = AppSpacing.md, bottom = AppSpacing.xs)
                            .semantics { heading() }
                    )
                    ownerAccounts.forEach { opt ->
                        ChoiceRow(
                            label = opt.accountName,
                            description = null,
                            selected = opt.accountId == selectedId,
                            onClick = { onSelect(opt.accountId) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CurrencySymbolDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by rememberSaveable { mutableStateOf(current) }
    val changed = draft != current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency symbol") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                Text(
                    "Shown next to amounts in your main currency.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(8) },
                    label = { Text("Symbol") },
                    supportingText = { Text("Up to 8 characters") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (changed) onSave(draft) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = changed) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportPassphraseDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encrypted backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                Text(
                    "This backup is encrypted. Enter the passphrase used when it was created.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (passphrase.isNotEmpty()) onConfirm(passphrase) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotEmpty()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BackupPassphraseDialog(
    isCurrentlySet: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val mismatch = passphrase.isNotEmpty() && confirmation.isNotEmpty() && passphrase != confirmation
    // 200k PBKDF2 iterations is fast against a low-entropy passphrase; require
    // at least 12 characters so the derived key is meaningfully expensive to
    // brute-force on stolen backup files.
    val tooShort = passphrase.isNotEmpty() && passphrase.length < MIN_BACKUP_PASSPHRASE_LENGTH
    val canSave = passphrase.length >= MIN_BACKUP_PASSPHRASE_LENGTH && passphrase == confirmation

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCurrentlySet) "Change backup passphrase" else "Set backup passphrase") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    "Backups are encrypted with AES-256 using this passphrase. " +
                        "If you lose it, your backups can't be recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = tooShort,
                    supportingText = {
                        Text(
                            if (tooShort) "Passphrase must be at least $MIN_BACKUP_PASSPHRASE_LENGTH characters"
                            else "At least $MIN_BACKUP_PASSPHRASE_LENGTH characters"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Confirm passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text("Passphrases don't match") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(passphrase) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(passphrase) }, enabled = canSave) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (isCurrentlySet) {
                    TextButton(
                        onClick = onClear,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Turn off")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
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
        title = { Text("Change password") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                Text(
                    "You'll be asked to sign in again with the new password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!isLoading) onConfirm(currentPassword, newPassword) }),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentPassword, newPassword) }, enabled = !isLoading) {
                Text(if (isLoading) "Changing…" else "Change")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
