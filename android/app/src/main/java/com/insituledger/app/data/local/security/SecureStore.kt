package com.insituledger.app.data.local.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secureDataStore: DataStore<Preferences> by preferencesDataStore(name = "secure_store")

@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: AndroidKeystoreCipher,
) {
    private val store: DataStore<Preferences> get() = context.secureDataStore

    suspend fun putString(key: String, value: String) {
        val encrypted = cipher.encrypt(value)
        store.edit { it[stringPreferencesKey(key)] = encrypted }
    }

    suspend fun getString(key: String): String? {
        val encrypted = store.data.first()[stringPreferencesKey(key)] ?: return null
        return cipher.decrypt(encrypted)
    }

    suspend fun remove(key: String) {
        store.edit { it.remove(stringPreferencesKey(key)) }
    }

    suspend fun contains(key: String): Boolean =
        store.data.first().contains(stringPreferencesKey(key))

    fun stringFlow(key: String): Flow<String?> =
        store.data.map { prefs -> prefs[stringPreferencesKey(key)]?.let(cipher::decrypt) }

    fun getStringBlocking(key: String): String? = runBlocking(Dispatchers.IO) { getString(key) }

    companion object {
        const val KEY_TOKEN = "token"
        const val KEY_BACKUP_PASSPHRASE = "backup_passphrase"
        const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
    }
}
