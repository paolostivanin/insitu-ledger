package com.insituledger.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.insituledger.app.data.local.security.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStore: SecureStore,
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
        val WEEK_START_DAY = stringPreferencesKey("week_start_day")
        val SCREEN_SECURE = booleanPreferencesKey("screen_secure")
        val AUTO_BACKUP_FOLDER_URI = stringPreferencesKey("auto_backup_folder_uri")
        val AUTO_BACKUP_DAILY_ENABLED = booleanPreferencesKey("auto_backup_daily_enabled")
        val AUTO_BACKUP_DAILY_RETENTION = intPreferencesKey("auto_backup_daily_retention")
        val AUTO_BACKUP_WEEKLY_ENABLED = booleanPreferencesKey("auto_backup_weekly_enabled")
        val AUTO_BACKUP_WEEKLY_RETENTION = intPreferencesKey("auto_backup_weekly_retention")
        val AUTO_BACKUP_MONTHLY_ENABLED = booleanPreferencesKey("auto_backup_monthly_enabled")
        val AUTO_BACKUP_MONTHLY_RETENTION = intPreferencesKey("auto_backup_monthly_retention")
        val MTLS_ENABLED = booleanPreferencesKey("mtls_enabled")
        val MTLS_ALIAS = stringPreferencesKey("mtls_alias")
        val CURRENCY_SYMBOL = stringPreferencesKey("currency_symbol")
        const val DEFAULT_CURRENCY_SYMBOL = "€"
        val ALLOW_CLEARTEXT_HTTP = booleanPreferencesKey("allow_cleartext_http")
    }

    val tokenFlow: Flow<String?> = secureStore.stringFlow(SecureStore.KEY_TOKEN)
    val backupPassphraseSetFlow: Flow<Boolean> =
        secureStore.stringFlow(SecureStore.KEY_BACKUP_PASSPHRASE).map { it != null }

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
    val weekStartDayFlow: Flow<String> = context.dataStore.data.map { it[WEEK_START_DAY] ?: "monday" }
    val screenSecureFlow: Flow<Boolean> = context.dataStore.data.map { it[SCREEN_SECURE] ?: true }
    val autoBackupFolderUriFlow: Flow<String?> = context.dataStore.data.map { it[AUTO_BACKUP_FOLDER_URI] }
    val autoBackupDailyEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_DAILY_ENABLED] ?: false }
    val autoBackupDailyRetentionFlow: Flow<Int> = context.dataStore.data.map { it[AUTO_BACKUP_DAILY_RETENTION] ?: 7 }
    val autoBackupWeeklyEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_WEEKLY_ENABLED] ?: false }
    val autoBackupWeeklyRetentionFlow: Flow<Int> = context.dataStore.data.map { it[AUTO_BACKUP_WEEKLY_RETENTION] ?: 4 }
    val autoBackupMonthlyEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_BACKUP_MONTHLY_ENABLED] ?: false }
    val autoBackupMonthlyRetentionFlow: Flow<Int> = context.dataStore.data.map { it[AUTO_BACKUP_MONTHLY_RETENTION] ?: 6 }
    val mtlsEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[MTLS_ENABLED] ?: false }
    val mtlsAliasFlow: Flow<String?> = context.dataStore.data.map { it[MTLS_ALIAS] }
    val currencySymbolFlow: Flow<String> = context.dataStore.data.map { it[CURRENCY_SYMBOL] ?: DEFAULT_CURRENCY_SYMBOL }
    val allowCleartextHttpFlow: Flow<Boolean> = context.dataStore.data.map { it[ALLOW_CLEARTEXT_HTTP] ?: false }

    suspend fun saveToken(token: String) {
        secureStore.putString(SecureStore.KEY_TOKEN, token)
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

    suspend fun saveWeekStartDay(day: String) {
        context.dataStore.edit { it[WEEK_START_DAY] = day }
    }

    suspend fun saveScreenSecure(enabled: Boolean) {
        context.dataStore.edit { it[SCREEN_SECURE] = enabled }
    }

    suspend fun saveAutoBackupFolderUri(uri: String?) {
        context.dataStore.edit {
            if (uri != null) it[AUTO_BACKUP_FOLDER_URI] = uri
            else it.remove(AUTO_BACKUP_FOLDER_URI)
        }
    }

    suspend fun saveAutoBackupDailyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_DAILY_ENABLED] = enabled }
    }

    suspend fun saveAutoBackupDailyRetention(count: Int) {
        context.dataStore.edit { it[AUTO_BACKUP_DAILY_RETENTION] = count }
    }

    suspend fun saveAutoBackupWeeklyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_WEEKLY_ENABLED] = enabled }
    }

    suspend fun saveAutoBackupWeeklyRetention(count: Int) {
        context.dataStore.edit { it[AUTO_BACKUP_WEEKLY_RETENTION] = count }
    }

    suspend fun saveAutoBackupMonthlyEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_BACKUP_MONTHLY_ENABLED] = enabled }
    }

    suspend fun saveAutoBackupMonthlyRetention(count: Int) {
        context.dataStore.edit { it[AUTO_BACKUP_MONTHLY_RETENTION] = count }
    }

    suspend fun saveMtlsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[MTLS_ENABLED] = enabled }
    }

    suspend fun saveMtlsAlias(alias: String?) {
        context.dataStore.edit {
            if (alias.isNullOrBlank()) it.remove(MTLS_ALIAS)
            else it[MTLS_ALIAS] = alias
        }
    }

    suspend fun saveCurrencySymbol(symbol: String) {
        context.dataStore.edit { it[CURRENCY_SYMBOL] = symbol }
    }

    suspend fun saveAllowCleartextHttp(allowed: Boolean) {
        context.dataStore.edit { it[ALLOW_CLEARTEXT_HTTP] = allowed }
    }

    suspend fun saveBackupPassphrase(passphrase: String?) {
        if (passphrase.isNullOrEmpty()) {
            secureStore.remove(SecureStore.KEY_BACKUP_PASSPHRASE)
        } else {
            secureStore.putString(SecureStore.KEY_BACKUP_PASSPHRASE, passphrase)
        }
    }

    suspend fun getBackupPassphrase(): String? = secureStore.getString(SecureStore.KEY_BACKUP_PASSPHRASE)

    @Volatile
    private var _syncModeCache: String = "none"
    @Volatile
    private var _autoBackupFolderUriCache: String? = null
    @Volatile
    private var _autoBackupDailyEnabledCache: Boolean = false
    @Volatile
    private var _autoBackupDailyRetentionCache: Int = 7
    @Volatile
    private var _autoBackupWeeklyEnabledCache: Boolean = false
    @Volatile
    private var _autoBackupWeeklyRetentionCache: Int = 4
    @Volatile
    private var _autoBackupMonthlyEnabledCache: Boolean = false
    @Volatile
    private var _autoBackupMonthlyRetentionCache: Int = 6
    @Volatile
    private var _mtlsEnabledCache: Boolean = false
    @Volatile
    private var _mtlsAliasCache: String? = null
    @Volatile
    private var _currencySymbolCache: String = DEFAULT_CURRENCY_SYMBOL
    @Volatile
    private var _allowCleartextHttpCache: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        syncModeFlow.onEach { _syncModeCache = it }.launchIn(scope)
        autoBackupFolderUriFlow.onEach { _autoBackupFolderUriCache = it }.launchIn(scope)
        autoBackupDailyEnabledFlow.onEach { _autoBackupDailyEnabledCache = it }.launchIn(scope)
        autoBackupDailyRetentionFlow.onEach { _autoBackupDailyRetentionCache = it }.launchIn(scope)
        autoBackupWeeklyEnabledFlow.onEach { _autoBackupWeeklyEnabledCache = it }.launchIn(scope)
        autoBackupWeeklyRetentionFlow.onEach { _autoBackupWeeklyRetentionCache = it }.launchIn(scope)
        autoBackupMonthlyEnabledFlow.onEach { _autoBackupMonthlyEnabledCache = it }.launchIn(scope)
        autoBackupMonthlyRetentionFlow.onEach { _autoBackupMonthlyRetentionCache = it }.launchIn(scope)
        mtlsEnabledFlow.onEach { _mtlsEnabledCache = it }.launchIn(scope)
        mtlsAliasFlow.onEach { _mtlsAliasCache = it }.launchIn(scope)
        currencySymbolFlow.onEach { _currencySymbolCache = it }.launchIn(scope)
        allowCleartextHttpFlow.onEach { _allowCleartextHttpCache = it }.launchIn(scope)
    }

    fun getSyncModeImmediate(): String = _syncModeCache
    fun getAutoBackupFolderUriImmediate(): String? = _autoBackupFolderUriCache
    fun getAutoBackupDailyEnabledImmediate(): Boolean = _autoBackupDailyEnabledCache
    fun getAutoBackupDailyRetentionImmediate(): Int = _autoBackupDailyRetentionCache
    fun getAutoBackupWeeklyEnabledImmediate(): Boolean = _autoBackupWeeklyEnabledCache
    fun getAutoBackupWeeklyRetentionImmediate(): Int = _autoBackupWeeklyRetentionCache
    fun getAutoBackupMonthlyEnabledImmediate(): Boolean = _autoBackupMonthlyEnabledCache
    fun getAutoBackupMonthlyRetentionImmediate(): Int = _autoBackupMonthlyRetentionCache
    fun getMtlsEnabledImmediate(): Boolean = _mtlsEnabledCache
    fun getMtlsAliasImmediate(): String? = _mtlsAliasCache
    fun getCurrencySymbolImmediate(): String = _currencySymbolCache
    fun getAllowCleartextHttpImmediate(): Boolean = _allowCleartextHttpCache

    suspend fun clearAll() {
        secureStore.remove(SecureStore.KEY_TOKEN)
        context.dataStore.edit { prefs ->
            val mtlsEnabled = prefs[MTLS_ENABLED]
            val mtlsAlias = prefs[MTLS_ALIAS]
            prefs.clear()
            if (mtlsEnabled != null) prefs[MTLS_ENABLED] = mtlsEnabled
            if (mtlsAlias != null) prefs[MTLS_ALIAS] = mtlsAlias
        }
    }

    fun getTokenImmediate(): String? = secureStore.getStringBlocking(SecureStore.KEY_TOKEN)
}
