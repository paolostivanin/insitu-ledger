package com.insituledger.app.di

import android.content.Context
import com.insituledger.app.BuildConfig
import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.api.*
import com.insituledger.app.data.remote.interceptor.AuthInterceptor
import com.insituledger.app.data.remote.interceptor.CleartextGuardInterceptor
import com.insituledger.app.data.remote.tls.ClientCertificateKeyManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        cleartextGuard: CleartextGuardInterceptor,
        clientCertKeyManager: ClientCertificateKeyManager,
        @ApplicationContext context: Context
    ): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, 10L * 1024 * 1024) // 10MB

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val trustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(clientCertKeyManager), arrayOf(trustManager), null)

        return OkHttpClient.Builder()
            .cache(cache)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .addInterceptor(authInterceptor)
            .addInterceptor(cleartextGuard)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, userPreferences: UserPreferences): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost/api/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides @Singleton fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)
    @Provides @Singleton fun provideTransactionApi(retrofit: Retrofit): TransactionApi = retrofit.create(TransactionApi::class.java)
    @Provides @Singleton fun provideCategoryApi(retrofit: Retrofit): CategoryApi = retrofit.create(CategoryApi::class.java)
    @Provides @Singleton fun provideAccountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)
    @Provides @Singleton fun provideScheduledApi(retrofit: Retrofit): ScheduledApi = retrofit.create(ScheduledApi::class.java)
    @Provides @Singleton fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)
    @Provides @Singleton fun provideSharedApi(retrofit: Retrofit): SharedApi = retrofit.create(SharedApi::class.java)
    @Provides @Singleton fun provideProfileApi(retrofit: Retrofit): ProfileApi = retrofit.create(ProfileApi::class.java)
}
