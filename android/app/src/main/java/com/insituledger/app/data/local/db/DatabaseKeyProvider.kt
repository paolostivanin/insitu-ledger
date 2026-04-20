package com.insituledger.app.data.local.db

import android.util.Base64
import com.insituledger.app.data.local.security.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyProvider @Inject constructor(
    private val secureStore: SecureStore,
) {
    /**
     * Returns the DB passphrase as a base64 string of 32 random bytes (256 bits of entropy).
     *
     * The same string is fed both to SQLCipher's SupportFactory (as ASCII bytes) at runtime
     * and to the one-time migration's ATTACH KEY clause, so PBKDF2 derives the same key
     * in both paths. Base64 keeps the value SQL-safe (alphabet has no quotes or backslashes).
     */
    fun getOrCreatePassphrase(): String {
        secureStore.getStringBlocking(SecureStore.KEY_DB_PASSPHRASE)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        runBlocking(Dispatchers.IO) { secureStore.putString(SecureStore.KEY_DB_PASSPHRASE, base64) }
        return base64
    }
}
