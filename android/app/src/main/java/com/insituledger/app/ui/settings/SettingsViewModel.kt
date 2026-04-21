package com.insituledger.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.remote.tls.ClientCertificateKeyManager
import com.insituledger.app.data.repository.AccountRepository
import com.insituledger.app.data.repository.AuthRepository
import com.insituledger.app.data.repository.FileBackupRepository
import com.insituledger.app.data.repository.PreferencesRepository
import com.insituledger.app.data.repository.SharedAccessState
import com.insituledger.app.data.repository.SharedRepository
import com.insituledger.app.data.sync.BackupManager
import com.insituledger.app.data.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: String = "system",
    val weekStartDay: String = "monday",
    val biometricEnabled: Boolean = false,
    val screenSecure: Boolean = true,
    val syncMode: String = "none",
    // Webapp sync fields
    val isWebappConnected: Boolean = false,
    val userName: String = "",
    val pendingOps: Int = 0,
    val lastSyncVersion: Long = 0,
    val isSyncing: Boolean = false,
    val isChangingPassword: Boolean = false,
    val passwordError: String? = null,
    val passwordChanged: Boolean = false,
    val disconnected: Boolean = false,
    // File backup fields
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val backupMessage: String? = null,
    // Auto backup fields
    val autoBackupFolderUri: String? = null,
    val autoBackupDailyEnabled: Boolean = false,
    val autoBackupDailyRetention: Int = 7,
    val autoBackupWeeklyEnabled: Boolean = false,
    val autoBackupWeeklyRetention: Int = 4,
    val autoBackupMonthlyEnabled: Boolean = false,
    val autoBackupMonthlyRetention: Int = 6,
    // mTLS fields
    val mtlsEnabled: Boolean = false,
    val mtlsAlias: String? = null,
    // Display preferences
    val currencySymbol: String = "€",
    val allowCleartextHttp: Boolean = false,
    // Backup encryption
    val backupPassphraseSet: Boolean = false,
    val pendingImportUri: String? = null,
    val pendingImportNeedsPassphrase: Boolean = false,
    // Default account (only meaningful when connected to webapp)
    val defaultAccountId: Long? = null,
    val accessibleAccountOptions: List<AccessibleAccountOption> = emptyList()
)

