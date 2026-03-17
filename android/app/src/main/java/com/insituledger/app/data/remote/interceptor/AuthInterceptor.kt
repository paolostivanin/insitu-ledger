package com.insituledger.app.data.remote.interceptor

import com.insituledger.app.data.local.datastore.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val userPreferences: UserPreferences
) : Interceptor {

    @Volatile
    private var cachedServerUrl: String = ""

    @Volatile
    private var cachedToken: String? = userPreferences.getTokenImmediate()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        userPreferences.serverUrlFlow.onEach { cachedServerUrl = it }.launchIn(scope)
        userPreferences.tokenFlow.onEach { cachedToken = it }.launchIn(scope)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val serverUrl = cachedServerUrl

        val newRequest = if (serverUrl.isNotBlank()) {
            val baseUrl = "$serverUrl/api/".toHttpUrlOrNull()
            val newUrl = if (baseUrl != null) {
                original.url.newBuilder()
                    .scheme(baseUrl.scheme)
                    .host(baseUrl.host)
                    .port(baseUrl.port)
                    .build()
            } else {
                original.url
            }

            val builder = original.newBuilder().url(newUrl)

            // Add auth token (skip for login endpoint)
            if (!original.url.encodedPath.endsWith("auth/login")) {
                val token = cachedToken
                if (token != null) {
                    builder.header("Authorization", "Bearer $token")
                }
            }

            builder.build()
        } else {
            original
        }

        val response = chain.proceed(newRequest)

        // Auto-logout on 401 (except for login endpoint)
        if (response.code == 401 && !original.url.encodedPath.endsWith("auth/login")) {
            cachedToken = null
            scope.launch { userPreferences.clearAll() }
        }

        return response
    }
}
