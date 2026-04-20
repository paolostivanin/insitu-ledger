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

    // KeyChain.getPrivateKey / getCertificateChain do binder IPC into the
    // system service and block. They run on the OkHttp connection thread
    // during the TLS handshake, so we cache by alias and only re-fetch
    // when the alias changes (handled by invalidate()).
    private data class CachedMaterial(
        val alias: String,
        val chain: Array<X509Certificate>?,
        val privateKey: PrivateKey?
    )

    @Volatile private var cache: CachedMaterial? = null
    private val cacheLock = Any()

    fun invalidate() {
        synchronized(cacheLock) { cache = null }
    }

    private fun activeAlias(): String? {
        if (!prefs.getMtlsEnabledImmediate()) return null
        return prefs.getMtlsAliasImmediate()?.takeIf { it.isNotBlank() }
    }

    private fun loadFor(alias: String): CachedMaterial {
        synchronized(cacheLock) {
            val current = cache
            if (current != null && current.alias == alias) return current

            val chain = try {
                KeyChain.getCertificateChain(context, alias)
            } catch (e: KeyChainException) {
                Log.w(TAG, "Failed to load certificate chain for alias '$alias'", e); null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); null
            }
            val key = try {
                KeyChain.getPrivateKey(context, alias)
            } catch (e: KeyChainException) {
                Log.w(TAG, "Failed to load private key for alias '$alias'", e); null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); null
            }

            val fresh = CachedMaterial(alias, chain, key)
            cache = fresh
            return fresh
        }
    }

    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        activeAlias()

    override fun chooseEngineClientAlias(keyTypes: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        activeAlias()

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        val key = alias ?: return null
        return loadFor(key).chain
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        val key = alias ?: return null
        return loadFor(key).privateKey
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        activeAlias()?.let { arrayOf(it) }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    companion object {
        private const val TAG = "ClientCertKM"
    }
}
