package com.insituledger.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.repository.AuthRepository
import com.insituledger.app.data.repository.FileBackupRepository
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
    val allowCleartextHttp: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val fileBackupRepository: FileBackupRepository,
    private val backupManager: BackupManager,
    private val prefs: UserPreferences,
    private val pendingOpDao: PendingOperationDao,
    private val okHttpClient: OkHttpClient
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
            fileBackupRepository.exportToUri(uri).fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isExporting = false, backupMessage = "Exported $count items") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isExporting = false, backupMessage = "Export failed: ${e.message}") }
                }
            )
        }
    }

    fun importData(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, backupMessage = null) }
            fileBackupRepository.importFromUri(uri).fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isImporting = false, backupMessage = "Imported $count items") }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isImporting = false, backupMessage = "Import failed: ${e.message}") }
                }
            )
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

    fun setMtlsAlias(alias: String?) {
        viewModelScope.launch {
            prefs.saveMtlsAlias(alias)
            okHttpClient.connectionPool.evictAll()
        }
    }
}
