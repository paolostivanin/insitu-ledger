package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("auth/me")
    suspend fun getMe(): Response<UserProfileDto>

    @POST("auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<Unit>

    @PUT("auth/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<Unit>

    @POST("auth/totp/setup")
    suspend fun totpSetup(): Response<TotpSetupResponse>

    @POST("auth/totp/verify")
    suspend fun totpVerify(@Body request: TotpVerifyRequest): Response<Unit>

    @POST("auth/totp/reset")
    suspend fun totpReset(@Body request: TotpResetRequest): Response<TotpSetupResponse>
}
