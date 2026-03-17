package com.insituledger.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.local.db.dao.PendingOperationDao
import com.insituledger.app.data.repository.AuthRepository
import com.insituledger.app.data.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val userName: String = "",
    val themeMode: String = "system",
    val biometricEnabled: Boolean = false,
    val pendingOps: Int = 0,
    val lastSyncVersion: Long = 0,
    val isSyncing: Boolean = false,
    val isChangingPassword: Boolean = false,
    val passwordError: String? = null,
    val passwordChanged: Boolean = false,
    val loggedOut: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncManager: SyncManager,
    private val prefs: UserPreferences,
    private val pendingOpDao: PendingOperationDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                prefs.userNameFlow,
                prefs.themeModeFlow,
                prefs.lastSyncVersionFlow,
                pendingOpDao.getCount(),
                prefs.biometricEnabledFlow
            ) { values ->
                _uiState.update {
                    it.copy(
                        userName = (values[0] as? String) ?: "",
                        themeMode = values[1] as String,
                        lastSyncVersion = values[2] as Long,
                        pendingOps = values[3] as Int,
                        biometricEnabled = values[4] as Boolean
                    )
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

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            syncManager.cancelAll()
            _uiState.update { it.copy(loggedOut = true) }
        }
    }
}
