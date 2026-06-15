package com.insituledger.app.di

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkModuleTest {
    @Test
    fun debugLoggingNeverUsesBodies() {
        assertEquals(HttpLoggingInterceptor.Level.BASIC, NetworkModule.loggingLevel(isDebug = true))
        assertFalse(NetworkModule.loggingLevel(isDebug = true) == HttpLoggingInterceptor.Level.BODY)
    }

    @Test
    fun releaseLoggingIsDisabled() {
        assertEquals(HttpLoggingInterceptor.Level.NONE, NetworkModule.loggingLevel(isDebug = false))
    }

    @Test
    fun secretHeadersAreRedacted() {
        assertEquals(
            setOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization"),
            NetworkModule.REDACTED_HEADERS.toSet()
        )
    }
}
