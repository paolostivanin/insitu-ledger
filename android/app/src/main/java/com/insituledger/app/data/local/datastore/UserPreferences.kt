package com.insituledger.app.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USER_ID = longPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val IS_ADMIN = booleanPreferencesKey("is_admin")
        val FORCE_PASSWORD_CHANGE = booleanPreferencesKey("force_password_change")
        val TOTP_ENABLED = booleanPreferencesKey("totp_enabled")
        val LAST_SYNC_VERSION = longPreferencesKey("last_sync_version")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val SYNC_MODE = stringPreferencesKey("sync_mode") // "none", "webapp", "gdrive"
        val SHARED_OWNER_ID = longPreferencesKey("shared_owner_id")
        val LAST_USED_ACCOUNT_ID = longPreferencesKey("last_used_account_id")

        private const val ENCRYPTED_PREFS_FILE = "secure_prefs"
        private const val KEY_TOKEN = "token"
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            ENCRYPTED_PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _tokenFlow = MutableStateFlow(encryptedPrefs.getString(KEY_TOKEN, null))
    val tokenFlow: Flow<String?> = _tokenFlow

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "" }
    val userIdFlow: Flow<Long?> = context.dataStore.data.map { it[USER_ID] }
    val userNameFlow: Flow<String?> = context.dataStore.data.map { it[USER_NAME] }
    val isAdminFlow: Flow<Boolean> = context.dataStore.data.map { it[IS_ADMIN] ?: false }
    val forcePasswordChangeFlow: Flow<Boolean> = context.dataStore.data.map { it[FORCE_PASSWORD_CHANGE] ?: false }
    val totpEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[TOTP_ENABLED] ?: false }
    val lastSyncVersionFlow: Flow<Long> = context.dataStore.data.map { it[LAST_SYNC_VERSION] ?: 0L }
    val themeModeFlow: Flow<String> = context.dataStore.data.map { it[THEME_MODE] ?: "system" }
    val biometricEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[BIOMETRIC_ENABLED] ?: false }
    val syncModeFlow: Flow<String> = context.dataStore.data.map { it[SYNC_MODE] ?: "none" }
    val sharedOwnerIdFlow: Flow<Long?> = context.dataStore.data.map { it[SHARED_OWNER_ID] }
    val lastUsedAccountIdFlow: Flow<Long?> = context.dataStore.data.map { it[LAST_USED_ACCOUNT_ID] }

    suspend fun saveToken(token: String) {
        encryptedPrefs.edit().putString(KEY_TOKEN, token).apply()
        _tokenFlow.value = token
    }

    suspend fun saveServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url }
    }

    suspend fun saveLoginData(userId: Long, name: String, isAdmin: Boolean, forcePasswordChange: Boolean, totpEnabled: Boolean) {
        context.dataStore.edit {
            it[USER_ID] = userId
            it[USER_NAME] = name
            it[IS_ADMIN] = isAdmin
            it[FORCE_PASSWORD_CHANGE] = forcePasswordChange
            it[TOTP_ENABLED] = totpEnabled
        }
    }

    suspend fun saveLastSyncVersion(version: Long) {
        context.dataStore.edit { it[LAST_SYNC_VERSION] = version }
    }

    suspend fun saveThemeMode(mode: String) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }

    suspend fun saveBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BIOMETRIC_ENABLED] = enabled }
    }

    suspend fun saveSyncMode(mode: String) {
        context.dataStore.edit { it[SYNC_MODE] = mode }
    }

    suspend fun saveSharedOwnerId(ownerId: Long?) {
        context.dataStore.edit {
            if (ownerId != null) it[SHARED_OWNER_ID] = ownerId
            else it.remove(SHARED_OWNER_ID)
        }
    }

    suspend fun saveLastUsedAccountId(accountId: Long) {
        context.dataStore.edit { it[LAST_USED_ACCOUNT_ID] = accountId }
    }

    @Volatile
    private var _syncModeCache: String = "none"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        syncModeFlow.onEach { _syncModeCache = it }.launchIn(scope)
    }

    fun getSyncModeImmediate(): String = _syncModeCache

    suspend fun clearAll() {
        encryptedPrefs.edit().remove(KEY_TOKEN).apply()
        _tokenFlow.value = null
        context.dataStore.edit { it.clear() }
    }

    fun getTokenImmediate(): String? = encryptedPrefs.getString(KEY_TOKEN, null)
}
