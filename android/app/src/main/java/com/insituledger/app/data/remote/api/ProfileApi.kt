package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.UserPreferencesDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

interface ProfileApi {
    @GET("profile/preferences")
    suspend fun getPreferences(): Response<UserPreferencesDto>

    @PUT("profile/preferences")
    suspend fun setPreferences(@Body body: UserPreferencesDto): Response<UserPreferencesDto>
}
