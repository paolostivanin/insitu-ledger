package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface SharedApi {
    @GET("shared/accessible")
    suspend fun accessible(): Response<List<AccessibleOwnerDto>>

    @GET("shared")
    suspend fun list(): Response<List<SharedAccessDto>>

    @POST("shared")
    suspend fun create(@Body input: SharedAccessInput): Response<IdResponse>

    @DELETE("shared/{id}")
    suspend fun delete(@Path("id") id: Long): Response<Unit>
}
