package com.insituledger.app.data.repository

import com.insituledger.app.data.local.datastore.UserPreferences
import com.insituledger.app.data.remote.api.AuthApi
import com.insituledger.app.data.remote.dto.*
import com.insituledger.app.data.remote.interceptor.AuthInterceptor
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val prefs: UserPreferences,
    private val authInterceptor: AuthInterceptor
) {
    val isLoggedIn: Flow<String?> = prefs.tokenFlow
    val forcePasswordChange: Flow<Boolean> = prefs.forcePasswordChangeFlow

    suspend fun login(serverUrl: String, login: String, password: String, totpCode: String? = null): Result<LoginResponse> {
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')
            authInterceptor.setServerUrl(normalizedUrl)
            prefs.saveServerUrl(normalizedUrl)
            val response = authApi.login(LoginRequest(login, password, totpCode))
            if (response.isSuccessful) {
                val body = response.body()!!
                if (body.totpRequired == true && body.token == null) {
                    Result.success(body)
                } else {
                    body.token?.let { prefs.saveToken(it) }
                    prefs.saveLoginData(
                        userId = body.userId,
                        name = body.name,
                        isAdmin = body.isAdmin,
                        forcePasswordChange = body.forcePasswordChange,
                        totpEnabled = body.totpEnabled
                    )
                    Result.success(body)
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "Login failed"
                Result.failure(Exception(errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        try {
            authApi.logout()
        } catch (_: Exception) {}
        prefs.clearAll()
    }

    suspend fun getProfile(): Result<UserProfileDto> {
        return try {
            val response = authApi.getMe()
            if (response.isSuccessful) Result.success(response.body()!!)
            else Result.failure(Exception(response.errorBody()?.string()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Result<Unit> {
        return try {
            val response = authApi.changePassword(ChangePasswordRequest(currentPassword, newPassword))
            if (response.isSuccessful) {
                prefs.saveLoginData(
                    userId = 0, name = "", isAdmin = false,
                    forcePasswordChange = false, totpEnabled = false
                )
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.errorBody()?.string()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(username: String?, email: String?, name: String?): Result<Unit> {
        return try {
            val response = authApi.updateProfile(UpdateProfileRequest(username, email, name))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(response.errorBody()?.string()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun totpSetup(): Result<TotpSetupResponse> {
        return try {
            val response = authApi.totpSetup()
            if (response.isSuccessful) Result.success(response.body()!!)
            else Result.failure(Exception(response.errorBody()?.string()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun totpVerify(code: String): Result<Unit> {
        return try {
            val response = authApi.totpVerify(TotpVerifyRequest(code))
            if (response.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(response.errorBody()?.string()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun totpReset(password: String): Result<TotpSetupResponse> {
        return try {
            val response = authApi.totpReset(TotpResetRequest(password))
            if (response.isSuccessful) Result.success(response.body()!!)
            else Result.failure(Exception(response.errorBody()?.string()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
