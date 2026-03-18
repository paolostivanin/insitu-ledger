package com.insituledger.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.repository.AuthRepository
import com.insituledger.app.data.repository.FileBackupRepository
import com.insituledger.app.data.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: String = "system",
    val biometricEnabled: Boolean = false,
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
    val backupMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val fileBackupRepository: FileBackupRepository,
    private val prefs: UserPreferences,
    private val pendingOpDao: PendingOperationDao
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
            combine(
                prefs.lastSyncVersionFlow,
                pendingOpDao.getCount()
            ) { syncVersion, pending ->
                _uiState.update {
                    it.copy(lastSyncVersion = syncVersion, pendingOps = pending)
                }
            }.collect()
        }
    }

    fun setTheme(mode: String) {
        viewModelScope.launch { prefs.saveThemeMode(mode) }
    }

    fun setBiometric(enabled: Boolean) {
        viewModelScope.launch { prefs.saveBiometricEnabled(enabled) }
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
}
