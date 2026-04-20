package com.insituledger.app.data.remote.interceptor

import com.insituledger.app.data.local.datastore.UserPreferences
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Default-deny cleartext (http://) requests. Self-hosters on a LAN can opt in
// via Settings; everyone else is held to TLS. Pairs with the platform-level
// network_security_config which still permits cleartext for the LAN case but
// trusts this interceptor as the authoritative gate.
@Singleton
class CleartextGuardInterceptor @Inject constructor(
    private val prefs: UserPreferences
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps && !prefs.getAllowCleartextHttpImmediate()) {
            throw IOException(
                "Refusing cleartext request to ${request.url.host}. " +
                "Enable 'Allow HTTP (insecure)' in Settings if your server is on a trusted LAN."
            )
        }
        return chain.proceed(request)
    }
}
