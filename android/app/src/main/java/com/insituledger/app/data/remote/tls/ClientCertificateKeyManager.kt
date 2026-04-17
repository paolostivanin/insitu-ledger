package com.insituledger.app.data.remote.tls

import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import android.util.Log
import com.insituledger.app.data.local.datastore.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

@Singleton
class ClientCertificateKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferences
) : X509ExtendedKeyManager() {

    private fun activeAlias(): String? {
        if (!prefs.getMtlsEnabledImmediate()) return null
        return prefs.getMtlsAliasImmediate()?.takeIf { it.isNotBlank() }
    }

    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        activeAlias()

    override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        activeAlias()

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        val key = alias ?: return null
        return try {
            KeyChain.getCertificateChain(context, key)
        } catch (e: KeyChainException) {
            Log.w(TAG, "Failed to load certificate chain for alias '$key'", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted loading certificate chain for alias '$key'", e)
            null
        }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        val key = alias ?: return null
        return try {
            KeyChain.getPrivateKey(context, key)
        } catch (e: KeyChainException) {
            Log.w(TAG, "Failed to load private key for alias '$key'", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted loading private key for alias '$key'", e)
            null
        }
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        activeAlias()?.let { arrayOf(it) }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    companion object {
        private const val TAG = "ClientCertKM"
    }
}