data class AccessibleAccountOption(
    val ownerName: String,
    val accountId: Long,
    val accountName: String
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val fileBackupRepository: FileBackupRepository,
    private val backupManager: BackupManager,
    private val prefs: UserPreferences,
    private val pendingOpDao: PendingOperationDao,
    private val okHttpClient: OkHttpClient,
    private val clientCertKeyManager: ClientCertificateKeyManager,
    private val accountRepository: AccountRepository,
    private val sharedRepository: SharedRepository,
    private val sharedAccessState: SharedAccessState,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                prefs.themeModeFlow,
                prefs.biometricEnabledFlow,
                prefs.syncModeFlow,
                prefs.tokenFlow,
                prefs.userNameFlow
            ) { theme, biometric, syncMode, token, userName ->
                _uiState.update {
                    it.copy(
                        themeMode = theme,
                        biometricEnabled = biometric,
                        syncMode = syncMode,
                        isWebappConnected = token != null,
                        userName = userName ?: ""
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            prefs.weekStartDayFlow.collect { day ->
                _uiState.update { it.copy(weekStartDay = day) }
            }
        }

        viewModelScope.launch {
            prefs.screenSecureFlow.collect { secure ->
                _uiState.update { it.copy(screenSecure = secure) }
            }
        }

        viewModelScope.launch {
            combine(
                prefs.lastSyncVersionFlow,
                pendingOpDao.getCount()
            ) { syncVersion, pending ->
                _uiState.update {
                    it.copy(lastSyncVersion = syncVersion, pendingOps = pending)
                }
            }.collect()
        }

        viewModelScope.launch {
            combine(prefs.mtlsEnabledFlow, prefs.mtlsAliasFlow) { enabled, alias ->
                _uiState.update { it.copy(mtlsEnabled = enabled, mtlsAlias = alias) }
            }.collect()
        }

        viewModelScope.launch {
            prefs.currencySymbolFlow.collect { symbol ->
                _uiState.update { it.copy(currencySymbol = symbol) }
            }
        }

        viewModelScope.launch {
            prefs.allowCleartextHttpFlow.collect { allowed ->
                _uiState.update { it.copy(allowCleartextHttp = allowed) }
            }
        }

        viewModelScope.launch {
            prefs.backupPassphraseSetFlow.collect { set ->
                _uiState.update { it.copy(backupPassphraseSet = set) }
            }
        }

        viewModelScope.launch {
            combine(
                prefs.defaultAccountIdFlow,
                sharedAccessState.accessibleOwners
            ) { defaultId, owners ->
                val ownAccounts = accountRepository.getCached().map {
                    AccessibleAccountOption("My accounts", it.id, it.name)
                }
                val sharedAccounts = owners.flatMap { owner ->
                    owner.accounts.map {
                        AccessibleAccountOption(owner.name, it.accountId, it.accountName)
                    }
                }
                _uiState.update {
                    it.copy(
                        defaultAccountId = defaultId,
                        accessibleAccountOptions = ownAccounts + sharedAccounts
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            combine(
                prefs.autoBackupFolderUriFlow,
                prefs.autoBackupDailyEnabledFlow,
                prefs.autoBackupDailyRetentionFlow,
                prefs.autoBackupWeeklyEnabledFlow,
                prefs.autoBackupWeeklyRetentionFlow,
                prefs.autoBackupMonthlyEnabledFlow,
                prefs.autoBackupMonthlyRetentionFlow
            ) { values: Array<Any?> ->
                _uiState.update {
                    it.copy(
                        autoBackupFolderUri = values[0] as String?,
                        autoBackupDailyEnabled = values[1] as Boolean,
                        autoBackupDailyRetention = values[2] as Int,
                        autoBackupWeeklyEnabled = values[3] as Boolean,
                        autoBackupWeeklyRetention = values[4] as Int,
                        autoBackupMonthlyEnabled = values[5] as Boolean,
                        autoBackupMonthlyRetention = values[6] as Int
                    )
                }
            }.collect()
        }
    }

    fun setTheme(mode: String) {
        viewModelScope.launch { prefs.saveThemeMode(mode) }
    }

    fun setWeekStartDay(day: String) {
        viewModelScope.launch { prefs.saveWeekStartDay(day) }
    }

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch { prefs.saveBiometricEnabled(enabled) }
    }

    fun setScreenSecure(enabled: Boolean) {
        viewModelScope.launch { prefs.saveScreenSecure(enabled) }
    }

    fun setSyncMode(mode: String) {
        viewModelScope.launch {
            prefs.saveSyncMode(mode)
            if (mode != "webapp") {
                syncManager.cancelAll()
            }
        }
    }

    fun forceSync() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            syncManager.syncNow()
            _uiState.update { it.copy(isSyncing = false) }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChangingPassword = true, passwordError = null) }
            val result = authRepository.changePassword(currentPassword, newPassword)
            result.fold(
                onSuccess = { _uiState.update { it.copy(isChangingPassword = false, passwordChanged = true) } },
                onFailure = { e -> _uiState.update { it.copy(isChangingPassword = false, passwordError = e.message) } }
            )
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            authRepository.logout()
            syncManager.cancelAll()
            prefs.saveSyncMode("none")
            _uiState.update { it.copy(disconnected = true) }
        }
    }

    fun exportData(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, backupMessage = null) }
            val passphrase = prefs.getBackupPassphrase()
            fileBackupRepository.exportToUri(uri, passphrase).fold(
                onSuccess = { count ->
                    val suffix = if (passphrase.isNullOrEmpty()) "" else " (encrypted)"
                    _uiState.update { it.copy(isExporting = false, backupMessage = "Exported $count items$suffix") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isExporting = false, backupMessage = "Export failed: ${e.message}") }
                }
            )
        }
    }

    fun importData(uri: Uri) {
        viewModelScope.launch {
            val savedPassphrase = prefs.getBackupPassphrase()
            // If file is encrypted but we have no passphrase saved, surface the
            // dialog state and wait for the user to enter one.
            if (fileBackupRepository.isEncryptedBackup(uri) && savedPassphrase.isNullOrEmpty()) {
                _uiState.update { it.copy(pendingImportUri = uri.toString(), pendingImportNeedsPassphrase = true) }
                return@launch
            }
            runImport(uri, savedPassphrase)
        }
    }

    fun importWithPassphrase(passphrase: String) {
        val uriStr = _uiState.value.pendingImportUri ?: return
        _uiState.update { it.copy(pendingImportUri = null, pendingImportNeedsPassphrase = false) }
        runImport(Uri.parse(uriStr), passphrase)
    }

    fun cancelPendingImport() {
        _uiState.update { it.copy(pendingImportUri = null, pendingImportNeedsPassphrase = false) }
    }

    private fun runImport(uri: Uri, passphrase: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, backupMessage = null) }
            fileBackupRepository.importFromUri(uri, passphrase).fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isImporting = false, backupMessage = "Imported $count items") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isImporting = false, backupMessage = "Import failed: ${e.message}") }
                }
            )
        }
    }

    fun setBackupPassphrase(passphrase: String?) {
        viewModelScope.launch {
            prefs.saveBackupPassphrase(passphrase?.ifEmpty { null })
        }
    }

    fun clearBackupMessage() {
        _uiState.update { it.copy(backupMessage = null) }
    }

    fun setAutoBackupFolder(uri: Uri) {
        fileBackupRepository.takeFolderPermission(uri)
        viewModelScope.launch {
            prefs.saveAutoBackupFolderUri(uri.toString())
            backupManager.scheduleAutoBackup()
        }
    }

    fun clearAutoBackupFolder() {
        viewModelScope.launch {
            val oldUri = prefs.getAutoBackupFolderUriImmediate()
            if (oldUri != null) {
                fileBackupRepository.releaseFolderPermission(Uri.parse(oldUri))
            }
            prefs.saveAutoBackupFolderUri(null)
            backupManager.cancelAutoBackup()
        }
    }

    fun setAutoBackupDailyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.saveAutoBackupDailyEnabled(enabled)
            backupManager.scheduleAutoBackup()
        }
    }

    fun setAutoBackupDailyRetention(count: Int) {
        viewModelScope.launch { prefs.saveAutoBackupDailyRetention(count) }
    }

    fun setAutoBackupWeeklyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.saveAutoBackupWeeklyEnabled(enabled)
            backupManager.scheduleAutoBackup()
        }
    }

    fun setAutoBackupWeeklyRetention(count: Int) {
        viewModelScope.launch { prefs.saveAutoBackupWeeklyRetention(count) }
    }

    fun setAutoBackupMonthlyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.saveAutoBackupMonthlyEnabled(enabled)
            backupManager.scheduleAutoBackup()
        }
    }

    fun setAutoBackupMonthlyRetention(count: Int) {
        viewModelScope.launch { prefs.saveAutoBackupMonthlyRetention(count) }
    }

    fun setMtlsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.saveMtlsEnabled(enabled)
            if (!enabled) prefs.saveMtlsAlias(null)
            clientCertKeyManager.invalidate()
            okHttpClient.connectionPool.evictAll()
        }
    }

    fun setAllowCleartextHttp(enabled: Boolean) {
        viewModelScope.launch {
            prefs.saveAllowCleartextHttp(enabled)
            okHttpClient.connectionPool.evictAll()
        }
    }

    fun setCurrencySymbol(symbol: String) {
        viewModelScope.launch {
            val trimmed = symbol.take(8)
            prefs.saveCurrencySymbol(trimmed)
            if (prefs.getSyncModeImmediate() == "webapp") {
                authRepository.updateCurrencySymbol(trimmed)
            }
        }
    }

    fun setDefaultAccount(accountId: Long?) {
        viewModelScope.launch { preferencesRepository.setDefaultAccount(accountId) }
    }

    fun refreshAccessibleAccounts() {
        viewModelScope.launch { sharedRepository.loadAccessibleOwners() }
    }

    fun setMtlsAlias(alias: String?) {
        viewModelScope.launch {
            prefs.saveMtlsAlias(alias)
            clientCertKeyManager.invalidate()
            okHttpClient.connectionPool.evictAll()
        }
    }
}
