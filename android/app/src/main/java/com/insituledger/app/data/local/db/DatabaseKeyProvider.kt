package com.insituledger.app.data.local.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILE,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Returns the DB passphrase as a base64 string of 32 random bytes (256 bits of entropy).
     *
     * The same string is fed both to SQLCipher's SupportFactory (as ASCII bytes) at runtime
     * and to the one-time migration's ATTACH KEY clause, so PBKDF2 derives the same key
     * in both paths. Base64 keeps the value SQL-safe (alphabet has no quotes or backslashes).
     */
    fun getOrCreatePassphrase(): String {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs.edit().putString(KEY_DB_PASSPHRASE, base64).apply()
        return base64
    }

    private companion object {
        const val PREFS_FILE = "insitu_ledger_db_key"
        const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
    }
}
