package com.insituledger.app.data.remote.api

import com.insituledger.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface ScheduledApi {
    @GET("scheduled")
    suspend fun list(): Response<List<ScheduledTransactionDto>>

    @POST("scheduled")
    suspend fun create(@Body input: ScheduledInput): Response<IdResponse>

    @PUT("scheduled/{id}")
    suspend fun update(@Path("id") id: Long, @Body input: ScheduledInput): Response<Unit>

    @DELETE("scheduled/{id}")
    suspend fun delete(@Path("id") id: Long): Response<Unit>
}
