package com.insituledger.app.data.remote.interceptor

import com.insituledger.app.data.local.datastore.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthInterceptor @Inject constructor(
    private val userPreferences: UserPreferences
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val serverUrl = runBlocking { userPreferences.serverUrlFlow.first() }

        // Rewrite URL to point to the user's server
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
                val token = runBlocking { userPreferences.tokenFlow.first() }
                if (token != null) {
                    builder.header("Authorization", "Bearer $token")
                }
            }

            builder.build()
        } else {
            original
        }

        return chain.proceed(newRequest)
    }
}
